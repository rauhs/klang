(ns klang.server
  (:require [klang.handler :refer [app]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:gen-class))

;; Doesn't actually run. We use figwheel instad
 (defn -main [& args]
   (let [port (Integer/parseInt (or (System/getenv "PORT") "3000"))]
     (run-jetty app {:port port :join? false})))
