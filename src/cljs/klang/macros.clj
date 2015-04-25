(ns klang.macros)
;; This requires Clojure 1.7 due to the use of transducers. But it can
;; be modified easily to use simple functions.
;; This macro file (.clj) is used in both, your production and dev environment.
;; You'll call them differently by using different :source-paths in your
;; leiningen configuration.

;; The global atom holds the transducers that determine if the log! call should
;; be elided or not:
;; This lives only during the compilation phase and will not result in any
;; javascript code
(def xforms
  (atom [(filter (constantly true))]))

;; Create a map that allows to say which type + namespace combo is elided?
;; TODO:
;;(def )

;; The function that is called for logging.
(def ^:dynamic ^:private *logger* 'klang.core/log!)

(defmacro logger!
  "Changes the logger that will be called to log-sym.
  Needs to be a fully qualified symbol.
  Example:
  (logger! 'myapp.logging/log!)"
  [log-sym]
  (alter-var-root #'*logger* (fn[_] (eval log-sym)))
  nil)

;; Hold the keywords of which metadata of &form should be added to a log! call
(def ^:dynamic *form-meta-keywords* #{})

(defmacro add-form-meta!
  "You can add &form metadata whenever you call log!. Sensible metadata:
  :file - The filename form where you call log!
  :line - The line number form where you call log!"
  [& meta-keywords]
  (alter-var-root #'*form-meta-keywords*
                  (fn[prev] (apply conj prev (eval `(hash-set ~@meta-keywords)))))
  nil)

(defmacro strip-ns!
  "Adds a transducer so that namespace information is stripped from the log!
  call"
  []
  (swap! xforms conj
         (map (fn[type] (keyword (name type)))))
  nil)

(defn single-transduce
  "Takes a transducer (xform) and an item and applies the transducer to the
  singe element and returnes the transduced item. Note: No reducing is
  involved. Returns nil if there was no result."
  [xform x]
  ((xform (fn[_ r] r)) nil x))

;; The main macro to call all thoughout your cljs code:
;; ns_type is your usual ::INFO, ::WARN etc.
(defmacro log!
  "Logs to the main logger. Example:
  (log! ::INFO :my nil \"debug message\")"
  [ns_type & msg]
  ;; when-let returns nil which emits no code so we're good
  (let [meta-d (select-keys (meta &form) *form-meta-keywords*)]
    (when-let [nslv-td (single-transduce (apply comp @xforms) ns_type)]
      (if (not-empty meta-d)
        ;; We need some method to pass in the meta data: We cannot assoc the
        ;; metadata to the function or keyword so we have to pass it in as an
        ;; argument which we have to remove again
        `(~*logger* ~nslv-td (with-meta 'klang.core/meta-data ~meta-d) ~@msg)
        `(~*logger* ~nslv-td ~@msg)))))

;;(defmacro )

(defn ns-kw [kw]
  (keyword (name (ns-name *ns*)) kw))

(defmacro trac! [& msg]
  `(log! ~(ns-kw "TRAC") ~@msg))

(defmacro debg! [& msg]
  `(log! ~(ns-kw "DEBG") ~@msg))

(defmacro info! [& msg]
  `(log! ~(ns-kw "INFO") ~@msg))

(defmacro warn! [& msg]
  `(log! ~(ns-kw "WARN") ~@msg))

(defmacro erro! [& msg]
  `(log! ~(ns-kw "ERRO") ~@msg))

(defmacro crit! [& msg]
  `(log! ~(ns-kw "CRIT") ~@msg))

(defmacro fata! [& msg]
  `(log! ~(ns-kw "FATA") ~@msg))

;; Stolen from lazytest
(defn- local-bindings
  "Returns a map of the names of local bindings to their values."
  [env]
  (reduce (fn [m sym] (assoc m `'~sym sym))
	  {} (keys env)))

(defmacro env!
  "Logs your &env bindings."
  ;; TODO
  [& msg]
  `(log! ~(keyword (name (ns-name *ns*)) "TRAC") ~@msg ~@(keys &env)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Other utils:
(defmacro dochan
  "(dochan [x your-chan]
  ___(xyz x)
  ___(zyx x))
  Returns a channel that returns :done when the channel closes."
  [[binding chan] & body]
  `(let [chan# ~chan]
     (cljs.core.async.macros/go
       (loop []
         (if-let [~binding (cljs.core.async/<! chan#)]
           (do
             ~@body
             (recur))
           :done)))))

(comment

  (defmacro init-dev! []
    ;;(line-nr! true)
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

  )
