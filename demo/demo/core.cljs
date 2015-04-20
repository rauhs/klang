(ns ^:figwheel-always demo.core
  (:require-macros
   [klang.macros :as macros]
   [cljs.core.async.macros :refer [go-loop go]])
  (:require
   [cljs.core.async :refer [put! chan sliding-buffer <! mult
                            tap close! pub sub timeout take!]]
   [klang.core :refer [tab->type!  tab->ns!  *db*
                       ns*->color  log! raw-log!
                       ]]
   ))


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
  (ns*->color *db* "klang.core" "yellow")
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
  ;; These will log to the console
  ;; OBSOLETE:
  #_(let [lg (logger ::CONSOLE)]
    (lg {:test "foo"} :bar))

  (log! :TRAC "World peace not achieved.")
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

(defn demo!
  []
  (k/init-single-mode!) ;; Sets *db*
  (k/init!)
  (k/default-config!)
  (k/show!)
  (ex-log-data))


;; Deref to generate logs
;; @gen-logs

(k/demo!)

