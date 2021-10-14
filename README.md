# eql-openapi

> A library to get EQL AST's from OpenAPI specs

> !! Not Stable. I will rename some functions.

# usage

```clojure
(require '[br.com.souenzzo.eql-openapi :as eql-openapi]
  '[edn-query-language.core :as eql]
  '[clojure.java.io :as io]
  '[clojure.data.json :as json])


(let [;; you need to read/parse your JSON or YAML.
      ;; both are supported, but you should keep the map keys as strings
      openapi (json/read (io/reader "petstore.json"))]
  ;; then you can ask for a schema via ref
  (-> openapi
    (eql-openapi/schema-or-reference->ast {"$ref" "#/components/schemas/Pets"})
    eql/ast->query))
;; => [:id :name :tag]
```

# about

Official EQL docs

- https://edn-query-language.org

Checkout my others EQL Libraries

- https://github.com/souenzzo/eql-datomic
- https://github.com/souenzzo/eql-as
