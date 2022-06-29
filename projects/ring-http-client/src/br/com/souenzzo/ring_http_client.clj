(ns br.com.souenzzo.ring-http-client
  (:refer-clojure :exclude [send]))

(defprotocol RingHttpClient
  :extend-via-metadata true
  (send [this ring-request]))
