(ns br.com.souenzzo.ring-openapi-test
  (:require [br.com.souenzzo.ring-openapi :as ro]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest]]
            [midje.sweet :refer [=> fact]])
  (:import (org.snakeyaml.engine.v2.api Load LoadSettings)))

(def v3-petstore
  (-> (io/file ".." ".." "OpenAPI-Specification" "examples" "v3.0" "petstore.json")
    io/reader
    json/read))


(def v3-petstore-expanded
  (-> (io/file ".." ".." "OpenAPI-Specification" "examples" "v3.0" "petstore-expanded.json")
    io/reader
    json/read))

(def *conduit
  (delay
    (let [url "https://raw.githubusercontent.com/gothinkster/realworld/main/api/openapi.yml"]
      (-> (Load. (.build (LoadSettings/builder)))
        (.loadFromReader (io/reader url))))))

(deftest v3-petstore-test
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore
                          ::ro/operation-id "listPets"})
    => {:request-method :get
        :uri            "/pets"})
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore
                          ::ro/operation-id "listPets"
                          ::ro/query-params {"limit" 42}})
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
                          ::ro/path-params  {"petId" "42"}})
    => {:request-method :get
        :uri            "/pets/42"}))


(deftest v3-petstore-expanded-test
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore-expanded
                          ::ro/operation-id "findPets"})
    => {:request-method :get
        :uri            "/pets"})
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore-expanded
                          ::ro/operation-id "findPets"
                          ::ro/query-params {"limit" 42
                                             "tags" ["a" "b"]}})
    => {:request-method :get
        :query-string   "tags=a,b&limit=42"
        :uri            "/pets"})
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore-expanded
                          ::ro/operation-id "addPet"})
    => {:request-method :post
        :uri            "/pets"})
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore-expanded
                          ::ro/operation-id "find pet by id"
                          ::ro/path-params  {"id" "42"}})
    => {:request-method :get
        :uri            "/pets/42"})
  (fact
    (ro/ring-request-for {::ro/openapi      v3-petstore-expanded
                          ::ro/operation-id "deletePet"
                          ::ro/path-params  {"id" "42"}})
    => {:request-method :delete
        :uri            "/pets/42"}))

(deftest conduit-test
  (fact
    (ro/ring-request-for {::ro/openapi      @*conduit
                          ::ro/operation-id "GetArticles"})
    => {:request-method :get :uri "/articles"})
  (fact
    (ro/ring-request-for {::ro/openapi      @*conduit
                          ::ro/operation-id "GetArticlesFeed"})
    => {:request-method :get :uri "/articles/feed"})
  (fact
    (ro/ring-request-for {::ro/openapi      @*conduit
                          ::ro/operation-id "GetCurrentUser"})
    => {:request-method :get :uri "/user"}))
