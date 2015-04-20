(ns klang.macros)
;; I tried to create a user usable code generator here but the entire clj<->cljs
;; makes this very tough. Calling the macros with things other than data is
;; weird due to namespacing.
;; All the below seems get too complicated for actual client use.
;; It's probably much easier if everybody just writes their own macros and
;; simplifies this down to 3 macros:
;; (init-dev!)
;; (init-prod!)
;; (log! ::INFO :blah)
;; Remember to always return nil in macros that shouldn't generate any code.


;; This requires Clojure 1.7 due to the use of transducers. But it can
;; be modified easily to use simple functions.
;; This macro file (.clj) is used in both, your production and dev environment.
;; You'll call them differently by using different :source-paths in your
;; leiningen configuration.

;; The global atom holds the filters/transducers that determine if the log! call
;; should be elided or not:
;; This lives only during the compilation phase and will not result in any
;; javascript code
;; Q: Why transducers and not just an array of predicate functions?
;; A: We may be interested in changing (ie. (map..)) the passed in keyword. For
;; instance by removing the namespace from the keyword.
;; The function transduces on (namespaced) keywords which are the log namespace
;; and type
(def xforms (atom [(filter (constantly true))]))

;; The function that is called for logging.
(def logger 'klang.core/log!)

(defn logger! [log-sym]
  (alter-var-root (var logger) (fn[_] log-sym)))

;; If we should add line information to each log! call
(def add-line-nr false)

(defn line-nr! [tf]
  (alter-var-root (var add-line-nr) (fn[_] tf)))

(defn single-transduce
  "Takes a transducer (xform) and an item and applies the transducer to the
  singe element and returnes the transduced item. Note: No reducing is
  involved. Returns nil if there was no result."
  [xform x]
  ((xform (fn[_ r] r)) nil x))

;; You may also make this a macro if you want to call it from cljs
(defn strip-ns!
  "Adds a transducer so that namespace information is stripped from the log!
  call"
  []
  (swap! xforms conj
         (map (fn[type] (keyword (name type))))))

(defmacro init-dev! []
  (line-nr! true)
  nil)


(defmacro init-debug-prod!
  "Sets up logging for production "
  []
  ;;(logger! 'my.app.log/log->server!)
  (line-nr! false)
  (strip-ns!)
  (swap! xforms conj
         ;; Only allow error message
         (comp 
          ;;(filter (fn[type] (= (name type) "ERRO")))
          (filter #(some (partial = (name %))
                         ["ERRO" "WARN"]))))
  nil)

(defmacro init-prod!
  "Productin. Strip all logging calls."
  []
  (logger! nil) ;; Not needed but just in case
  (swap! xforms conj
         (filter (constantly false)))
  nil)

;; The main macro to call all thoughout your cljs code:
;; ns_type is your usual ::INFO, ::WARN etc.
(defmacro log!
  "Don't use this. Write your own."
  [ns_type & msg]
  ;; when-let returns nil which emits no code so we're good
  (when-let [nslv-td (single-transduce (apply comp @xforms) ns_type)]
    (if add-line-nr
      `(~logger ~nslv-td ~(str "#" (:line (meta &form))) ~@msg)
      `(~logger ~nslv-td ~@msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Other utils:

(defmacro dochan [[binding chan] & body]
  `(let [chan# ~chan]
     (cljs.core.async.macros/go
       (loop []
         (if-let [~binding (cljs.core.async/<! chan#)]
           (do
             ~@body
             (recur))
           :done)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OLD:
;; Not so well working.

(defmacro when-debug
  [& exprs]
  `(when ~(with-meta 'js/goog.DEBUG assoc :tag 'boolean)
     ~@exprs))

(defn add-filter!
  "Adds the filter_fn function to the global filter functions. The
  function will be called by the macros `deflogger' and `log!' with
  the keyword for the log message. If the function returns false the
  logger/log-msg will be elided during compile time. If true, the log
  call will remain in place.
  Example:
  (add-filter! (filter #(= % ::INFO)))"
  [filter_fn]
  ;;(swap! xforms conj (eval filter_fn))
  (swap! xforms conj filter_fn)
  ;; Don't emit any code:
  nil)

#_(defmacro log!
    "Don't use this. Write your own."
    [ns_type & msg]
    ;; when-let returns nil which emits no code so we're good
    (when-let [nslv-td (single-transduce (apply comp @xforms) ns_type)]
      `(klang.core/log! ~nslv-td ~@msg)))


(defmacro deflogger
  "Dont use me! Currently does not full elide function calls to the
  defined logger"
  [logger level]
  ;; Can't emit macros in clojurescript (right?)
  ;;`(~'defmacro ~logger [& ~'_] )
  `(~'defn ~logger [& ~'_])
  #_(if (single-transduce (apply comp @xforms) level)
      `(~'defn ~logger [& ~'msg] (klang.core/log! ~level ~'msg))
      ;; Closure compiler wont elide this useless function:
      `(~'defn ~logger [& ~'msg])
      ))


