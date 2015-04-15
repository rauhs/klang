(ns klang.core)

(defmacro when-debug [& exprs]
  `(when ~(with-meta 'js/goog.DEBUG assoc :tag 'boolean)
     ~@exprs))

;; Transducers require clj1.7
;;(defonce td (atom [(filter (fn[_] false))]))

(defmacro elider!
  [transducer]
  )

#_(defmacro deflogger [logger level]
  ;;(if)
  `(defn ~logger [& msg] (klang.core/log! ~level msg))
  )

