(ns br.com.souenzzo.eql-openapi
  (:require [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [edn-query-language.core :as eql])
  (:import (java.net URI)))

(set! *warn-on-reflection* true)

;; https://swagger.io/specification/#openapi-object
(s/def ::openapi map?)
;; https://swagger.io/specification/#schema-object
(s/def ::schema map?)
;; https://swagger.io/specification/#reference-object
(s/def ::reference map?)

;; from https://github.com/everit-org/json-schema/blob/master/core/src/main/java/org/everit/json/schema/JSONPointer.java#L142

(defn escape
  [s]
  (-> (cond
        (qualified-ident? s) (str (namespace s) "/" (name s))
        (ident? s) (name s)
        :else (str s))
    (string/replace "~" "~0")
    (string/replace "/" "~1")
    (string/replace "\\" "\\\\")
    (string/replace "\"" "\\\"")))

(defn unescape
  [s]
  (-> s
    (string/replace "~1" "/")
    (string/replace "~0" "~")
    (string/replace "\\\"" "\"")
    (string/replace "\\\\" "\\")))

(defn dereference
  [openapi schema-or-reference]
  (if (contains? schema-or-reference "$ref")
    (let [path (-> schema-or-reference
                 (get "$ref")
                 URI/create
                 .getFragment
                 (string/split #"/")
                 rest
                 (->> (map unescape)))]
      (dereference openapi (get-in openapi path)))
    schema-or-reference))

(defn schema-or-reference->ast
  [openapi schema-or-reference]
  (let [{:strs [type properties required items allOf]
         :as   schema} (dereference openapi schema-or-reference)]
    (case type
      "object" {:type     :root
                :children (mapv (fn [[k v]]
                                  (let [k (keyword k)
                                        {:keys [children]
                                         :as   node} (schema-or-reference->ast openapi v)]
                                    (assoc node
                                      :type (if children :join :props)
                                      :dispatch-key k
                                      :key k)))
                            properties)}
      "array" (schema-or-reference->ast openapi items)
      (reduce eql/merge-asts {:type :root}
        (map (partial schema-or-reference->ast openapi) allOf)))))

(s/fdef schema-or-reference->ast
  :args (s/cat :openapi ::openapi
          :schema (s/or :schema ::schema
                    :reference ::reference)))