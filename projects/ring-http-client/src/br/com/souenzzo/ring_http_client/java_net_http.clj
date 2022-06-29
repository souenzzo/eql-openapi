(ns br.com.souenzzo.ring-http-client.java-net-http
  (:require [br.com.souenzzo.ring-http-client :as rhc]
            [clojure.string :as string]
            [ring.core.protocols :as rcp])
  (:import (java.io ByteArrayOutputStream)
           (java.net URI)
           (java.net.http HttpClient HttpClient$Version HttpHeaders HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandler HttpResponse$BodyHandlers HttpResponse$ResponseInfo HttpResponse$BodySubscriber)
           (java.nio ByteBuffer)
           (java.util Optional)
           (java.util.concurrent Flow$Subscription)
           (java.util.function BiPredicate)))


(defn ^HttpRequest ring-request->http-request
  [{:keys [body headers protocol query-string remote-addr request-method scheme server-name server-port uri]
    :or   {server-port -1}}]
  (proxy [HttpRequest] []
    (method [] (some-> request-method
                 name
                 string/upper-case))
    (uri []
      (URI. (some-> scheme name)
        nil
        (or server-name remote-addr)
        server-port
        uri
        query-string
        nil))
    (timeout [] (Optional/empty))
    (expectContinue [] false)
    (version [] (Optional/ofNullable
                  (get {"HTTP/1.1" HttpClient$Version/HTTP_1_1
                        "HTTP/2"   HttpClient$Version/HTTP_2}
                    protocol)))
    (bodyPublisher [] (Optional/ofNullable (some-> body
                                             HttpRequest$BodyPublishers/ofInputStream)))
    (headers [] (HttpHeaders/of (into {}
                                  (map (fn [[k v]]
                                         [k (if (coll? v)
                                              (mapv str v)
                                              [(str v)])]))
                                  headers)
                  (reify BiPredicate (test [this t u] true))))))

(defn http-request->ring-request
  [^HttpRequest http-request]
  (let [uri (.uri http-request)
        port (.getPort uri)
        path (.getPath uri)
        headers (into {}
                  (map (fn [[k vs]]
                         [k (string/join "," vs)]))
                  (.map (.headers http-request)))]
    (merge {}
      (when-let [method (.method http-request)]
        {:request-method (keyword (string/lower-case method))})
      (when (seq headers)
        {:headers headers})
      (when (seq path)
        {:uri path})
      (when-let [host (.getHost uri)]
        {:server-name host})
      (when-let [scheme (.getScheme uri)]
        {:scheme (keyword scheme)})
      (when-let [query-string (.getQuery uri)]
        {:query-string query-string})
      (when-let [protocol (get {HttpClient$Version/HTTP_1_1 "HTTP/1.1"
                                HttpClient$Version/HTTP_2   "HTTP/2"}
                            (.orElse (.version http-request) nil))]
        {:protocol (str protocol)})
      (when (pos? port)
        {:server-port port}))))


(defn http-response->ring-response
  [^HttpResponse http-response]
  {:status  (.statusCode http-response)
   :body    (.body http-response)
   :headers (into {}
              (map (fn [[k vs]]
                     [k (string/join "," vs)]))
              (.map (.headers http-response)))})

(defn ^HttpResponse ring-response->http-response
  [{:keys [status body headers]
    :as   ring-response} ^HttpResponse$BodyHandler body-handler]
  (let [baos (ByteArrayOutputStream.)
        response-headers (HttpHeaders/of (into {}
                                           (map (fn [[k v]]
                                                  [k [v]]))
                                           headers)
                           (reify BiPredicate (test [this t u] true)))
        ^HttpResponse$BodySubscriber bs (.apply body-handler
                                          (reify HttpResponse$ResponseInfo
                                            (statusCode [this] status)
                                            (headers [this] response-headers)))
        *body (delay
                (with-open [output-stream baos]
                  (rcp/write-body-to-stream body ring-response output-stream))
                (.onSubscribe bs (reify Flow$Subscription
                                   (request [this n]
                                     (.onNext bs [(ByteBuffer/wrap (.toByteArray baos))]))))
                (.onComplete bs)
                @(.toCompletableFuture (.getBody bs)))]
    (reify HttpResponse
      (statusCode [this] status)
      (headers [this] response-headers)
      (body [this] @*body))))

(defn ^HttpClient ring-handler->http-client
  [ring-handler]
  (proxy [HttpClient] []
    (send [^HttpRequest http-request ^HttpResponse$BodyHandler body-handler]
      (let [ring-request (http-request->ring-request http-request)
            ring-response (ring-handler ring-request)]
        (ring-response->http-response ring-response body-handler)))))


(extend-protocol rhc/RingHttpClient
  HttpClient
  (send [this ring-request]
    (-> this
      (.send (ring-request->http-request ring-request)
        (HttpResponse$BodyHandlers/ofInputStream))
      http-response->ring-response)))
