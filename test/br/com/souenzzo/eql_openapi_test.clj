(ns br.com.souenzzo.eql-openapi-test
  (:require [clojure.test :refer [deftest is]]
            [br.com.souenzzo.eql-openapi :as eql-openapi]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.pprint :as pp]
            [edn-query-language.core :as eql]
            [clojure.string :as string])
  (:import (java.net URI)))

(def v3-petstore
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "petstore.json")
    io/reader
    (json/read)))

(def v2-petstore
  (-> (io/file "OpenAPI-Specification" "examples" "v2.0" "json" "petstore.json")
    io/reader
    (json/read)))

(def v3-petstore-expanded
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "petstore-expanded.json")
    io/reader
    (json/read)))


(def v3-uspto
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "uspto.json")
    io/reader
    (json/read)))


(def v3-link-example
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "link-example.json")
    io/reader
    (json/read)))

(defn $ref
  [& ks]
  {"$ref" (str (URI. nil nil nil
                 -1 nil nil
                 (string/join "/" (cons "" (map eql-openapi/escape ks)))))})

(deftest uspto
  (is (-> (eql-openapi/schema-or-reference->ast v3-uspto {"$ref" "#/components/schemas/dataSetList"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:total
            {:apis [:apiKey
                    :apiVersionNumber
                    :apiUrl
                    :apiDocumentationUrl]}]))))

(deftest link-example
  (is (-> (eql-openapi/schema-or-reference->ast v3-link-example {"$ref" "#/components/schemas/user"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:username :uuid])))
  (is (-> (eql-openapi/schema-or-reference->ast v3-link-example {"$ref" "#/components/schemas/repository"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:slug {:owner [:username :uuid]}])))
  (is (-> (eql-openapi/schema-or-reference->ast v3-link-example {"$ref" "#/components/schemas/pullrequest"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:id
            :title
            {:repository [:slug {:owner [:username :uuid]}]}
            {:author [:username :uuid]}]))))

(deftest petstore
  (is (-> (eql-openapi/schema-or-reference->ast v3-petstore {"$ref" "#/components/schemas/Pet"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:id :name :tag])))
  (is (-> (eql-openapi/schema-or-reference->ast v3-petstore {"$ref" "#/components/schemas/Pets"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:id :name :tag])))
  (is (-> (eql-openapi/schema-or-reference->ast v3-petstore {"$ref" "#/components/schemas/Error"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:code :message])))
  (is (-> (eql-openapi/schema-or-reference->ast v3-petstore
            ($ref :paths "/pets" :get :responses 200 :content :application/json :schema))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:id :name :tag]))))


(deftest petstore-old
  (is (-> (eql-openapi/schema-or-reference->ast v2-petstore ($ref :definitions :Pet))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:id :name :tag]))))


(deftest petstore-expanded
  (is (-> (eql-openapi/schema-or-reference->ast v3-petstore-expanded {"$ref" "#/components/schemas/Pet"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:id :tag :name])))
  (is (-> (eql-openapi/schema-or-reference->ast v3-petstore-expanded {"$ref" "#/components/schemas/NewPet"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:name :tag])))
  (is (-> (eql-openapi/schema-or-reference->ast v3-petstore-expanded {"$ref" "#/components/schemas/Error"})
        eql/ast->query
        #_(doto pp/pprint)
        (= [:code :message])))
  #_(pp/pprint (eql-openapi/dereference v3-petstore-expanded {"$ref" "#/components/schemas/Pets"})))

