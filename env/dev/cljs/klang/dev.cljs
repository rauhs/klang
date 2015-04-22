(ns ^:figwheel-no-load
  klang.dev
  (:require [klang.core :as core]
            [klangdemo.core :as demo]
            [figwheel.client :as figwheel :include-macros true]
            [weasel.repl :as weasel]
            [reagent.core :as r]))

;; Allow (println) to print to js console
(enable-console-print!)

(figwheel/watch-and-reload
  :websocket-url "ws://localhost:3449/figwheel-ws"
  :jsload-callback demo/figwheel-reload)

(weasel/connect "ws://localhost:9001" :verbose true)

;;(demo/init!)
