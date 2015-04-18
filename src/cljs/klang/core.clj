(ns klang.core)

(defmacro when-debug [& exprs]
  `(when ~(with-meta 'js/goog.DEBUG assoc :tag 'boolean)
     ~@exprs))

(defonce xforms (atom [(filter (constantly true))]))

(defn single-transduce
  "Takes a transducer (xform) and an item and applies the transducer to the
  singe element and returnes the transduced item. Note: No reducing is
  involved. Returns nil if there was no result."
  [xform x]
  ((xform (fn[_ r] r)) nil x))

(defmacro dochan [[binding chan] & body]
  `(let [chan# ~chan]
     (cljs.core.async.macros/go
       (loop []
         (if-let [~binding (cljs.core.async/<! chan#)]
           (do
             ~@body
             (recur))
           :done)))))

(defmacro elide!
  [filter_fn]
  (swap! xforms conj (eval filter_fn))
  ;; Not sure if I need this nil
  nil)

(defmacro deflogger [logger level]
  ;;`(~'defmacro ~logger [& ~'_] )
  ;;`(~'defn ~logger [& ~'_])
  (if (single-transduce (apply comp @xforms) level)
      `(~'defn ~logger [& ~'msg] (apply klang.core/log! ~level ~'msg))
      ;;`(~'defn ~logger [& ~'msg])
      ;; Closure compiler will elide this useless function:
      `(~'defn ~logger [& ~'msg])
      ))

