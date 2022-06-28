(ns br.com.souenzzo.ring-http-client-test
  (:require [br.com.souenzzo.ring-http-client :as rhc]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest]]
            [midje.sweet :refer [=> fact]]
            [ring.core.protocols :as rcp]))

(set! *warn-on-reflection* true)

(deftest client
  (let [*req (promise)
        client (rhc/ring-handler->http-client
                 (fn [req]
                   (deliver *req req)
                   {:body    (reify rcp/StreamableResponseBody
                               (write-body-to-stream [this response output-stream]
                                 (with-open [w (io/writer output-stream)]
                                   (json/write [{"id" 0 "name" "Bisteca" "tag" "beagle"}]
                                     w))))
                    :headers {"hello" "world"}
                    :status  200}))]
    (fact
      (-> client
        (rhc/send {:uri            "/pets"
                   :request-method :get
                   :scheme         :http})
        (update :body (fn [body]
                        (with-open [rdr (io/reader body)]
                          (json/read rdr)))))
      => {:body    [{"id" 0 "name" "Bisteca" "tag" "beagle"}]
          :headers {"hello" "world"}
          :status  200})
    (fact
      @*req
      => {:scheme         :http
          :request-method :get
          :uri            "/pets"})))
