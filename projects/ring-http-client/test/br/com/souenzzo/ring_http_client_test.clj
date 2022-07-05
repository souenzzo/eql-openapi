(ns br.com.souenzzo.ring-http-client-test
  (:require [br.com.souenzzo.ring-http-client :as rhc]
            [br.com.souenzzo.ring-http-client.java-net-http :as rhc.jnh]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest]]
            [io.pedestal.http :as http]
            [io.pedestal.log :as log]
            [midje.sweet :refer [=> fact]]
            [ring.core.protocols :as rcp])
  (:import (java.lang AutoCloseable)
           (java.net URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (org.eclipse.jetty.servlet ServletContextHandler ServletHolder)
           (org.eclipse.jetty.websocket.api Session UpgradeRequest WebSocketConnectionListener WebSocketListener)
           (org.eclipse.jetty.websocket.servlet WebSocketCreator WebSocketServlet WebSocketServletFactory)))

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

(defn upgrade-request->ring-request
  [^UpgradeRequest request]
  (let [headers (.getHeaders request)
        uri (.getRequestURI request)
        path (.getPath uri)
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

(defn ^URI ring->uri
  [{:keys [uri server-port server-name scheme query-string]}]
  (URI. (some-> scheme name)
    nil
    server-name
    server-port
    uri
    query-string
    nil))

(defn with-ws-endpoint
  [^ServletContextHandler ctx path ^WebSocketCreator creator]
  (.addServlet ctx (ServletHolder.
                     (proxy [WebSocketServlet] []
                       (configure [^WebSocketServletFactory factory]
                         (.setCreator factory creator))))
    (str path))
  ctx)


(deftest jetty-ws
  (let [*ws-clients (atom #{})
        creator (reify WebSocketCreator
                  (createWebSocket [this req resp]
                    (let [ring-request (upgrade-request->ring-request req)
                          *conn (promise)]
                      (reify WebSocketConnectionListener
                        (onWebSocketConnect [this ws-session]
                          (log/info :in "onWebSocketConnect"
                            :ws ws-session)
                          (deliver *conn ws-session)
                          (swap! *ws-clients conj ws-session)
                          @(.sendStringByFuture (.getRemote ^Session @*conn)
                             "Hello"))
                        (onWebSocketClose [this status-code reason]
                          (log/info :in "onWebSocketClose"
                            :code status-code
                            :reason reason)
                          (swap! *ws-clients disj @*conn))
                        (onWebSocketError [this cause]
                          (log/error :in "onWebSocketError"
                            :exception cause))
                        WebSocketListener
                        (onWebSocketText [this msg]
                          (log/info :in "onWebSocketText"
                            :msg msg)
                          @(.sendStringByFuture (.getRemote ^Session @*conn)
                             (str "echo - " ring-request)))
                        (onWebSocketBinary [this payload offset length]
                          (log/info :in "onWebSocketBinary"
                            :payload (seq payload)))))))
        service (-> {::http/type              :jetty
                     ::http/port              8080
                     ::http/routes            #{}
                     ::http/container-options {:context-configurator (fn [ctx] (with-ws-endpoint ctx "/ws" creator))}
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
                                  (log/info :onText (str data)
                                    :last? last?))
                                (onBinary [this ws data last?]
                                  (log/info :onBinary data :last? last))
                                (onClose [this ws status message]
                                  (log/info :onClose message
                                    :status status))
                                (onError [this ws ex]
                                  (log/error :onError "??"
                                    :exception ex))
                                (onPing [this ws msg]
                                  (log/info :onPing "??"))
                                (onPong [this ws msg]
                                  (log/info :onPong "??"))))
                            deref)]
        (try
          @(.sendText ws "cleiton" true)
          (doseq [^Session session @*ws-clients
                  :when (.isOpen session)]
            @(.sendStringByFuture (.getRemote session)
               "wowo"))
          (Thread/sleep 1000)
          (finally
            (.sendClose ws 0 "bye")))))))
