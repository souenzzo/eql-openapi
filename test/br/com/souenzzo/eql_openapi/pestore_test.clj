(ns br.com.souenzzo.eql-openapi.pestore-test
  (:require [br.com.souenzzo.ring-http-client :as rhc]
            [br.com.souenzzo.ring-http-client.java-net-http]
            [br.com.souenzzo.ring-http-client.pedestal :as rhc.pedestal]
            [br.com.souenzzo.ring-openapi :as ro]
            [clojure.data.json :as json]
            [br.com.souenzzo.eql-openapi :as eql-openapi]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [midje.sweet :refer [=> fact]]
            [ring.core.protocols :as rcp])
  (:import (java.io PipedInputStream PipedOutputStream)))

(set! *warn-on-reflection* true)

(def ring-keys
  [;; DEPRECATED
   #_:character-encoding #_:content-length #_:content-type
   :body :headers :protocol :query-string :remote-addr :request-method :scheme :server-name :server-port
   :ssl-client-cert :uri])

(defn body-as-json
  [{:keys [body]
    :as   ring-response}]
  (let [in (PipedInputStream.)]
    (with-open [output-stream (PipedOutputStream. in)]
      (rcp/write-body-to-stream body ring-response output-stream))
    (json/read (io/reader in))))

(defn resolvers-for
  [{::keys    [base-name context->http-client]
    ::ro/keys [openapi]
    :as       opts}]
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
                                        {:keys [status headers]
                                         :as   response} (rhc/send (context->http-client env)
                                                           (ro/ring-request-for
                                                             (assoc opts ::ro/operation-id operationId
                                                               ::ro/query-params (into {}
                                                                                   (keep (fn [[param-name param-ident]]
                                                                                           (when-let [[_ v] (find params param-ident)]
                                                                                             [param-name v])))
                                                                                   query-params)
                                                               ::ro/path-params (into {}
                                                                                  (keep (fn [[param-name param-ident]]
                                                                                          (when-let [[_ v] (find input param-ident)]
                                                                                            [param-name v])))
                                                                                  path-params))))
                                        {:strs [content]} (get responses (str status) default)
                                        {:strs [schema]} (get content (get headers "Content-Type"))
                                        kw (keyword (string/join "."
                                                      [base-name (last (string/split (get schema "$ref") #"/"))])
                                             "-raw")]
                                    {output-ident {kw (body-as-json response)}}))})))
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


(def v3-petstore
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "petstore.json")
    io/reader
    json/read))

(defn petstore-service
  [service-map]
  (let [routes #{["/pets" :get (fn [{:keys [query-params]}]
                                 (let [limit (some-> query-params
                                               :limit
                                               parse-long)]
                                   {:headers {;; "x-next"       ""
                                              "Content-Type" "application/json"}
                                    :body    (cond-> [{:id   0
                                                       :name "Bisteca"
                                                       :tag  "beagle"}]
                                               (number? limit) (->> (take limit))
                                               :always json/write-str)
                                    :status  200}))
                  :route-name :listPets]
                 ["/pets" :post (fn [_]
                                  (let []
                                    {:status 201}))
                  :route-name :createPets]
                 ["/pets/:petId" :get (fn [{:keys [path-params]}]
                                        (let [pet-id (-> path-params
                                                       :petId)]
                                          {:body    (-> {:id   0
                                                         :name "Bisteca"
                                                         :tag  "beagle"}
                                                      json/write-str)
                                           :headers {"Content-Type" "application/json"}
                                           :status  200}))
                  :route-name :showPetById]}]
    (-> (assoc service-map
          ::url-for (-> routes
                      route/expand-routes
                      route/url-for-routes)
          ::http/routes routes)
      http/default-interceptors)))



(deftest petstore-test
  (let [{::rhc.pedestal/keys [http-client]
         :as                 env} (-> {}
                                    petstore-service
                                    http/create-servlet
                                    rhc.pedestal/create-ring-http-client)
        env (pci/register (assoc env
                            ::http-client http-client)
              (resolvers-for {::ro/openapi           v3-petstore
                              ::context->http-client ::http-client
                              ::base-name            "petstore"}))]
    (fact
      "listPets"
      (p.eql/process env [{:petstore.operation/listPets [{:petstore/Pets
                                                          [:petstore.Pet/id
                                                           :petstore.Pet/name]}]}])
      => {:petstore.operation/listPets {:petstore/Pets [{:petstore.Pet/id   0
                                                         :petstore.Pet/name "Bisteca"}]}})
    (fact
      "listPets limit"
      (p.eql/process env `[{(:petstore.operation/listPets ~{:petstore.operation.listPets/limit "0"})
                            [{:petstore/Pets
                              [:petstore.Pet/id
                               :petstore.Pet/name]}]}])
      => {:petstore.operation/listPets {:petstore/Pets []}})
    (fact
      "showPetById"
      (p.eql/process env {:petstore.operation.showPetById/petId 42}
        [{:petstore.operation/showPetById
          [:petstore.Pet/id
           :petstore.Pet/name]}])
      => {:petstore.operation/showPetById {:petstore.Pet/id   0
                                           :petstore.Pet/name "Bisteca"}})))
