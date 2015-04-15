(ns klang.dev
  (:require [cemerick.piggieback :as piggieback]
            [weasel.repl.websocket :as weasel]
            [leiningen.core.main :as lein]))

;; Usually started from the nREPL
(defn browser-repl []
  (piggieback/cljs-repl
   :repl-env
   (weasel/repl-env
    :ip "localhost"
    :port 9001)))

;; Usually already started in a separate JVM with "lein figwheel"
(defn start-figwheel []
  (future
    (print "Starting figwheel.\n")
    (lein/-main ["figwheel"])))
