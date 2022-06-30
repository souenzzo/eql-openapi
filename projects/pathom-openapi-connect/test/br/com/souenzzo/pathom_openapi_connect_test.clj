(ns br.com.souenzzo.pathom-openapi-connect-test
  (:require [br.com.souenzzo.pathom-openapi-connect :as poc]
            [br.com.souenzzo.pathom-openapi-connect.petstore :as petstore]
            [br.com.souenzzo.ring-http-client.pedestal :as rhc.pedestal]
            [br.com.souenzzo.ring-openapi :as ro]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest]]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [io.pedestal.http :as http]
            [midje.sweet :refer [=> fact]]
            [ring.core.protocols :as rcp])
  (:import (java.io PipedInputStream PipedOutputStream)))


(defn response->content
  [env content
   {:keys [body]
    :as   ring-response}]
  (let [in (PipedInputStream.)]
    (with-open [output-stream (PipedOutputStream. in)]
      (rcp/write-body-to-stream body ring-response output-stream))
    (json/read (io/reader in))))

(deftest petstore-test
  (let [{::rhc.pedestal/keys [http-client]
         :as                 env} (-> {}
                                    petstore/service
                                    http/create-servlet
                                    rhc.pedestal/create-ring-http-client)
        env (pci/register (assoc env
                            ::http-client http-client)
              (poc/resolvers-for {::poc/openapi               petstore/spec-v3
                                  ::poc/context->http-client ::http-client
                                  ::poc/response->content    response->content
                                  ::poc/base-name            "petstore"}))]
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
