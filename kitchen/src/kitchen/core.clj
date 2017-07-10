;;
;;       Copyright (c) 2017 Two Sigma Investments, LP.
;;       All Rights Reserved
;;
;;       THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF
;;       Two Sigma Investments, LP.
;;
;;       The copyright notice above does not evidence any
;;       actual or intended publication of such source code.
;;
(ns kitchen.core
  (:require [clj-time.core :as t]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :refer (closed?)]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [kitchen.pi :as pi]
            [kitchen.spnego :as spnego]
            [kitchen.utils :as utils]
            [plumbing.core :as pc]
            [qbits.jet.client.http :as http]
            [qbits.jet.server :as server]
            [ring.middleware.basic-authentication :as basic-authentication]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.params :as params])
  (:gen-class)
  (:import (java.io InputStream ByteArrayOutputStream)
           java.net.URI
           java.nio.ByteBuffer
           java.util.UUID
           java.util.zip.GZIPOutputStream
           org.eclipse.jetty.server.HttpOutput))


(def async-requests (atom {}))
(def pending-http-requests (atom 0))
(def total-http-requests (atom 0))
(def pending-ws-requests (atom 0))
(def total-ws-requests (atom 0))

(defn request->cid [request]
  (get-in request [:headers "x-cid"]))

(defn- request->cid-string [request]
  (str "[CID=" (request->cid request) "]"))

(defn printlog
  ([request & messages]
   (log/info (request->cid-string request) (str/join " " messages))))

(defn- poison-pill-fn
  "Sends a faulty InputStream directly into Jetty's response output stream (HttpOutput) to trigger a failure path.
   When a streaming chunked transfer from a backend fails, Waiter needs to relay this error to its client by not
   sending a terminating chunk."
  [request]
  (fn [output-stream]
    (try
      (printlog request "executing poison pill")
      (let [input-stream (proxy [InputStream] []
                           (available [] true) ; claim data is available to enable trigger exception on read()
                           (close [])
                           (read [_ _ _]
                             (let [message "read(byte[], int, int) used to trigger exception!"]
                               (printlog request "poison pill:" message)
                               (throw (UnsupportedOperationException. message)))))]
        (.sendContent ^HttpOutput output-stream input-stream))
      (catch Exception e
        (log/warn e (request->cid-string request) "gobbling expected error while passing poison pill")))))

(defmacro fail-response
  "Fails the current response by either sending a poison pill or by terminating the JVM."
  [request resp-chan fail-by-terminating-jvm]
  `(if ~fail-by-terminating-jvm
     (do
       (printlog ~request "failing request by terminating jvm with exit code 1")
       (System/exit 1))
     (do
       (printlog ~request "sending poison pill")
       (async/>! ~resp-chan (poison-pill-fn ~request)))))

(defn- gc-async-request
  "Completes a request and performs GC by cleaning up entry in async-requests."
  [request-id]
  (swap!
    async-requests
    (fn [request-id->metdadata]
      (when (contains? request-id->metdadata request-id)
        (swap! pending-http-requests dec))
      (dissoc request-id->metdadata request-id))))

(defn- async-request-handler
  "Handler for async requests.
   It spawns an async to perform computation for a given amount of time.
   Also, it triggers GC of response data after completion of request processing."
  [{:keys [headers] :as request}]
  (let [request-id (get headers "x-kitchen-request-id")
        processing-time-ms (Integer/parseInt (get headers "x-kitchen-delay-ms" "20000"))
        linger-time-ms (Integer/parseInt (get headers "x-kitchen-store-async-response-ms" "10000"))
        exclude-headers (str/lower-case (get headers "x-kitchen-exclude-headers" ""))
        current-time (t/now)
        expires-time (t/plus current-time (t/millis processing-time-ms))
        process-handle (async/chan 1)
        gc-handle (async/chan 1)
        request-metadata {:channels {:gc-handle gc-handle
                                     :process-handle process-handle}
                          :cid (request->cid request)
                          :expires expires-time
                          :linger-ms linger-time-ms
                          :processing-time processing-time-ms
                          :received current-time}]
    (printlog request "async-request-handler:exclude-headers" exclude-headers)
    (printlog request "async-request-handler: metadata" request-metadata)
    (swap! async-requests assoc request-id request-metadata)
    (swap! pending-http-requests inc)
    (printlog request "async-request-handler: starting processing")
    (let [async-handle (async/go
                         (async/<! (async/timeout processing-time-ms))
                         (printlog request "async-request-handler: completing request")
                         (swap! async-requests update-in [request-id]
                                (fn [request-metadata]
                                  (when request-metadata
                                    (assoc request-metadata :done true))))
                         (async/>! process-handle :complete)
                         (async/close! process-handle)

                         (async/<! (async/timeout linger-time-ms))
                         (printlog request "async-request-handler: gc-ing request data")
                         (gc-async-request request-id)
                         (async/>! gc-handle :complete)
                         (async/close! gc-handle)

                         :complete)]
      (swap! async-requests assoc-in [request-id :channels :complete-handle] async-handle))
    {:status 202
     :headers (cond-> {"Content-Type" "text/plain", "x-kitchen-request-id" request-id}
                      (not (str/includes? exclude-headers "location"))
                      (assoc "Location" (str "/async/status?request-id=" request-id))
                      (not (str/includes? exclude-headers "expires"))
                      (assoc "Expires" (utils/date-to-str expires-time :format "EEE, dd MMM yyyy HH:mm:ss z")))
     :body (str "Accepted request " request-id)}))

(defn- async-status-handler
  "Handler for get/delete calls on async request status."
  [{:keys [headers query-params request-method] :as request}]
  (let [request-id (get query-params "request-id")
        _ (printlog request "async-status-handler: request id" request-id)
        request-metadata (get @async-requests request-id)]
    (if request-id
      (case request-method
        :delete
        (let [allow-async-cancel (Boolean/parseBoolean (get headers "x-kitchen-allow-async-cancel" "true"))
              respond-with-204-on-success (Boolean/parseBoolean (get headers "x-kitchen-204-on-async-cancel" "true"))]
          (printlog request "async-status-handler: allow-async-cancel" allow-async-cancel)
          (printlog request "async-status-handler: respond-with-204-on-success" respond-with-204-on-success)
          (if allow-async-cancel
            (do
              (gc-async-request request-id)
              (if request-metadata
                {:status (if respond-with-204-on-success 204 200)
                 :headers {"Content-Type" "text/plain"}
                 :body (if respond-with-204-on-success "" (str "Deleted request-id " request-id))}
                {:status 404
                 :headers {"Content-Type" "text/plain"}
                 :body (str request-id " not found!")}))
            {:status 405
             :headers {"Content-Type" "text/plain"}
             :body "Cancellation not supported!"}))
        :get
        (if request-metadata
          (if (:done request-metadata)
            {:status 303
             :headers {"Content-Type" "text/plain", "Location" (str "/async/result?request-id=" request-id)}
             :body (str "Processing complete for request-id " request-id)}
            {:status 200
             :headers {"Content-Type" "text/plain"}
             :body (str "Still processing request-id " request-id)})
          {:status 410
           :headers {"Content-Type" "text/plain"}
           :body (str "No data found for request-id " request-id)}))
      {:status 400
       :headers {"Content-Type" "text/plain"}
       :body (str "Missing request-id")})))

(defn- async-result-handler
  "Handler to return responses for async requests."
  [{:keys [query-params] :as request}]
  (let [request-id (get query-params "request-id")
        _ (printlog request "async-result-handler: request id" request-id)
        request-metadata (get @async-requests request-id)]
    (printlog request "async-result-handler: result" request-metadata)
    (gc-async-request request-id)
    (utils/map->json-response {:result (dissoc request-metadata :async-handle)} :status (if request-metadata 200 404))))

(defn- die-handler
  "Handler for receiving JVM exit requests."
  [{:keys [headers] :as request}]
  (let [delay-ms (Integer/parseInt (get headers "x-kitchen-delay-ms" "0"))
        die-after-ms (Integer/parseInt (get headers "x-kitchen-die-after-ms" "0"))]
    (printlog request "die-handler: delay-ms:" delay-ms)
    (printlog request "die-handler: die-after-ms:" die-after-ms)
    (async/thread
      (printlog request "sleeping" die-after-ms "ms before terminating jvm")
      (Thread/sleep die-after-ms)
      (printlog request "terminating jvm with exit code 1")
      (System/exit 1))
    (printlog request "sleeping for" delay-ms "ms before returning response")
    (Thread/sleep delay-ms)
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (str "Will die after " die-after-ms " ms.")}))

(defn- chunked-handler
  "Handles requests that may potentially fail, uses chunjked responses."
  [{:keys [headers] :as request}]
  (let [max-response-size 50000000
        resp-chan (async/chan 1024)
        response-size-in-bytes (Integer/parseInt (get headers "x-kitchen-response-size" (str max-response-size)))
        delay (Integer/parseInt (get headers "x-kitchen-chunk-delay" "0"))
        [chunk-size-in-bytes chunk-data-bytes]
        (let [default-data-string "Lorem ipsum dolor sit amet, proin in nibh tellus penatibus, viverra nunc risus ligula proin ligula."
              default-data-string-bytes (byte-array (map (comp byte int) default-data-string))
              num-data-string-bytes (count default-data-string-bytes)
              chunk-size-in-bytes (Integer/parseInt (get headers "x-kitchen-chunk-size" (str num-data-string-bytes)))]
          [chunk-size-in-bytes (byte-array chunk-size-in-bytes (cycle default-data-string-bytes))])
        fail-after-bytes (Integer/parseInt (get headers "x-kitchen-fail-after" (str (+ max-response-size chunk-size-in-bytes))))
        fail-by-terminating-jvm (boolean (Boolean/parseBoolean (get headers "x-kitchen-fail-by-terminating-jvm")))]
    (printlog request "chunked-handler: response-size-in-bytes: " response-size-in-bytes)
    (printlog request "chunked-handler: fail-after-bytes: " fail-after-bytes)
    (printlog request "chunked-handler: fail-by-terminating-jvm: " fail-by-terminating-jvm)
    (printlog request "chunked-handler: chunk-size-in-bytes: " chunk-size-in-bytes)
    (swap! pending-http-requests inc)
    (async/go
      (try
        (loop [bytes-sent 0]
          (if (or (>= bytes-sent fail-after-bytes)
                  (closed? (:ctrl request)))
            (fail-response request resp-chan fail-by-terminating-jvm)
            ; continue streaming data
            (when (< bytes-sent response-size-in-bytes)
              (let [bytes-to-send-this-iteration (min chunk-size-in-bytes (- response-size-in-bytes bytes-sent))]
                (printlog request "chunked-handler: streaming" bytes-to-send-this-iteration "bytes")
                (async/>! resp-chan (byte-array bytes-to-send-this-iteration chunk-data-bytes))
                (when (pos? delay)
                  ; sleep before next chunk
                  (Thread/sleep delay))
                (recur (+ bytes-sent bytes-to-send-this-iteration))))))
        (catch Exception e
          (printlog request "chunked-handler: sending exception message in response channel")
          (async/>! resp-chan (with-out-str (.printStackTrace e)))
          (.printStackTrace e))
        (finally
          (printlog request "chunked-handler: closing channel")
          (async/close! resp-chan)
          (swap! pending-http-requests dec))))
    {:status 200
     :headers {"Content-Type" "text/plain"
               "Transfer-Encoding" "chunked"}
     :body resp-chan}))

(defn- gzip-handler
  "Handles requests that may potentially fail, uses unchunked response."
  [{:keys [headers] :as request}]
  (let [max-response-size 50000000
        resp-chan (async/chan 1024)
        data-string "Lorem ipsum dolor sit amet, proin in nibh tellus penatibus, viverra nunc risus ligula proin ligula."
        data-string-bytes (byte-array (map (comp byte int) data-string))
        num-data-string-bytes (count data-string-bytes)
        response-size-bytes (Integer/parseInt (get headers "x-kitchen-response-size" (str max-response-size)))
        fail-after-bytes (Integer/parseInt (get headers "x-kitchen-fail-after" (str (+ max-response-size num-data-string-bytes))))
        fail-by-terminating-jvm (boolean (Boolean/parseBoolean (get headers "x-kitchen-fail-by-terminating-jvm")))
        chunked-mode (Boolean/parseBoolean (get headers "x-kitchen-chunked" "false"))]
    (printlog request "gzip-handler: chunked-mode: " chunked-mode)
    (printlog request "gzip-handler: response-size-bytes: " response-size-bytes)
    (printlog request "gzip-handler: fail-after-bytes: " fail-after-bytes)
    (printlog request "gzip-handler: fail-by-terminating-jvm: " fail-by-terminating-jvm)
    (printlog request "gzip-handler: chunked-mode: " chunked-mode)
    (let [data-bytes (byte-array response-size-bytes (cycle data-string-bytes))
          byte-array-output-stream (ByteArrayOutputStream.)
          _ (doto (GZIPOutputStream. byte-array-output-stream) (.write data-bytes) (.close))
          compressed-bytes (.toByteArray byte-array-output-stream)
          bytes-to-send (byte-array (min (count compressed-bytes) fail-after-bytes) (cycle compressed-bytes))]
      (printlog request "gzip-handler: num data-bytes: " (count data-bytes))
      (printlog request "gzip-handler: num compressed-bytes: " (count compressed-bytes))
      (swap! pending-http-requests inc)
      (async/go
        (try
          (async/>! resp-chan bytes-to-send)
          (when (< fail-after-bytes (count compressed-bytes))
            (fail-response request resp-chan fail-by-terminating-jvm))
          (catch Exception e
            (printlog request "gzip-handler: sending exception message in response channel")
            (async/>! resp-chan (with-out-str (.printStackTrace e))))
          (finally
            (printlog request "gzip-handler: closing channel")
            (async/close! resp-chan)
            (swap! pending-http-requests dec))))
      {:status 200
       :headers (cond-> {"Content-Type" "text/plain"
                         "Content-Encoding" "gzip"}
                        (not chunked-mode) (merge {"Content-Length" (str (count compressed-bytes))})) ; content length has to be raw byte size!
       :body resp-chan})))

(defn- request-info-handler
  "Returns the info received in the request."
  [{:keys [body headers request-method] :as request}]
  (when (instance? InputStream body)
    (slurp body))
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:headers headers
                          :request-method request-method})})

(defn- unchunked-handler
  "Handles requests that may potentially fail, uses unchunked response."
  [{:keys [headers] :as request}]
  (let [max-response-size 50000000
        resp-chan (async/chan 1024)
        data-string "Lorem ipsum dolor sit amet, proin in nibh tellus penatibus, viverra nunc risus ligula proin ligula."
        data-string-bytes (byte-array (map (comp byte int) data-string))
        num-data-string-bytes (count data-string-bytes)
        response-size-bytes (Integer/parseInt (get headers "x-kitchen-response-size" (str max-response-size)))
        fail-after-bytes (Integer/parseInt (get headers "x-kitchen-fail-after" (str (+ max-response-size num-data-string-bytes))))
        fail-by-terminating-jvm (boolean (Boolean/parseBoolean (get headers "x-kitchen-fail-by-terminating-jvm")))]
    (printlog request "unchunked-handler: response-size-bytes: " response-size-bytes)
    (printlog request "unchunked-handler: fail-after-bytes: " fail-after-bytes)
    (printlog request "unchunked-handler: fail-by-terminating-jvm: " fail-by-terminating-jvm)
    (swap! pending-http-requests inc)
    (async/go
      (try
        (async/>! resp-chan (byte-array (min response-size-bytes fail-after-bytes) (cycle data-string-bytes)))
        (when (< fail-after-bytes response-size-bytes)
          (fail-response request resp-chan fail-by-terminating-jvm))
        (catch Exception e
          (printlog request "unchunked-handler: sending exception message in response channel")
          (async/>! resp-chan (with-out-str (.printStackTrace e)))
          (.printStackTrace e))
        (finally
          (printlog request "unchunked-handler: closing channel")
          (async/close! resp-chan)
          (swap! pending-http-requests dec))))
    {:status 200
     :headers {"Content-Type" "text/plain"
               "Content-Length" (str response-size-bytes)}
     :body resp-chan}))

(defn environment-handler [_]
  {:status 200
   :body (json/write-str
           (->> (System/getenv)
                seq
                (remove (fn [[k _]] (str/includes? (str/lower-case k) "password")))
                (into {})))})

(defn state-handler [_]
  {:status 200
   :body (json/write-str
           {:async-requests @async-requests
            :pending-http-requests @pending-http-requests
            :pending-ws-requests @pending-ws-requests
            :total-http-requests @total-http-requests
            :total-ws-requests @total-ws-requests})})

(defn parse-cookies [header-value]
  (when-not (nil? header-value)
    (pc/map-vals #(identity {:value %}) (apply hash-map (str/split header-value #"=|,")))))

(defn- add-cookies [response cookies]
  (cookies/cookies-response (assoc response :cookies (parse-cookies cookies))))

(defn default-handler
  "The default handler of requests."
  [{:keys [headers body]}]
  (Thread/sleep (Integer/parseInt (get headers "x-kitchen-delay-ms" "1")))
  (when (contains? headers "x-kitchen-throw")
    (throw (ex-info "Instructed by header to throw" {})))
  (let [response {:status (if (contains? headers "x-kitchen-act-busy") 503 200)
                  :body (if (contains? headers "x-kitchen-echo") body "Hello World")}
        cookies (get headers "x-kitchen-cookies")]
    (cond-> response cookies (add-cookies cookies))))

(defn pi-handler
  [{:keys [form-params] :as req}]
  (let [{:strs [iterations threads]} form-params]
    {:body (json/write-str (pi/estimate-pi (utils/parse-positive-int iterations 1000)
                                           (utils/parse-positive-int threads 1)))
     :headers {"content-type" "application/json"}}))

(defn make-request
  "TODO(DPO)"
  ([url path &
    {:keys [body decompress-body headers http-method-fn multipart query-params use-spnego verbose]
     :or {body "", decompress-body false, headers {}, http-method-fn http/get, query-params {}, use-spnego false, verbose false}}]
   (let [request-url (str "http://" url path)
         request-headers (walk/stringify-keys headers)]
     (when verbose
       (log/info "request url:" request-url)
       (log/info "request headers:" (into (sorted-map) request-headers)))
     (let [start-nanos (System/nanoTime)
           {:keys [status]} (async/<!!
                              (http-method-fn
                                (http/client)
                                request-url
                                (cond-> {:throw-exceptions false
                                         :decompress-body decompress-body
                                         :follow-redirects false
                                         :headers request-headers
                                         :query-params query-params
                                         :body body}
                                        multipart (assoc :multipart multipart)
                                        use-spnego (assoc :auth (spnego/spnego-authentication (URI. url))))))
           elapsed-nanos (- (System/nanoTime) start-nanos)]
       {:status status
        :elapsed-nanos elapsed-nanos}))))

(defn make-parallel-requests
  "TODO(DPO)"
  [url path concurrency-level total-requests & {:keys [verbose use-spnego] :or {verbose false, use-spnego false}}]
  (let [responses-atom (atom [])
        make-requests (fn []
                        (try
                          (let [future-uuid (UUID/randomUUID)]
                            (loop []
                              (when verbose
                                (log/info "making request from future" future-uuid))
                              (let [response (make-request url path :verbose verbose :use-spnego use-spnego)]
                                (if (< (count (swap! responses-atom conj response)) total-requests)
                                  (recur)
                                  (log/info "no more requests to make from future" future-uuid)))))
                          (catch Throwable t
                            (log/error t "encountered exception making parallel requests"))))
        futures (doall (repeatedly concurrency-level #(future (make-requests))))]
    (dorun (map deref futures))
    @responses-atom))

(defn percentile
  "Calculates the p-th percentile of the values in coll
  (where 0 < p <= 100), using the Nearest Rank method:

  https://en.wikipedia.org/wiki/Percentile#The_Nearest_Rank_method

  Assumes that coll is sorted (see percentiles below for context)"
  [coll p]
  (if (or (empty? coll) (not (number? p)) (<= p 0) (> p 100))
    nil
    (nth coll
         (-> p
             (/ 100)
             (* (count coll))
             (Math/ceil)
             (dec)))))

(defn percentiles
  "Calculates the p-th percentiles of the values in coll for
  each p in p-list (where 0 < p <= 100), and returns a map of
  p -> value"
  [coll & p-list]
  (let [sorted (sort coll)]
    (into {} (map (fn [p] [p (percentile sorted p)]) p-list))))

(defn load-test-handler
  "TODO(DPO)"
  [{:keys [headers]}]
  (let [url (get headers "x-load-test-url")
        path (get headers "x-load-test-path" "/status")
        concurrency-level (Integer/parseInt (get headers "x-load-test-concurrency" "1"))
        total-requests (Integer/parseInt (get headers "x-load-test-total-requests" "1"))
        verbose (Boolean/parseBoolean (get headers "x-load-test-verbose" "false"))
        use-spnego (Boolean/parseBoolean (get headers "x-load-test-use-spnego" "false"))]
    (if url
      (let [responses (make-parallel-requests url path concurrency-level total-requests
                                              :verbose verbose
                                              :use-spnego use-spnego)
            total-requests-made (count responses)
            non-2xx-responses (count (filter #(or (< (:status %) 200) (>= (:status %) 300)) responses))
            latencies (map :elapsed-nanos responses)
            latency-percentiles (percentiles latencies 50 75 95 99 100)
            latency-average (/ (reduce + latencies) total-requests-made)]
        {:status (if (>= total-requests-made total-requests) 200 500)
         :body (json/write-str {:latency-average-nanos latency-average
                                :latency-percentiles-nanos latency-percentiles
                                :non-2xx-responses non-2xx-responses
                                :total-requests-made total-requests-made})})
      {:status 400
       :body "Missing header x-load-test-url"})))

(defn http-handler
  [request]
  (try
    (swap! total-http-requests inc)
    (let [{:keys [request-method uri] :as request}
          (-> request
              (update-in [:headers "x-cid"] (fn [cid] (or cid (str (UUID/randomUUID)))))
              (assoc-in [:headers "x-kitchen-request-id"] (str (UUID/randomUUID))))
          _ (printlog request "handler: request-method" request-method "and request uri:" uri)
          _ (printlog request "headers:" (into (sorted-map) (:headers request)))
          response (case uri
                     "/async/request" (async-request-handler request)
                     "/async/result" (async-result-handler request)
                     "/async/status" (async-status-handler request)
                     "/chunked" (chunked-handler request)
                     "/die" (die-handler request)
                     "/environment" (environment-handler request)
                     "/gzip" (gzip-handler request)
                     "/pi" (pi-handler request)
                     "/request-info" (request-info-handler request)
                     "/unchunked" (unchunked-handler request)
                     "/kitchen-state" (state-handler request)
                     "/load-test" (load-test-handler request)
                     (default-handler request))]
      (update-in response [:headers "x-cid"] (fn [cid] (or cid (request->cid request)))))
    (catch Exception e
      (log/error e "handler: encountered exception")
      (utils/exception->json-response e))))

(defn websocket-handler
  [request]
  (swap! total-ws-requests inc)
  (let [{:keys [in out] :as request}
        (-> request
            (update-in [:headers "x-cid"] (fn [cid] (or cid (str (UUID/randomUUID)))))
            (assoc-in [:headers "x-kitchen-request-id"] (str (UUID/randomUUID))))]
    (printlog request "Received websocket request:" request)
    (swap! pending-ws-requests inc)
    (async/go
      (async/>! out "Connected to kitchen")
      (loop []
        (let [in-data (async/<! in)]
          (printlog request "Received data on websocket:" in-data)
          (if (or (str/blank? (str in-data)) (= "exit" in-data))
            (do
              (async/>! out "bye")
              (printlog request "Closing connection.")
              (async/close! out)
              (swap! pending-ws-requests dec))
            (do
              (cond
                (instance? ByteBuffer in-data)
                (let [response-bytes (byte-array (.remaining in-data))]
                  (.get in-data response-bytes)
                  (async/>! out response-bytes))

                (= "request-info" in-data)
                (async/>! out (-> request request-info-handler :body))

                (= "kitchen-state" in-data)
                (async/>! out (-> request state-handler :body))

                (and (string? in-data) (str/starts-with? in-data "chars-") (> (count in-data) (count "chars-")))
                (let [num-chars-str (subs in-data (count "chars-"))
                      num-chars-int (Integer/parseInt num-chars-str)
                      chars (map char (range 65 91))
                      string-data (->> (repeatedly #(rand-nth chars))
                                       (take num-chars-int)
                                       (reduce str))]
                  (async/>! out string-data))

                (and (string? in-data) (str/starts-with? in-data "bytes-") (> (count in-data) (count "bytes-")))
                (let [num-bytes-str (subs in-data (count "bytes-"))
                      num-bytes-int (Integer/parseInt num-bytes-str)
                      byte-data (byte-array (take num-bytes-int (cycle (range 103))))]
                  (async/>! out byte-data))

                :else
                (async/>! out in-data))
              (recur))))))))

(defn basic-auth-middleware [username password handler]
  (fn [{:keys [uri] :as req}]
    (cond
      (not (and username password)) (handler req)
      (= "/status" uri) (handler req)
      :else ((basic-authentication/wrap-basic-authentication
               handler
               (fn [u p]
                 (and (= username u)
                      (= password p))))
              req))))

(defn -main
  [& args]
  (log/info "command line arguments:" (vec args))
  (let [cli-options [["-h" "--help"]
                     ["-p" "--port PORT" "Port number"
                      :parse-fn #(Integer/parseInt %)
                      :validate [#(< 0 % 0x10000) "Must be between 0 and 65536"]]
                     [nil "--ssl" "Launch server in SSL mode"]
                     [nil "--start-up-sleep-ms MS" "Milliseconds to sleep before starting Jetty"
                      :parse-fn #(Integer/parseInt %)
                      :default 0]]
        {:keys [options summary]} (cli/parse-opts args cli-options)
        {:keys [help port ssl start-up-sleep-ms]} (cond-> options
                                                          (-> options :port nil?) (assoc :port (if (:ssl options) 8443 8080)))
        username (System/getenv "WAITER_USERNAME")
        password (System/getenv "WAITER_PASSWORD")
        keystore (System/getenv "KEYSTORE")
        keystore-password (System/getenv "KEYSTORE_PASSWORD")
        keystore-type (System/getenv "KEYSTORE_TYPE")]
    (try
      (if help
        (println summary)
        (do
          (when ssl
            (when (str/blank? keystore)
              (throw (IllegalStateException. "Keystore (in environment variable KEYSTORE) is required when SSL is enabled")))
            (when (str/blank? keystore-password)
              (throw (IllegalStateException. "Keystore password (in environment variable KEYSTORE_PASSWORD) is required when SSL is enabled")))
            (when (str/blank? keystore-type)
              (throw (IllegalStateException. "Keystore type (in environment variable KEYSTORE_TYPE) is required when SSL is enabled")))
            (log/info "SSL config:" {:keystore keystore, :keystore-password "***", :keystore-type keystore-type}))
          (log/info "kitchen running on port" port (str (when ssl "in SSL mode")))
          (when (pos? start-up-sleep-ms)
            (log/info "sleeping for" start-up-sleep-ms "ms before starting server")
            (Thread/sleep start-up-sleep-ms))
          (let [partial-server-options {:ring-handler (basic-auth-middleware username password (params/wrap-params http-handler))
                                        :request-header-size 32768
                                        :websocket-handler websocket-handler}
                server-options (if ssl
                                 (assoc partial-server-options
                                   :key-password keystore-password
                                   :keystore keystore
                                   :keystore-type keystore-type
                                   :ssl? true
                                   :ssl-port port
                                   ;; duplicate key store values for the trust store
                                   :trust-password keystore-password
                                   :truststore keystore
                                   :truststore-type keystore-type)
                                 (assoc partial-server-options
                                   :port port))]
            (server/run-jetty server-options))))
      (shutdown-agents)
      (catch Exception e
        (log/fatal e "Encountered error starting kitchen with" options)
        (System/exit 1)))))
