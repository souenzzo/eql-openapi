(ns br.com.souenzzo.ring-http-client-test
  (:require [br.com.souenzzo.ring-http-client :as rhc]
            [br.com.souenzzo.ring-http-client.java-net-http :as rhc.jnh]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest]]
            [io.pedestal.http :as http]
            [io.pedestal.http.jetty.websockets :as ws]
            [midje.sweet :refer [=> fact]]
            [ring.core.protocols :as rcp]
            [io.pedestal.log :as log]
            [clojure.core.async :as async])
  (:import (java.lang AutoCloseable)
           (org.eclipse.jetty.websocket.api Session WebSocketConnectionListener WebSocketListener)
           (java.net.http HttpClient WebSocket$Listener WebSocket)
           (java.net URI)))

(set! *warn-on-reflection* true)

(deftest client
  (let [*req (promise)
        client (rhc.jnh/ring-handler->http-client
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

(defn ^AutoCloseable ->server
  [service-map]
  (let [server (-> service-map
                 http/create-server
                 http/start)]
    (reify AutoCloseable
      (close [this]
        (http/stop server)))))


(deftest jetty-ws
  (let [routes `#{["/foo" :get ~(fn [_]
                                  {:body   "ok"
                                   :status 200})
                   :route-name :foo]}
        *ws-clients (atom {})
        listener-fn (fn [req res _]
                      (let [start! (ws/start-ws-connection
                                     (fn [^Session ws-session send-ch]
                                       (async/put! send-ch "This will be a text message")
                                       (swap! *ws-clients assoc ws-session send-ch)))]
                        (reify
                          WebSocketConnectionListener
                          (onWebSocketConnect [this ws-session]
                            (start! ws-session))
                          (onWebSocketClose [this status-code reason]
                            (log/info :msg "WS Closed:" :reason reason))
                          (onWebSocketError [this cause]
                            (log/error :msg "WS Error happened" :exception cause))

                          WebSocketListener
                          (onWebSocketText [this msg]
                            (log/info :msg (str "A client sent - " msg)))
                          (onWebSocketBinary [this payload offset length]
                            (log/info :msg "Binary Message!" :bytes payload)))))
        service (-> {::http/type              :jetty
                     ::http/port              8080
                     ::http/routes            routes
                     ::http/container-options {:context-configurator #(ws/add-ws-endpoints % {"/ws" {}}
                                                                        {:listener-fn (constantly listener-fn)})}

                     ::http/join?             false}
                  http/default-interceptors)]
    (with-open [server (->server service)]
      (let [^WebSocket ws (-> (HttpClient/newHttpClient)
                            .newWebSocketBuilder
                            (.buildAsync (URI/create "ws://localhost:8080/ws")
                              (reify WebSocket$Listener
                                (onOpen [this ws])
                                (onText [this ws data last?]
                                  (prn last?))))
                            deref)]
        (.sendText ws "hello" true)
        (doseq [[^Session session channel] @*ws-clients]
          ;; The Pedestal Websocket API performs all defensive checks before sending,
          ;;  like `.isOpen`, but this example shows you can make calls directly on
          ;;  on the Session object if you need to
          (when (.isOpen session)
            (async/put! channel "world")))
        (Thread/sleep 100)
        (.sendClose ws 0 "bye")))))



