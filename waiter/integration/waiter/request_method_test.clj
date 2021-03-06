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
(ns waiter.request-method-test
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [waiter.client-tools :refer :all]))

(deftest ^:parallel ^:integration-fast test-request-method
  (testing-using-waiter-url
    (let [service-name (rand-name "test-request-method")
          headers {:x-waiter-name service-name, :x-kitchen-echo "true"}
          canary-response (make-request-with-debug-info headers #(make-kitchen-request waiter-url %))
          lorem-ipsum "Lorem ipsum dolor sit amet, consectetur adipiscing elit."]
      (is (= lorem-ipsum (:body (make-kitchen-request waiter-url headers :http-method-fn http/post :body lorem-ipsum))))
      (is (= lorem-ipsum (:body (make-kitchen-request waiter-url headers :http-method-fn http/put :body lorem-ipsum))))
      (is (= lorem-ipsum (:body (make-kitchen-request waiter-url headers :http-method-fn http/get :body lorem-ipsum))))
      (delete-service waiter-url (:service-id canary-response)))))
