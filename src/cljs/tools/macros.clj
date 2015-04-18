(ns tools.macros
  (:require-macros
    [cljs.core.async.macros :refer [go alt!]]
    [tools.helpers :as h])
  #_(:require 
    #_[cljs.core.async.macros :refer [go alt!]]
    #_[cljs.core.async :refer [>! <! chan put! close! timeout]]))

;; Some from:
;; https://github.com/grammati/enos/blob/master/src/enos/core.clj

(defmacro suppress
  "Suppresses any errors thrown.
    (suppress (error \"Error\")) ;=> nil
    (suppress (error \"Error\") :error) ;=> :error
  "
  ([body]
     `(try ~body (catch Throwable ~'t)))
  ([body catch-val]
     `(try ~body (catch Throwable ~'t
                   (cond (fn? ~catch-val)
                         (~catch-val ~'t)
                         :else ~catch-val)))))

(defmacro case-let [[var bound] & body]
  `(let [~var ~bound]
     (case ~var ~@body)))

(defmacro pause!
  "Used in a go-block, pauses execution for `ms` milliseconds without
  blocking a thread."
  [ms]
  `(<! (timeout ~ms)))


;; (async/go (try
;;             (let [data      (<? (get-data "clojure"))
;;                   more-data (<? (get-data "core.async")]
;;               ;; process all data
;;               )
;;             ;; Handle exceptions for all '<? calls'
;;             (catch Exception e
;;               (log/error "error getting data"))))

(defmacro <? [expr]
  `(h/throw-err (<! ~expr)))
 
(defmacro dochan [[binding chan] & body]
  `(let [chan# ~chan]
     (go
       (loop []
         (if-let [~binding (<! chan#)]
           (do
             ~@body
             (recur))
           :done)))))

