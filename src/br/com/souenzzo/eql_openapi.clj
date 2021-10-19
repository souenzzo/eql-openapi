(ns br.com.souenzzo.eql-openapi
  (:require [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [edn-query-language.core :as eql])
  (:import (java.net URI)))

(set! *warn-on-reflection* true)

(s/def ::json-pointer string?)

;; https://swagger.io/specification/#openapi-object
(s/def ::document map?)
;; https://swagger.io/specification/#schema-object
(s/def ::schema map?)
;; https://swagger.io/specification/#reference-object
(s/def ::reference (s/map-of #{"$ref"} ::json-pointer))

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
  [document object-or-reference]
  (if (s/valid? ::reference object-or-reference)
    (let [path (-> object-or-reference
                 (get "$ref")
                 URI/create
                 .getFragment
                 (string/split #"/")
                 rest
                 (->> (map unescape)))]
      (dereference document (get-in document path)))
    object-or-reference))

(defn swagger->ast
  [swagger schema-or-reference]
  (let [{:strs [properties type items allOf]
         :as   root-schema} (dereference swagger schema-or-reference)
        root-node {:type :root
                   :meta root-schema}]
    (cond (map? properties)
          (assoc root-node
            :children (mapv (fn [[property v]]
                              (let [k (keyword property)
                                    {:keys [children]
                                     :as   node} (swagger->ast swagger v)]
                                (assoc node
                                  :type (if children :join :props)
                                  :dispatch-key k
                                  :key k)))
                        properties))
          (= type "array")
          (swagger->ast swagger items)
          :else (reduce eql/merge-asts root-node
                  (map (partial swagger->ast swagger) allOf)))))

(defn openapi-v3->ast
  [openapi schema-or-reference]
  (let [{:strs [type properties items allOf]
         :as   root-schema} (dereference openapi schema-or-reference)
        root-node {:type :root
                   :meta root-schema}]
    (case type
      "object" (assoc root-node
                 :children (mapv (fn [[property schema]]
                                   (let [k (keyword property)
                                         {:keys [children]
                                          :as   node} (openapi-v3->ast openapi schema)]
                                     (assoc node
                                       :type (if children :join :props)
                                       :dispatch-key k
                                       :key k)))
                             properties))
      "array" (openapi-v3->ast openapi items)
      (reduce eql/merge-asts root-node
        (map (partial openapi-v3->ast openapi) allOf)))))

(defn schema-or-reference->ast
  [document schema-or-reference]
  (if (= "2.0" (get document "swagger"))
    (swagger->ast document schema-or-reference)
    (openapi-v3->ast document schema-or-reference)))

(s/fdef schema-or-reference->ast
  :args (s/cat :document ::document
          :schema (s/or :schema ::schema
                    :reference ::reference)))

(defn distinct-by
  ([keyfn coll]
   (sequence (distinct-by keyfn) coll))
  ([f]
   (fn [rf]
     (let [*seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [k (f input)]
            (if (contains? @*seen k)
              result
              (do (vswap! *seen conj k)
                  (rf result input))))))))))


(defn operations
  [document]
  (let [methods ["get" "put" "post" "delete" "options" "head" "patch" "trace"]]
    (for [[path path-item] (get document "paths")
          :let [path-item (dereference document path-item)]
          [method operation] (select-keys path-item methods)]
      (assoc operation
        ::operation-ref (string/join "/"
                          (cons "#" (map escape ["paths" path method])))
        ::path-item-ref (string/join "/"
                          (cons "#" (map escape ["paths" path])))
        ::path path
        ::parameters (into []
                       (comp cat
                         (map (partial dereference document))
                         (distinct-by (fn [{:strs [name location]}]
                                        [name location])))
                       [(get operation "parameters")
                        (get path-item "parameters")])
        ::method method))))
