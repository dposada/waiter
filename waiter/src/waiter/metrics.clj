;;
;;       Copyright (c) 2017 Two Sigma Investments, LLC.
;;       All Rights Reserved
;;
;;       THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF
;;       Two Sigma Investments, LLC.
;;
;;       The copyright notice above does not evidence any
;;       actual or intended publication of such source code.
;;
(ns waiter.metrics
  (:require [clj-time.core :as t]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [metrics.core :as mc]
            [metrics.counters :as counters]
            [metrics.histograms :as histograms]
            [metrics.meters :as meters]
            [metrics.timers :as timers]
            [plumbing.core :as pc]
            [waiter.async-utils :as au]
            [waiter.correlation-id :as cid]
            [waiter.utils :as utils])
  (:import (com.codahale.metrics Counter Histogram Meter MetricFilter MetricRegistry Timer Timer$Context)))

(defn compress-strings
  "Compress adjacent strings in vector with a delimeter.
  e.g.  (compress-strings [\"a\" \"b\" \"c\"] \".\") -> [\"a.b.c\"]
        (compress-strings [\"a\" 'b \"c\"] \".\") -> [\"a\" b \"c\"]"
  [elements delim]
  (loop [sofar []
         current-string nil
         [f & r] elements]
    (cond
      (and (string? f) current-string) (recur sofar (str current-string delim f) r)
      (and (string? f) (nil? current-string) (seq sofar)) (recur sofar (str delim f) r)
      (and (string? f) (nil? current-string) (not (seq sofar))) (recur sofar f r)
      (and f (not (string? f)) current-string) (recur (conj sofar (str current-string delim) f) nil r)
      (and f (not (string? f)) (nil? current-string) (seq sofar)) (recur (conj sofar delim f) nil r)
      (and f (not (string? f)) (nil? current-string) (not (seq sofar))) (recur (conj sofar f) nil r)
      (and (not f) current-string) (conj sofar current-string)
      (and (not f) (nil? current-string)) sofar)))

(defn join-by-concat
  [elements]
  (loop [expr (first elements)
         [f & r] (rest elements)]
    (if f
      (recur `(.concat ~expr ~f) r)
      expr)))

(defn metric-name
  "Returns code that will create a name string given a metric named defined by an
  array of strings and symbols.
  e.g. (metric-name [\"services\" service-id \"requests\" \"counter\"])
          -> (.concat (.concat (.concat \"services\" \".\") service-id) \"requests.counter\")"
  [elements]
  (-> elements
      (compress-strings ".")
      (join-by-concat)))

(defmacro service-counter
  "Creates a counter with service-specific naming scheme"
  [service-id & nested-path]
  `(counters/counter ~(metric-name (concat ["services" service-id "counters"] nested-path))))

(defmacro service-histogram
  "Creates a histogram with service-specific naming scheme"
  [service-id & nested-path]
  `(histograms/histogram ~(metric-name (concat ["services" service-id "histograms"] nested-path))))

(defmacro service-meter
  "Creates a meter with service-specific naming scheme"
  [service-id & nested-path]
  `(meters/meter ~(metric-name (concat ["services" service-id "meters"] nested-path))))

(defmacro service-timer
  "Creates a timer with service-specific naming scheme"
  [service-id & nested-path]
  `(timers/timer ~(metric-name (concat ["services" service-id "timers"] nested-path))))

(defmacro waiter-counter
  "Creates a counter with waiter-specific naming scheme"
  [classifier & nested-path]
  `(counters/counter ~(metric-name (concat ["waiter" classifier "counters"] nested-path))))

(defmacro waiter-meter
  "Creates a waiter-meter with waiter-specific naming scheme"
  [classifier & nested-path]
  `(meters/meter ~(metric-name (concat ["waiter" classifier "meters"] nested-path))))

(defmacro waiter-timer
  "Creates a timer with waiter-specific naming scheme"
  [classifier & nested-path]
  `(timers/timer ~(metric-name (concat ["waiter" classifier "timers"] nested-path))))

(defmacro with-meter
  [title-or-meter-start title-or-meter-end & body]
  `(let [meter-start# (if (instance? Meter ~title-or-meter-start)
                        ~title-or-meter-start
                        (throw (IllegalArgumentException. (str "Please pass a Meter instance: " ~title-or-meter-start))))
         meter-end# (if (instance? Meter ~title-or-meter-end)
                      ~title-or-meter-end
                      (throw (IllegalArgumentException. (str "Please pass a Meter instance: " ~title-or-meter-end))))]
     (try
       (meters/mark! meter-start#)
       (do
         ~@body)
       (finally
         (meters/mark! meter-end#)))))

(defmacro with-counter
  [title-or-counter & body]
  `(let [counter# (if (instance? Counter ~title-or-counter)
                    ~title-or-counter
                    (throw (IllegalArgumentException. (str "Please pass a Counter instance: " ~title-or-counter))))]
     (try
       (counters/inc! counter#)
       (do
         ~@body)
       (finally
         (counters/dec! counter#)))))

(defn get-metrics
  "Return a nested map of metrics data."
  ([] (get-metrics mc/default-registry MetricFilter/ALL))
  ([^MetricRegistry registry ^MetricFilter metric-filter]
   (utils/keys->nested-map
     (merge
       (pc/map-vals (fn [c] (counters/value c))
                    (.getCounters registry metric-filter))
       (pc/map-vals (fn [^Histogram h] {"count" (.getCount h)
                                        "value" (->> (histograms/percentiles h [0.0 0.25 0.5 0.75 0.95 0.99 0.999 1.0])
                                                     (pc/map-keys str))})
                    (.getHistograms registry metric-filter))
       (pc/map-vals (fn [^Meter m] {"count" (.getCount m)
                                    "value" (meters/rate-one m)})
                    (.getMeters registry metric-filter))
       (pc/map-vals (fn [^Timer t]
                      {"count" (.getCount t)
                       ; nanos -> seconds
                       "value" (->> (timers/percentiles t [0.0 0.25 0.5 0.75 0.95 0.99 0.999 1.0])
                                    (pc/map-vals #(/ % 1e9))
                                    (pc/map-keys str))})
                    (.getTimers registry metric-filter)))
     #"\.")))

(defn get-core-metrics
  "Retrieves the core set of metrics used by the routers to make decisions based on aggregate metrics.
   The returned data has the format service-id->core-metrics-map
   The core-metrics-map is a flat map that has the following string keys:
     slots-offered (via work-stealing), slots-assigned, slots-available, slots-in-use, outstanding and total.
   The numeric values in each entry of the map come from corresponding codahale metrics."
  []
  (let [services-string "services"
        metric-filter (reify MetricFilter
                        (matches [_ name _]
                          (and (str/starts-with? name services-string)
                               (or (and (str/includes? name "counters")
                                        (some #(str/includes? name %)
                                              ["slots-available" "slots-in-use" "in-flight" "outstanding" "total"]))
                                   (and (str/includes? name "timers") (str/includes? name "process"))))))
        service-id->metrics (-> (get-metrics mc/default-registry metric-filter)
                                (get services-string))]
    (pc/map-vals (fn [metrics]
                   (let [assoc-if (fn [transient-map metrics-keys map-key]
                                    (let [value (get-in metrics metrics-keys)]
                                      (cond-> transient-map
                                              value (assoc! map-key value))))
                         transient-map (transient {})]
                     (-> transient-map
                         (assoc-if ["counters" "instance-counts" "slots-available"] "slots-available")
                         (assoc-if ["counters" "instance-counts" "slots-in-use"] "slots-in-use")
                         (assoc-if ["counters" "request-counts" "outstanding"] "outstanding")
                         (assoc-if ["counters" "request-counts" "total"] "total")
                         (assoc-if ["counters" "work-stealing" "received-from" "in-flight"] "slots-offered")
                         (persistent!))))
                 service-id->metrics)))

(defn get-service-metrics
  "Retrieves the metrics for a sepcific service-id available at this router."
  [service-id]
  (let [prefix-string (str "services" "." service-id)
        metric-filter (reify MetricFilter
                        (matches [_ name _]
                          (str/starts-with? name prefix-string)))
        service-metrics (get-metrics mc/default-registry metric-filter)]
    service-metrics))

(defn get-waiter-metrics
  "Retrieves the waiter metrics at this router."
  []
  (let [metric-filter (reify MetricFilter
                        (matches [_ name _]
                          (str/starts-with? name "waiter")))
        waiter-metrics (get-metrics mc/default-registry metric-filter)]
    waiter-metrics))

(defn update-counter
  "Updates the counter to the size of the `new-coll`."
  [counter old-coll new-coll]
  (counters/inc! counter (- (count new-coll) (count old-coll))))

(defn reset-counter
  "Resets the value of the counter to the new value."
  [the-counter new-value]
  (counters/inc! the-counter (- (or new-value 0) (counters/value the-counter))))

(defn retrieve-local-stats-for-service
  "Returns a map containing local metrics for the specified service."
  [service-id]
  {:service-id service-id
   :total-requests (counters/value (service-counter service-id "request-counts" "total"))
   :outstanding-requests (counters/value (service-counter service-id "request-counts" "outstanding"))
   :router-instances (counters/value (service-counter service-id "instance-counts" "my-instances"))
   :in-use-instances (counters/value (service-counter service-id "instance-counts" "in-use"))
   :healthy-instances (counters/value (service-counter service-id "instance-counts" "healthy"))
   :unhealthy-instances (counters/value (service-counter service-id "instance-counts" "unhealthy"))
   :failed-instances (counters/value (service-counter service-id "instance-counts" "failed"))
   :blacklisted-instances (counters/value (service-counter service-id "instance-counts" "blacklisted"))})

(defn is-quantile-metric?
  "Returns true if the input map represents a quantile metric (timer or historgram).
   Warning: Not foolproof."
  [value]
  (and (map? value)
       (contains? value "count")
       (integer? (get value "count"))
       (contains? value "value")
       (let [value-entry (get value "value")]
         (and (map? value-entry)
              (contains? value-entry "0.25")
              (contains? value-entry "0.5")
              (contains? value-entry "0.75")))))

(defn- merge-quantile-metrics
  "Merges the quantile metrics (timers and historgrams) using a weighted sum reduction."
  [& values]
  (let [non-nil-values (remove nil? values)]
    (when-not (every? is-quantile-metric? non-nil-values)
      (throw (ex-info "All inputs are not quantile metrics!" {:input values})))
    (let [counts (map #(or (get % "count") 0) non-nil-values)
          total-count (reduce + counts)
          sanitized-total-count (max total-count 1) ;; avoid divide by zero errors
          values (map #(get % "value") non-nil-values)
          weighted-values (map (fn [count quantiles] (pc/map-vals #(* % (/ count sanitized-total-count)) quantiles))
                               counts values)]
      {"count" total-count
       "value" (apply merge-with + weighted-values)})))

(defn is-rate-metric?
  "Returns true if the input map represents a rate (meter) metric.
   Warning: Not foolproof."
  [value]
  (and (map? value)
       (= 2 (count value))
       (contains? value "count")
       (integer? (get value "count"))
       (contains? value "value")
       (number? (get value "value"))))

(defn- merge-rate-metrics
  "Merges the rate metric (meter) using a weighted-sum reduction."
  [& values]
  (let [non-nil-values (remove nil? values)]
    (when-not (every? is-rate-metric? non-nil-values)
      (throw (ex-info "All inputs are not rate metrics!" {:input values})))
    (let [counts (map #(or (get % "count") 0) non-nil-values)
          values (map #(get % "value") non-nil-values)]
      {"count" (reduce + counts)
       "value" (reduce + values)})))

(defn- merge-metrics
  "Merges all the metrics.
   Timers and histograms will be combined using `merge-quantile-metrics`.
   Meters will be combined using `merge-rate-metrics`.
   Counters are combined using sum reduction.
   Nested values are recursively merged using one of the above rules."
  [& maps]
  (when (some identity maps)
    (let [merge-entry (fn [accum-map [entry-key entry-val]]
                        (if (contains? accum-map entry-key)
                          (let [current-val (get accum-map entry-key)
                                reduced-val (cond
                                              ;; histograms and timers
                                              (is-quantile-metric? entry-val) (merge-quantile-metrics current-val entry-val)
                                              ;; meters
                                              (is-rate-metric? entry-val) (merge-rate-metrics current-val entry-val)
                                              ;; leaf-level counters
                                              (every? number? [current-val entry-val]) (reduce + [current-val entry-val])
                                              ;; recurse on nested values
                                              (every? map? [current-val entry-val]) (merge-metrics current-val entry-val)
                                              ;; base-case
                                              (every? nil? [current-val entry-val]) nil
                                              ;; error scenario
                                              :else (throw (ex-info "Unable to merge" {:accum current-val, :value entry-val})))]
                            (assoc accum-map entry-key reduced-val))
                          (assoc accum-map entry-key entry-val)))
          merge-metric-maps-fn (fn [m1 m2] (reduce merge-entry (or m1 {}) (seq m2)))]
      (reduce merge-metric-maps-fn maps))))

(defn aggregate-router-data
  "Aggregates the metrics from the different routers."
  [router->metrics]
  (assoc
    (apply merge-metrics
           (map #(-> (dissoc % "metrics-version")
                     (utils/dissoc-in ["counters" "instance-counts"]))
                (vals router->metrics)))
    :routers-sent-requests-to (count router->metrics)))

(let [metric-filter-fn (fn [service-id]
                         (reify MetricFilter
                           (matches [_ name _]
                             (and (str/includes? name service-id)
                                  (not (str/includes? name "outstanding"))))))]
  ;; We avoid deleting the outsanding requests metric as deleting it can lead to a data race for
  ;; the increment associated with an incoming request. With this count deleted (effectively means value is
  ;; zero), the autoscaler will not detect the outstanding request and not launch an instance to handle the request.
  (defn remove-metrics-except-outstanding
    "Removes all in-memory metrics for a given service except the one for outstanding requests."
    [^MetricRegistry registry service-id]
    (log/info "deleting transient metrics for" service-id)
    (.removeMatching registry (metric-filter-fn service-id))))

(defn transient-metrics-data-producer
  "Periodically (every `metrics-gc-interval-ms` ms) creates a service-id->metrics map from metrics
   resident in memory, writing them to a channel."
  [service-id->metrics-chan service-id->metrics-fn {:keys [metrics-gc-interval-ms]}]
  (let [exit-chan (async/chan 1)
        query-chan (async/chan 1)
        has-metrics-except-outstanding? #(-> % (dissoc "outstanding") (not-empty))]
    ;; launch go-block to populate service-id->metrics-chan
    (async/go-loop [iteration 0
                    timeout-chan (async/timeout metrics-gc-interval-ms)]
      (let [[args channel] (async/alts! [exit-chan timeout-chan query-chan] :priority true)]
        (condp = channel
          exit-chan
          (when (not= :exit args)
            (recur (inc iteration) timeout-chan))

          timeout-chan
          (do
            (cid/with-correlation-id
              (str "transient-service-metrics-data-producer-" iteration)
              (try
                (let [service-id->metrics (utils/filterm (fn [[_ metrics]] (has-metrics-except-outstanding? metrics))
                                                         (or (service-id->metrics-fn) {}))]
                  (log/info "writing transient metrics for" (count service-id->metrics) "services")
                  (async/>! service-id->metrics-chan service-id->metrics))
                (catch Exception e
                  (log/error e "unable to generate transient metrics"))))
            (recur (inc iteration) (async/timeout metrics-gc-interval-ms)))

          query-chan
          (let [{:keys [response-chan]} args]
            (async/>! response-chan {:iteration iteration})
            (recur iteration timeout-chan)))))
    {:exit-chan exit-chan
     :query-chan query-chan}))

(defn transient-metrics-gc
  "Launches go-blocks that keep running for the lifetime of the router watching for idle services known to the router.
   When such an idle service is found (based on the timestamp it was last updated), the metrics (except the one for
   outstanding requests) are deleted locally."
  [service-gc-go-routine {:keys [metrics-gc-interval-ms transient-metrics-timeout-ms]}]
  (let [sanitize-state-fn (fn sanitize-state [prev-service->state _] prev-service->state)
        service-id->state-fn (fn service->state [_ _ data]
                               (or (select-keys data ["outstanding" "total"]) {}))
        gc-service?-fn (fn [_ {:keys [state last-modified-time]} current-time]
                         (let [{:strs [outstanding]} state]
                           (and (or (nil? outstanding)
                                    (zero? outstanding))
                                (let [threshold (t/millis transient-metrics-timeout-ms)
                                      mod-threshold-time (t/plus last-modified-time threshold)]
                                  (t/after? current-time mod-threshold-time)))))
        perform-gc-fn (fn [service-id]
                        (remove-metrics-except-outstanding mc/default-registry service-id)
                        ; truthy value to represent successful delete
                        true)
        service-id->metrics-chan (au/latest-chan)]
    ;; launch go-block to perform transient metrics GC
    (merge (service-gc-go-routine
             "transient-metrics-gc"
             service-id->metrics-chan
             metrics-gc-interval-ms
             sanitize-state-fn
             service-id->state-fn
             gc-service?-fn
             perform-gc-fn)
           {:service-id->metrics-chan service-id->metrics-chan})))

(defmacro with-timer!
  "Times the operation specified by body using the provided
  timer and calls handle-elapsed-nanos-fn with the epased
  time in nanoseconds. Returns the return value of body."
  [timer handle-elapsed-nanos-fn & body]
  `(let [^Timer$Context start# (metrics.timers/start ~timer)
         out# (do ~@body)
         elapsed# (metrics.timers/stop start#)]
     (~handle-elapsed-nanos-fn elapsed#)
     out#))
