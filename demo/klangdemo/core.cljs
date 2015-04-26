(ns ^:figwheel-always
  klangdemo.core
  (:require-macros
   [klang.macros :as macros]
   [cljs.core.async.macros :refer [go-loop go]])
  (:require
   [cljs.core.async :refer [put! chan sliding-buffer <! mult
                            tap close! pub sub timeout take!]]
   [reagent.core :as r]
   [klang.core :refer [tab->type!  tab->ns!  tab->ns*!  *db* logger
                       ns*->color!  ns->color!  log!  raw-log!] :as k])
  (:import goog.date.DateTime))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; For development
(defn ex-log-data []
  (ns*->color! *db* "klang.core" "yellow")
  (ns->color! *db* "my.ns" "green")
  (tab->type! *db* :erro :ERRO)
  (tab->type! *db* :errwarn :ERRO :WARN)
  (tab->ns! *db* :my.ns "my.ns")
  (tab->type! *db* :my.ns :INFO)
  (tab->ns*! *db* :my.ns* "my.ns")
  ;;(msg->console! *db* :CONSOLE)

  (doseq [x (range 15)
          :let [lg {:time (goog.date.DateTime.)
                    :msg (str "Log msg " (* x 1))
                    :type :INFO
                    :ns "my.ns"}]]
    ;; Will receive a time for the channel listener
    (raw-log! *db* lg))

  (doseq [x (range 15)
          :let [lg {:msg (str "Log msg " (* x 1))
                    :type :TRAC
                    :ns "my.ns.one"}]]
    ;; Will receive a time for the channel listener
    (raw-log! *db* lg))

  (log! :TRAC "Trace log" true)
  (log! :DEBG "No ns")
  (log! :INFO "No ns")
  (log! :FATA "fatal stuff")
  (log! :CRIT "World peace not achieved.")
  (log! *db* :FATA "With db and stuff")
  (log! *db* :WARN "Warn and stuff")
  (log! ::INFO {:test "foo"} :bar "this is a problem")
  (log! ::WARN {:test "twooo"})
  (log! ::ERRO {:test "foo"} :bar)
  (log! ::INFO nil)
  (log! ::DEBG :functions-can-be-logged (fn[name] (str "Hello " name)))
  (log! ::TRAC
        :nil-also-works nil 'symbols 'also 'work
        :this :should ["wrap" :around "in your browser window"]))

(defn demo! []
  (k/init-single-mode!) ;; Sets *db*
  (k/init!)
  (k/default-config!)
  (k/show!)
  (ex-log-data))


(def gen-logs
  (delay
   (go-loop [i 0]
     (<! (timeout 10))
     (log! ::INFO {:gen i})
     (recur (+ i 1)))))


;; Deref to generate logs
;; @gen-logs

(defonce gen (r/atom false))

;;(defonce  )

(defn toggle-generator [_]
  (swap! gen not))

(defn demo-ui [gen]
  [:div
   [:p "Press the key \"l\" to view the logs."]
   [:a {:style {:cursor "pointer" :text-decoration "underline"}
        :on-click toggle-generator
        }
    (when @gen "Stop ") "generate Random data"]])

(r/render [demo-ui gen] js/document.body)

(demo!)

(log! ::TRAC :may-wanna-click "on on this message" js/document.head)

(macros/logger! 'klang.core/log!)
;;(macros/add-form-meta! :line :file)
(macros/add-form-meta! :line)
;; This adds the enviornment to every log call
(macros/add-form-env! true)

(macros/log! ::INFO :test-macro)
(macros/info! :test-info)

(let [x :foo
      this-is [:lots :of 'fun]]
  (macros/env! :I-can-dump-local)
  (macros/warn! :click "ME"))

;;(macros/env! :no-local-bindings ":(")

