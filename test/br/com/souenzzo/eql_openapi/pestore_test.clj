(ns br.com.souenzzo.eql-openapi.pestore-test
  (:require [br.com.souenzzo.ring-http-client :as rhc]
            [br.com.souenzzo.ring-http-client.java-net-http]
            [br.com.souenzzo.ring-http-client.pedestal :as rhc.pedestal]
            [br.com.souenzzo.ring-openapi :as ro]
            [clojure.data.json :as json]
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

(defn body-as-reader
  [{:keys [body]
    :as   ring-response}]
  (let [in (PipedInputStream.)]
    (with-open [output-stream (PipedOutputStream. in)]
      (rcp/write-body-to-stream body ring-response output-stream))
    (io/reader in)))

(defn resolvers-for
  [{::keys    [base-name]
    ::ro/keys [openapi]
    :as       opts}]
  (concat [(pco/resolver `listPets
             {::pco/output [:petstore.operation/listPets]}
             (fn [{::keys [http-client]} _]
               (let [-raw (-> (rhc/send http-client (ro/ring-request-for
                                                      (assoc opts ::ro/operation-id "listPets")))
                            body-as-reader
                            json/read)]
                 {:petstore.operation/listPets {:petstore.components.schemas.Pets/-raw -raw}})))
           (pco/resolver `showPetById
             {::pco/input  [:petstore.operation.showPetById/petId]
              ::pco/output [:petstore.operation/showPetById]}
             (fn [{::keys [http-client]} {:petstore.operation.showPetById/keys [petId]}]
               (let [-raw (-> (rhc/send http-client (ro/ring-request-for (assoc opts
                                                                           ::ro/operation-id "showPetById"
                                                                           ::ro/path-params {:petId petId})))
                            body-as-reader
                            json/read)]
                 {:petstore.operation/showPetById {:petstore.components.schemas.Pet/-raw -raw}})))]
    (map pco/resolver
      (for [[ident schema] (get-in openapi ["components" "schemas"])
            :let [kw (keyword
                       (string/join "."
                         [base-name "components" "schemas" ident])
                       "-raw")
                  {:strs [properties items]} schema]
            operation (cond
                        properties (for [[ident _] properties
                                         :let [output (keyword
                                                        (namespace kw)
                                                        ident)]]
                                     {::pco/op-name (gensym)
                                      ::pco/input   [kw]
                                      ::pco/resolve (fn [env input]
                                                      (let []
                                                        (when-let [[_ v] (find (get input kw) ident)]
                                                          {output v})))
                                      ::pco/output  [output]})
                        items (let [output (keyword
                                             (string/join "."
                                               [base-name "components" "schemas"])
                                             ident)
                                    new-raw (keyword
                                              (string/join "."
                                                [base-name "components" "schemas"
                                                 (last (string/split (get items "$ref") #"/"))])
                                              "-raw")]
                                [{::pco/op-name (gensym)
                                  ::pco/input   [kw]
                                  ::pco/resolve (fn [env input]
                                                  {output (mapv (fn [-raw]
                                                                  {new-raw -raw})
                                                            (get input kw))})
                                  ::pco/output  [output]}])
                        :else [schema])]
        operation))))


(def v3-petstore
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "petstore.json")
    io/reader
    json/read))

(defn petstore-service
  [{::keys []
    :as    service-map}]
  (let [routes #{["/pets" :get (fn [{:keys [query-params]}]
                                 (let [limit (some-> query-params
                                               :limit
                                               parse-long)]
                                   {:headers {;; "x-next"       ""
                                              "Content-Type" "application/json"}
                                    :body    (-> [{:id   0
                                                   :name "Bisteca"
                                                   :tag  "beagle"}]
                                               json/write-str)
                                    :status  200}))
                  :route-name :listPets]
                 ["/pets" :post (fn [_]
                                  (let []
                                    {:status 201}))
                  :route-name :createPets]
                 ["/pets/:petId" :get (fn [{:keys [path-params]}]
                                        (let [pet-id (-> path-params
                                                       :petId)]
                                          {:body   (-> {:id   0
                                                        :name "Bisteca"
                                                        :tag  "beagle"}
                                                     json/write-str)
                                           :status 202}))
                  :route-name :showPetById]}]
    (-> (assoc service-map
          ::url-for (-> routes
                      route/expand-routes
                      route/url-for-routes)
          ::http/routes routes)
      http/default-interceptors)))



(deftest hello
  (let [{::rhc.pedestal/keys [http-client]
         :as                 env} (-> {}
                                    petstore-service
                                    http/create-servlet
                                    rhc.pedestal/create-ring-http-client)
        env (pci/register (assoc env
                            ::http-client http-client)
              (resolvers-for {::ro/openapi v3-petstore
                              ::base-name  "petstore"}))]
    (fact
      "listPets"
      (p.eql/process env [{:petstore.operation/listPets [{:petstore.components.schemas/Pets
                                                          [:petstore.components.schemas.Pet/id
                                                           :petstore.components.schemas.Pet/name]}]}])
      => {:petstore.operation/listPets {:petstore.components.schemas/Pets [{:petstore.components.schemas.Pet/id   0
                                                                            :petstore.components.schemas.Pet/name "Bisteca"}]}})
    (fact
      "showPetById"
      (p.eql/process env {:petstore.operation.showPetById/petId 42}
        [{:petstore.operation/showPetById
          [:petstore.components.schemas.Pet/id
           :petstore.components.schemas.Pet/name]}])
      => {:petstore.operation/showPetById {:petstore.components.schemas.Pet/id   0
                                           :petstore.components.schemas.Pet/name "Bisteca"}})))
