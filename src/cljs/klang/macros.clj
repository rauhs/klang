(ns klang.macros)

(defmacro when-debug
  [& exprs]
  `(when ~(with-meta 'js/goog.DEBUG assoc :tag 'boolean)
     ~@exprs))

(defonce xforms
  (atom [(filter (constantly true))]))

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

(defmacro add-filter!
  "Adds the filter_fn function to the global filter functions. The
  function will be called by the macros `deflogger' and `log!' with
  the keyword for the log message. If the function returns false the
  logger/log-msg will be elided during compile time. If true, the log
  call will remain in place.
  Example:
  (add-filter! (filter #(= % ::INFO)))"
  [filter_fn]
  (swap! xforms conj (eval filter_fn))
  ;; Don't emit any code:
  nil)

(defmacro log!
  "Don't use this. Write your own."
  [ns_level & msg]
  (when-let [nslv-td (single-transduce (apply comp @xforms) ns_level)]
      ;; If we 
      `(klang.core/log! ~nslv-td ~@msg)
      )
  )

(defmacro deflogger
  "Dont use me! Currently does not full elide function calls to the
  defined logger"
  [logger level]
  ;;`(~'defmacro ~logger [& ~'_] )
  ;;`(~'defn ~logger [& ~'_])
  (if (single-transduce (apply comp @xforms) level)
    `(~'defn ~logger [& ~'msg] (apply klang.core/log! ~level ~'msg))
    ;;`(~'defn ~logger [& ~'msg])
    ;; Closure compiler will elide this useless function:
    `(~'defn ~logger [& ~'msg])
    ))

