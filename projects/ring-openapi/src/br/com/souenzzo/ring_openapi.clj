(ns br.com.souenzzo.ring-openapi
  (:require [clojure.string :as string])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(defn ring-request-for
  [{::keys [openapi operation-id path-params query-params]}]
  (let [{:strs [paths]} openapi
        {:strs  [parameters]
         ::keys [method path]} (or (first (for [[path opts] paths
                                                [method {:strs [operationId]
                                                         :as   operation}] opts
                                                :when (= operation-id operationId)]
                                            (into {::method method
                                                   ::path path}
                                              operation)))
                                 (throw (ex-info (str "Can't find " (pr-str operation-id))
                                          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                                           :operation-id                 operation-id})))
        {:strs [in-path in-query]} (group-by (fn [{:strs [in]}] (str "in-" in))
                                     parameters)
        path (reduce (fn [path {:strs [name required style]
                                :or   {style "simple"}}]
                       (when (and required
                               (not (contains? path-params name)))
                         (throw (ex-info (str "Missing " (pr-str name) " at path-params")
                                  {:cognitect.anomalies/category :cognitect.anomalies/incorrect})))
                       (if-let [[_ v] (find path-params name)]
                         (string/replace path
                           (re-pattern (str "\\{" name "}"))
                           (str v))
                         path))
               path
               in-path)
        query (reduce
                (fn [query {:strs [name required style]
                            :or   {style "form"}}]
                  (when (and required
                          (not (contains? query-params name)))
                    (throw (ex-info (str "Missing " (pr-str name) " at query-params")
                             {:cognitect.anomalies/category :cognitect.anomalies/incorrect})))
                  (if-let [[_ v] (find query-params name)]
                    (assoc query name v)
                    query))

                {}
                in-query)]
    (merge {:request-method (keyword (string/lower-case method))
            :uri            path}
      (when (seq query)
        {:query-string (string/join "&"
                         (map (fn [[k v]]
                                (str (URLEncoder/encode (str k)
                                       StandardCharsets/UTF_8)
                                  "=" (if (coll? v)
                                        (string/join ","
                                          (map #(URLEncoder/encode (str %)
                                                  StandardCharsets/UTF_8)
                                            v))
                                        (URLEncoder/encode (str v)
                                          StandardCharsets/UTF_8))))
                           query))}))))
