(ns klang.core)

(defmacro when-debug [& exprs]
  `(when ~(with-meta 'js/goog.DEBUG assoc :tag 'boolean)
     ~@exprs))

#_(defmacro elider!
    [transducer]
    ())

#_(defmacro deflogger [logger level]
  `(defn ~logger [& msg] (klang.core/log ~level ~@msg))
  )

