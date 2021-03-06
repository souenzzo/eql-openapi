(ns br.com.souenzzo.ring-http-client.pedestal
  (:require [br.com.souenzzo.ring-http-client :as rhc]
            [io.pedestal.test :refer [response-for]]
            [io.pedestal.http :as http]
            [clojure.string :as string])
  (:import (java.net URI)))

(defn service-fn->http-client
  [service-fn]
  (reify rhc/RingHttpClient
    (send [this {:keys [body headers query-string remote-addr request-method scheme server-name server-port uri]
                 :or   {server-port -1}}]
      (-> (apply response-for service-fn
            request-method
            (str (URI. (some-> scheme name)
                   nil
                   (or server-name remote-addr)
                   server-port
                   uri
                   query-string
                   nil))
            (concat
              (some-> body slurp (conj [:body]))
              (some-> headers (conj [:headers]))))
        (update :headers (fn [headers]
                           (into {}
                             (map (fn [[k v]]
                                    [(string/lower-case k) v]))
                             headers)))))))


(defn create-ring-http-client
  [service-map]
  (let [{::http/keys [service-fn]
         :as         service-map} (-> service-map
                                    http/create-servlet)
        http-client (service-fn->http-client service-fn)]
    (with-meta (assoc service-map
                 ::http-client http-client)
      `{rhc/send ~(fn [this request]
                    (rhc/send http-client request))})))
