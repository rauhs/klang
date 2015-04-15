(ns klang.core)

(defmacro when-debug [& exprs]
  `(when ~(with-meta 'js/goog.DEBUG assoc :tag 'boolean)
     ~@exprs))

;; Transducers require clj1.7
(defonce xforms (atom [(filter (fn[_] false))]))

(defn single-transduce
  "Takes a transducer (xform) and an item and applies the transducer to the
  singe element and returnes the transduced item. Note: No reducing is
  involved. Returns nil if there was no result."
  [xform x]
  ((xform (fn[_ r] r)) nil x))

(defmacro elide!
  [transducer]
  ;;(swap)
  )

(defmacro deflogger [logger level]
  (if (single-transduce (apply comp @xforms) level)
    `(~'defn ~logger [& ~'msg] (klang.core/log! ~level ~'msg))
    `(~'defn ~logger [& ~'msg])
    ))

