(ns br.com.souenzzo.pathom-openapi-connect.petstore
  (:require [clojure.data.json :as json]
            [io.pedestal.http.route :as route]
            [io.pedestal.http :as http]
            [clojure.java.io :as io]))

(defn service
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

(def spec-v3
  (-> (io/file ".." ".." "OpenAPI-Specification" "examples" "v3.0" "petstore.json")
    io/reader
    json/read))
