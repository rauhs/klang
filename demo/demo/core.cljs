(ns ;;^:figwheel-always
  demo.core
  (:require-macros
   [klang.macros :as macros]
   [cljs.core.async.macros :refer [go-loop go]])
  (:require
   [cljs.core.async :refer [put! chan sliding-buffer <! mult
                            tap close! pub sub timeout take!]]
   [klang.core :refer [tab->type! 
                       tab->ns!
                       tab->ns*!
                       *db*
                       logger
                       ns*->color!
                       ns->color!
                       log!
                       raw-log!]
    :as k]))

(def gen-logs
  (delay
   (go-loop [i 0]
     (<! (timeout 100))
     (log! ::INFO {:gen i})
     ;;(l i)
     (recur (+ i 1)))))

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
  (log! :FATAL "fatal stuff")
  (log! :CRIT "World peace not achieved.")
  (log! *db* :FATAL "With db and stuff")
  (log! *db* :WARN "Warn and stuff")

  (let [lg (logger ::ERRO)]
    (lg {:test "foo"} :bar "this is a problem")
    (lg {:test "twooo"}))

  (let [lg (logger ::ERRO)]
    (lg {:test "foo"} :bar)
    (lg nil)
    (lg :function  (fn[name] (str "Hello " name)))
    ;; Long message should wrap
    (lg :test "This is a longer test message so we can see wrapping it around."
        :nil=also-works nil
        nil 'symbols 'also 'work
        :this :should :really ["wrap" :around "on your small browser window"])))

(defn demo! []
  (k/init-single-mode!) ;; Sets *db*
  (k/init!)
  (k/default-config!)
  (k/show!)
  (ex-log-data))


;; Deref to generate logs

;; TODO: Create a demo UI to allow switching sources on-off
;; @gen-logs

(demo!)


;;(macros/init-debug-prod!)
(macros/init-dev!)
;;(macros/log! ::WARN :hi-with-macros)

(macros/info! :info "logging")
(macros/warn! :warn "logging")






