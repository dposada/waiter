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
(ns waiter.interstitial
  (:require [clj-time.core :as t]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [comb.template :as template]
            [metrics.counters :as counters]
            [metrics.meters :as meters]
            [plumbing.core :as pc]
            [waiter.metrics :as metrics]
            [waiter.util.async-utils :as au]
            [waiter.util.utils :as utils]))

(defn- service-id->interstitial-promise
  "Returns the promise mapped against a service-id.
   It will return nil if the service is not currently mapped to a promise."
  [interstitial-state service-id]
  (get-in interstitial-state [:service-id->interstitial-promise service-id]))

(defn- resolve-promise!
  "Resolves a promise to the specified value.
   It must be called inside a critical section to avoid data races."
  [service-id interstitial-promise interstitial-resolution]
  (when-not (realized? interstitial-promise)
    (deliver interstitial-promise interstitial-resolution)
    (when (= interstitial-resolution @interstitial-promise)
      (log/info "interstitial for" service-id "resolved to" (name interstitial-resolution))
      (counters/inc! (metrics/waiter-counter "interstitial" "promise" "resolved"))
      (counters/inc! (metrics/waiter-counter "interstitial" "resolution" (name interstitial-resolution))))))

(defn install-interstitial-timeout!
  "Resolves the interstitial-promise after a delay on interstitial-secs seconds."
  [service-id interstitial-secs interstitial-promise]
  (if (pos? interstitial-secs)
    (async/go
      (-> interstitial-secs t/seconds t/in-millis async/timeout async/<!)
      (resolve-promise! service-id interstitial-promise :interstitial-timeout))
    (log/error service-id "has opted out of interstitial, not installing timeout")))

(defn ensure-service-interstitial!
  "Ensures a promise is mapped against the provided service id and returns the promise.
   If this call is responsible for creating the promise that got mapped, it also invokes process-interstitial-promise."
  [interstitial-state-atom service-id interstitial-secs]
  (or (service-id->interstitial-promise @interstitial-state-atom service-id)
      (loop [iteration 0
             new-interstitial-promise (promise)]
        (->> (fn [{:keys [service-id->interstitial-promise] :as interstitial-state}]
               (cond-> interstitial-state
                       (not (contains? service-id->interstitial-promise service-id))
                       (assoc-in [:service-id->interstitial-promise service-id] new-interstitial-promise)))
             (swap! interstitial-state-atom))
        (if-let [interstitial-promise (service-id->interstitial-promise @interstitial-state-atom service-id)]
          (do
            (when (identical? new-interstitial-promise interstitial-promise)
              (log/info "created interstitial promise for" service-id)
              (counters/inc! (metrics/waiter-counter "interstitial" "promise" "total"))
              (install-interstitial-timeout! service-id interstitial-secs interstitial-promise))
            interstitial-promise)
          (do
            (log/info "looping as interstitial promise was removed" {:iteration iteration :service-id service-id})
            (recur (inc iteration) (promise)))))))

(defn remove-resolved-interstitial-promises!
  "Removes all resolved promises mapped against services listed in service-ids.
   It returns the services from service-ids which no longer have promises mapped against them in the interstitial state."
  [interstitial-state-atom service-ids]
  (->> (fn [{:keys [service-id->interstitial-promise] :as interstitial-state}]
         (->> service-ids
              (filter #(some-> % service-id->interstitial-promise realized?))
              (apply dissoc service-id->interstitial-promise)
              (assoc interstitial-state :service-id->interstitial-promise)))
       (swap! interstitial-state-atom))
  (->> @interstitial-state-atom
       :service-id->interstitial-promise
       keys
       set
       (set/difference service-ids)))

(defn- process-scheduler-messages
  "Processes messages from the scheduler.
   In particular, it resolves all promises for services which have healthy instances.
   It also removes from the interstitial state any resolved promises for services which are no longer available."
  [interstitial-state-atom service-id->service-description current-available-service-ids scheduler-messages]
  (let [available-service-ids (->> scheduler-messages
                                   (some (fn [[message-type message-data]]
                                           (when (= :update-available-apps message-type)
                                             (:available-apps message-data))))
                                   set)
        healthy-instance-service-ids (->> scheduler-messages
                                          (keep (fn [[message-type message-data]]
                                                  (when (and (= :update-app-instances message-type)
                                                             (seq (:healthy-instances message-data)))
                                                    (:service-id message-data)))))
        service-ids-to-remove (set/difference current-available-service-ids available-service-ids)
        removed-service-ids (remove-resolved-interstitial-promises! interstitial-state-atom service-ids-to-remove)]
    (doseq [service-id available-service-ids]
      (let [{:strs [interstitial-secs]} (service-id->service-description service-id)]
        (when (pos? interstitial-secs)
          (ensure-service-interstitial! interstitial-state-atom service-id interstitial-secs))))
    (doseq [service-id healthy-instance-service-ids]
      (when-let [interstitial-promise (service-id->interstitial-promise @interstitial-state-atom service-id)]
        (resolve-promise! service-id interstitial-promise :healthy-instance-found)))
    (-> current-available-service-ids
        (set/difference removed-service-ids)
        (set/union available-service-ids)
        (set/union healthy-instance-service-ids))))

(defn interstitial-maintainer
  "Long running daemon process that listens for scheduler state updates and triggers changes in the
   interstitial state. It also responds to queries for the interstitial state."
  [service-id->service-description scheduler-state-chan interstitial-state-atom initial-state]
  (let [exit-chan (au/latest-chan)
        query-chan (async/chan 32)
        channels [exit-chan scheduler-state-chan query-chan]]
    (async/go
      (loop [{:keys [available-service-ids] :or {available-service-ids #{}} :as current-state} initial-state]
        (metrics/reset-counter
          (metrics/waiter-counter "interstitial" "available-services")
          (count available-service-ids))
        (let [next-state
              (try
                (let [[message selected-chan] (async/alts! channels :priority true)]
                  (condp = selected-chan
                    exit-chan
                    (if (= :exit message)
                      (log/warn "stopping interstitial-maintainer")
                      (do
                        (log/info "received unknown message, not stopping interstitial-maintainer" {:message message})
                        current-state))

                    query-chan
                    (let [{:keys [response-chan service-id]} message]
                      (->> (if service-id
                             {:available (contains? available-service-ids service-id)
                              :interstitial (some-> (service-id->interstitial-promise @interstitial-state-atom service-id)
                                                    (deref 0 :not-realized))}
                             {:interstitial (update @interstitial-state-atom :service-id->interstitial-promise
                                                    (fn [service-id->interstitial-promise]
                                                      (pc/map-vals #(deref % 0 :not-realized) service-id->interstitial-promise)))
                              :maintainer current-state})
                           (async/>! response-chan))
                      current-state)

                    scheduler-state-chan
                    (let [scheduler-messages message
                          available-service-ids' (process-scheduler-messages
                                                   interstitial-state-atom service-id->service-description
                                                   available-service-ids scheduler-messages)]
                      (when-not (:initialized? @interstitial-state-atom)
                        (log/info "interstitial state has been initialized with services from the scheduler")
                        (swap! interstitial-state-atom assoc :initialized? true))
                      (assoc current-state :available-service-ids available-service-ids'))))
                (catch Exception e
                  (log/error e "error in interstitial-maintainer")
                  current-state))]
          (when next-state
            (recur next-state)))))
    {:exit-chan exit-chan
     :query-chan query-chan}))

(let [interstitial-template-fn (template/fn
                                 [{:keys [service-description service-id target-url]}]
                                 (slurp (io/resource "web/interstitial.html")))]
  (defn render-interstitial-template
    "Renders the interstitial html page."
    [context]
    (interstitial-template-fn context)))

(def ^:const bypass-interstitial-param-name-value "x-waiter-bypass-interstitial=1")

(defn- strip-interstitial-param
  "Removes the interstitial param at the end from the query string.
   It assumes the query string already ends with the interstitial param."
  [query-string]
  (->> (count bypass-interstitial-param-name-value)
       inc
       (- (count query-string))
       (max 0)
       (subs query-string 0)))

(defn wrap-interstitial
  "Redirects users to the interstitial page when interstitial has been enabled and the service has not been started.
   The interstitial page will retry the request as a GET with the same query parameters but bypass the interstitial page."
  [handler interstitial-state-atom]
  (fn wrap-interstitial-handler [{:keys [descriptor headers query-string uri] :as request}]
    (let [{:keys [on-the-fly? service-description service-id]} descriptor
          {:strs [interstitial-secs]} service-description
          ;; the bypass interstitial should be the last query parameter
          bypass-interstitial? (str/ends-with? (str query-string) bypass-interstitial-param-name-value)]
      (if (or bypass-interstitial? ;; bypass query parameter provided
              on-the-fly? ;; on-the-fly request
              (zero? interstitial-secs) ;; interstitial support has been disabled
              (not (str/includes? (str (get headers "accept")) "text/html")) ;; expecting html response
              (not (:initialized? @interstitial-state-atom))
              (realized? (ensure-service-interstitial! interstitial-state-atom service-id interstitial-secs)))
        ;; continue processing down the handler chain
        (->> (cond-> query-string
                     bypass-interstitial? (strip-interstitial-param))
             (assoc request :query-string)
             handler)
        ;; redirect to interstitial page
        (let [location (str "/waiter-interstitial" uri (when-not (str/blank? query-string) (str "?" query-string)))]
          (counters/inc! (metrics/service-counter service-id "request-counts" "interstitial"))
          (meters/mark! (metrics/waiter-meter "interstitial" "redirect"))
          {:headers {"location" location
                     "x-waiter-interstitial" "true"}
           :status 303})))))

(defn display-interstitial-handler
  [{:keys [descriptor query-string route-params]}]
  (let [{:keys [service-description service-id]} descriptor
        {:keys [path]} route-params
        target-url (str "/" path "?"
                        (when (not (str/blank? query-string))
                          (str query-string "&"))
                        ;; the bypass interstitial should be the last query parameter
                        bypass-interstitial-param-name-value)]
    {:body (render-interstitial-template
             {:service-description (update service-description "cmd" utils/truncate 100)
              :service-id service-id
              :target-url target-url})
     :status 200}))
