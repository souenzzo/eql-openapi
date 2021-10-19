(ns br.com.souenzzo.eql-openapi-test
  (:require [br.com.souenzzo.eql-openapi :as eql-openapi]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [edn-query-language.core :as eql])
  (:import (java.net URI)))

(def v3-petstore
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "petstore.json")
    io/reader
    json/read))

(def v2-petstore
  (-> (io/file "OpenAPI-Specification" "examples" "v2.0" "json" "petstore.json")
    io/reader
    json/read))

(def v3-petstore-expanded
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "petstore-expanded.json")
    io/reader
    json/read))


(def v3-uspto
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "uspto.json")
    io/reader
    json/read))


(def v3-link-example
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "link-example.json")
    io/reader
    json/read))


(def conduit-example
  (-> (io/file "realworld" "api" "swagger.json")
    io/reader
    json/read))


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

(deftest conduit
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :UpdateArticleRequest))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:article [:title :description :body]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :NewCommentRequest))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:comment [:body]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :NewArticle))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:title :description :body :tagList])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :UpdateUserRequest))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:user [:email :token :username :bio :image]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :MultipleCommentsResponse))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:comments
             [:id :createdAt :updatedAt :body
              {:author [:username :bio :image :following]}]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :UserResponse))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:user [:email :token :username :bio :image]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :GenericErrorModel))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:errors [:body]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :SingleCommentResponse))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:comment
             [:id :createdAt :updatedAt :body
              {:author [:username :bio :image :following]}]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :Comment))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:id :createdAt :updatedAt :body
            {:author [:username :bio :image :following]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :MultipleArticlesResponse))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:articles [:updatedAt :body :createdAt
                        {:author [:username :bio :image :following]}
                        :favorited :slug :tagList :favoritesCount :title
                        :description]}
            :articlesCount])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :NewUser))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:username :email :password])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :SingleArticleResponse))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:article [:updatedAt :body :createdAt
                       {:author [:username :bio :image :following]}
                       :favorited :slug :tagList :favoritesCount :title
                       :description]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :NewArticleRequest))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:article [:title :description :body :tagList]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :TagsResponse))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:tags])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :ProfileResponse))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:profile [:username :bio :image :following]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :UpdateArticle))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:title :description :body])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :User))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:email :token :username :bio :image])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :LoginUser))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:email :password])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :NewComment))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:body])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :Article))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:updatedAt :body :createdAt
            {:author [:username :bio :image :following]}
            :favorited :slug :tagList :favoritesCount :title
            :description])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :Profile))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:username :bio :image :following])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :LoginUserRequest))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:user [:email :password]}])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :UpdateUser))
        eql/ast->query
        #_(doto pp/pprint)
        (= [:email :token :username :bio :image])))
  (is (-> (eql-openapi/schema-or-reference->ast conduit-example ($ref :definitions :NewUserRequest))
        eql/ast->query
        #_(doto pp/pprint)
        (= [{:user [:username :email :password]}]))))

(defspec escape->unescape-identity-prop
  1e3
  (prop/for-all [s gen/string]
    (= s (eql-openapi/unescape (eql-openapi/escape s)))))
