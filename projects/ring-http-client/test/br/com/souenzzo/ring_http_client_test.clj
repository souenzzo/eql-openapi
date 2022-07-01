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
            [clojure.core.async :as async]
            [clojure.string :as string])
  (:import (java.lang AutoCloseable)
           (org.eclipse.jetty.websocket.api Session WebSocketConnectionListener WebSocketListener RemoteEndpoint)
           (java.net.http HttpClient WebSocket$Listener WebSocket)
           (java.net URI)
           (org.eclipse.jetty.websocket.servlet ServletUpgradeResponse ServletUpgradeRequest)))

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

(defn servlet-upgrade-request->ring-request
  [^ServletUpgradeRequest request]
  (let [headers (.getHeaders request)
        path (.getRequestPath request)
        uri (.getRequestURI request)
        port (.getPort uri)]
    (merge {}
      (when-let [method (.getMethod request)]
        {:request-method (keyword (string/lower-case method))})
      (when (seq headers)
        {:headers (into {}
                    (map (fn [[k vs]]
                           [(string/lower-case k)
                            (string/join "," vs)]))
                    headers)})
      (when (seq path)
        {:uri path})
      (when-let [host (.getHost uri)]
        {:server-name host})
      (when-let [scheme (.getScheme uri)]
        {:scheme (keyword scheme)})
      (when-let [query-string (.getQuery uri)]
        {:query-string query-string})
      (when-let [protocol (.getHttpVersion request)]
        {:protocol (str protocol)})
      (when (pos? port)
        {:server-port port}))))



(deftest jetty-ws
  (let [*ws-clients (atom {})
        listener-fn (fn [^ServletUpgradeRequest req ^ServletUpgradeResponse res ws-map]
                      (log/info :headers (into {}
                                           (map (fn [[k vs]]
                                                  [k (vec vs)]))
                                           (.getHeaders req)))
                      (reify
                        WebSocketConnectionListener
                        (onWebSocketConnect [this ws-session]
                          (log/info :in "onWebSocketConnect"
                            :ws ws-session)
                          (let [send-ch (async/chan)
                                remote ^RemoteEndpoint (.getRemote ws-session)]
                            ;; Let's process sends...
                            (async/thread
                              (loop []
                                (log/info :waiting "again")
                                (if-let [out-msg (and (.isOpen ws-session)
                                                   (async/<!! send-ch))]
                                  (do
                                    (try
                                      (ws/ws-send out-msg remote)
                                      (catch Exception ex
                                        (log/error :msg "Failed on ws-send"
                                          :exception ex)))
                                    (recur))
                                  (.close ws-session))))
                            (swap! *ws-clients assoc ws-session send-ch)
                            (async/>!! send-ch "This will be a text message")))
                        (onWebSocketClose [this status-code reason]
                          (log/info :in "onWebSocketClose"
                            :code status-code
                            :reason reason))
                        (onWebSocketError [this cause]
                          (log/error :in "onWebSocketError"
                            :exception cause))
                        WebSocketListener
                        (onWebSocketText [this msg]
                          (log/info :in "onWebSocketText"
                            :msg msg))
                        (onWebSocketBinary [this payload offset length]
                          (log/info :in "onWebSocketBinary"
                            :payload (seq payload)))))
        service (-> {::http/type              :jetty
                     ::http/port              8080
                     ::http/routes            #{}
                     ::http/container-options {:context-configurator #(ws/add-ws-endpoints % {"/ws" {}}
                                                                        {:listener-fn listener-fn})}

                     ::http/join?             false}
                  http/default-interceptors)]
    (with-open [server (->server service)]
      (let [^WebSocket ws (-> (HttpClient/newHttpClient)
                            .newWebSocketBuilder
                            (.buildAsync (URI/create "ws://localhost:8080/ws")
                              (reify WebSocket$Listener
                                (onOpen [this ws]
                                  (log/info :onOpen "??")
                                  (.request ws 1e3))
                                (onText [this ws data last?]
                                  (log/info :onText data :last? last)
                                  #_(.request ^WebSocket ws 1))
                                (onBinary [this ws data last?]
                                  (log/info :onBinary data :last? last))
                                (onClose [this ws status message]
                                  (log/info :onClose message))
                                (onError [this ws ex]
                                  (log/error :onError "??"
                                    :exception ex))
                                (onPing [this ws msg]
                                  (log/info :onPing "??"))
                                (onPong [this ws msg]
                                  (log/info :onPong "??"))))
                            deref)]
        (try
          (.sendText ws "hello" true)
          (doseq [[^Session session channel] @*ws-clients
                  :let [open? (.isOpen session)]]
            (when open?
              (async/>!! channel "world")))
          (Thread/sleep 1000)
          (finally
            (.sendClose ws 0 "bye")))))))



