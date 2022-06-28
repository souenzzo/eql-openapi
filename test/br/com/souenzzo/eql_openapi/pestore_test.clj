(ns br.com.souenzzo.eql-openapi.pestore-test
  (:refer-clojure :exclude [send])
  (:require [br.com.souenzzo.ring-http-client :as rhc]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.test :refer [response-for]]
            [midje.sweet :refer [=> fact]])
  (:import (java.net URLEncoder)
           (java.net.http HttpClient HttpRequest)
           (java.nio.charset StandardCharsets)))
(set! *warn-on-reflection* true)
(def ring-keys
  [;; DEPRECATED
   #_:character-encoding #_:content-length #_:content-type
   :body :headers :protocol :query-string :remote-addr :request-method :scheme :server-name :server-port
   :ssl-client-cert :uri])
(def v3-petstore
  (-> (io/file "OpenAPI-Specification" "examples" "v3.0" "petstore.json")
    io/reader
    json/read))


(defn ^HttpClient ->http-client
  [{::http/keys [service-fn]}]
  (proxy [HttpClient] []
    (send [^HttpRequest http-request body-handler]
      (let [body-publisher (.orElse (.bodyPublisher http-request) nil)
            {:keys [status body headers]} (apply response-for service-fn
                                            (or (some-> (.method http-request)
                                                  string/lower-case
                                                  keyword)
                                              :get)
                                            (str (.uri http-request))
                                            :headers (.map (.headers http-request))
                                            (concat (when body-publisher)
                                              [:body body-publisher]))]
        (rhc/ring-response->http-response {:status  status
                                           :headers headers
                                           :body    (io/input-stream (.getBytes (str body)))}
          body-handler)))))


(defn ring-request-for
  [{:strs [paths]} operation-id & {:keys [path-params query-params]}]
  (let [{:strs  [parameters]
         ::keys [method path]} (or (first (for [[path opts] paths
                                                [method {:strs [operationId]
                                                         :as   operation}] opts
                                                :when (= operation-id operationId)]
                                            (assoc operation
                                              ::method method
                                              ::path path)))
                                 (throw (ex-info (str "Can't find " (pr-str operation-id))
                                          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                                           :operation-id                 operation-id})))
        {:keys [in-path in-query]} (group-by (fn [{:strs [in]}] (keyword (str "in-" in)))
                                     parameters)
        path (reduce (fn [path {:strs [name required]}]
                       (when (and required
                               (not (contains? path-params (keyword name))))
                         (throw (ex-info (str "Missing " (pr-str name))
                                  {:cognitect.anomalies/category :cognitect.anomalies/incorrect})))
                       (if-let [[_ v] (find path-params (keyword name))]
                         (string/replace path
                           (re-pattern (str "\\{" name "}"))
                           (str v))
                         path))
               path
               in-path)
        query (reduce
                (fn [query {:strs [name required]}]
                  (when (and required
                          (not (contains? query-params (keyword name))))
                    (throw (ex-info (str "Missing " (pr-str name))
                             {:cognitect.anomalies/category :cognitect.anomalies/incorrect})))
                  (if-let [[_ v] (find query-params (keyword name))]
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
                                  "=" (URLEncoder/encode (str v)
                                        StandardCharsets/UTF_8)))
                           query))}))))

(deftest ring-request-for-test
  (fact
    (ring-request-for v3-petstore "listPets")
    => {:request-method :get
        :uri            "/pets"})
  (fact
    (ring-request-for v3-petstore "listPets"
      :query-params {:limit 42})
    => {:request-method :get
        :query-string   "limit=42"
        :uri            "/pets"})
  (fact
    (ring-request-for v3-petstore "createPets")
    => {:request-method :post
        :uri            "/pets"})
  (fact
    (ring-request-for v3-petstore "showPetById"
      :path-params {:petId "42"})
    => {:request-method :get
        :uri            "/pets/42"}))

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

(pco/defresolver listPets [{::keys [http-client]} {::keys []}]
  {::pco/output [:petstore.operation/listPets]}
  (let [-raw (-> (rhc/send http-client (ring-request-for v3-petstore "listPets"))
               :body
               io/reader
               json/read)]
    {:petstore.operation/listPets {:petstore.components.schemas.Pets/-raw -raw}}))

(pco/defresolver showPetById [{::keys [http-client]} {:petstore.operation.showPetById/keys [petId]}]
  {::pco/output [:petstore.operation/showPetById]}
  (let [-raw (-> (rhc/send http-client (ring-request-for v3-petstore "showPetById"
                                         :path-params {:petId petId}))
               :body
               io/reader
               json/read)]
    {:petstore.operation/showPetById {:petstore.components.schemas.Pet/-raw -raw}}))


(pco/defresolver Pets [{:petstore.components.schemas.Pets/keys [-raw]}]
  {::pco/output [:petstore.components.schemas/Pets]}
  {:petstore.components.schemas/Pets (mapv (fn [pet]
                                             {:petstore.components.schemas.Pet/-raw pet})
                                       -raw)})


(pco/defresolver Pet#id [{:petstore.components.schemas.Pet/keys [-raw]}]
  {::pco/output [:petstore.components.schemas.Pet/id]}
  (some-> -raw
    (get "id")
    (->> (array-map :petstore.components.schemas.Pet/id))))


(pco/defresolver Pet#name [{:petstore.components.schemas.Pet/keys [-raw]}]
  {::pco/output [:petstore.components.schemas.Pet/name]}
  (some-> -raw
    (get "name")
    (->> (array-map :petstore.components.schemas.Pet/name))))


(pco/defresolver Pet#tag [{:petstore.components.schemas.Pet/keys [-raw]}]
  {::pco/output [:petstore.components.schemas.Pet/tag]}
  (some-> -raw
    (get "tag")
    (->> (array-map :petstore.components.schemas.Pet/tag))))


(deftest hello
  (let [service-map (-> {}
                      petstore-service
                      http/create-servlet)
        http-client (->http-client service-map)
        env (pci/register {::http-client http-client} [listPets showPetById Pet#id Pet#name Pet#tag Pets])]
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
