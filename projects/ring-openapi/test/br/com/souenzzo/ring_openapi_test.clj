(ns br.com.souenzzo.ring-openapi-test
  (:require [clojure.test :refer [deftest]]
            [br.com.souenzzo.ring-openapi :as ro]
            [midje.sweet :refer [fact =>]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def v3-petstore
  (-> (io/file ".." ".." "OpenAPI-Specification" "examples" "v3.0" "petstore.json")
    io/reader
    json/read))


(deftest ring-request-for-test
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore
                          ::ro/operation-id "listPets"})
    => {:request-method :get
        :uri            "/pets"})
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore
                          ::ro/operation-id "listPets"
                          ::ro/query-params {:limit 42}})
    => {:request-method :get
        :query-string   "limit=42"
        :uri            "/pets"})
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore
                          ::ro/operation-id "createPets"})
    => {:request-method :post
        :uri            "/pets"})
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore
                          ::ro/operation-id "showPetById"
                          ::ro/path-params  {:petId "42"}})
    => {:request-method :get
        :uri            "/pets/42"}))
