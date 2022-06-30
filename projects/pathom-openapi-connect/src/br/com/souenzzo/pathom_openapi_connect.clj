(ns br.com.souenzzo.pathom-openapi-connect
  (:require [br.com.souenzzo.eql-openapi :as eql-openapi]
            [br.com.souenzzo.ring-http-client :as rhc]
            [br.com.souenzzo.ring-openapi :as ro]
            [clojure.string :as string]
            [com.wsscode.pathom3.connect.operation :as pco]))


(defn resolvers-for
  [{::keys [base-name context->http-client response->content openapi]
    :as    opts}]
  (concat
    (into []
      (comp (filter (comp #{"get"} ::eql-openapi/method))
        (map (fn [{::eql-openapi/keys [parameters]
                   :strs              [responses operationId]}]
               (let [base-ns (string/join "."
                               [base-name "operation"])
                     base-operation-ns (string/join "."
                                         [base-name "operation" operationId])
                     {:strs [default]} responses
                     output-ident (keyword base-ns operationId)
                     path-params (into {}
                                   (comp
                                     (filter (fn [{:strs [in]}]
                                               (= in "path")))
                                     (map (fn [{:strs [name]}]
                                            [name
                                             (keyword base-operation-ns name)])))
                                   parameters)
                     query-params (into {}
                                    (comp
                                      (filter (fn [{:strs [in]}]
                                                (= in "query")))
                                      (map (fn [{:strs [name]}]
                                             [name
                                              (keyword base-operation-ns name)])))
                                    parameters)]
                 {::pco/op-name (symbol output-ident)
                  ::pco/output  [output-ident]
                  ::pco/input   (vec (vals path-params))
                  ::pco/resolve (fn [env input]
                                  (let [params (pco/params env)
                                        http-client (context->http-client env)
                                        request (ro/ring-request-for
                                                  (assoc opts ::ro/openapi openapi
                                                    ::ro/operation-id operationId
                                                    ::ro/query-params (into {}
                                                                        (keep (fn [[param-name param-ident]]
                                                                                (when-let [[_ v] (find params param-ident)]
                                                                                  [param-name v])))
                                                                        query-params)
                                                    ::ro/path-params (into {}
                                                                       (keep (fn [[param-name param-ident]]
                                                                               (when-let [[_ v] (find input param-ident)]
                                                                                 [param-name v])))
                                                                       path-params)))
                                        {:keys [status headers]
                                         :as   response} (rhc/send http-client request)
                                        {:strs [content]} (get responses (str status) default)
                                        {:strs [schema]
                                         :as   content} (get content (get headers "Content-Type"))
                                        kw (keyword (string/join "."
                                                      [base-name (last (string/split (get schema "$ref") #"/"))])
                                             "-raw")]
                                    {output-ident {kw (response->content opts
                                                        content
                                                        response)}}))})))
        (map pco/resolver))
      (eql-openapi/operations openapi))
    (into []
      (comp
        (map (fn [[schema-name schema-object-or-reference]]
               (let [schema-object (eql-openapi/dereference openapi schema-object-or-reference)
                     schema-ident (keyword
                                    (string/join "."
                                      [base-name schema-name])
                                    "-raw")
                     {:strs [properties items]} schema-object]
                 (cond
                   properties (for [[property-name _] properties
                                    :let [property-ident (keyword
                                                           (namespace schema-ident)
                                                           property-name)]]
                                {::pco/op-name (symbol property-ident)
                                 ::pco/input   [schema-ident]
                                 ::pco/resolve (fn [env input]
                                                 (let []
                                                   (when-let [[_ v] (find (get input schema-ident) property-name)]
                                                     {property-ident v})))
                                 ::pco/output  [property-ident]})
                   items (let [items-ident (keyword base-name schema-name)
                               item-element-ident (keyword
                                                    (string/join "."
                                                      [base-name
                                                       (last (string/split (get items "$ref") #"/"))])
                                                    "-raw")]
                           [{::pco/op-name (symbol items-ident)
                             ::pco/input   [schema-ident]
                             ::pco/resolve (fn [env input]
                                             {items-ident (mapv (fn [-raw] {item-element-ident -raw})
                                                            (get input schema-ident))})
                             ::pco/output  [items-ident]}])
                   :else (throw (ex-info (str "unknow schema: " (pr-str schema-name))
                                  {:object schema-object
                                   :ref    schema-object-or-reference}))))))
        cat
        (map pco/resolver))
      (get-in openapi ["components" "schemas"]))))

