(ns klang.macros)
;; This requires Clojure 1.7 due to the use of transducers. But it can
;; be modified easily to use simple functions.
;; This macro file (.clj) is used in both, your production and dev environment.
;; You'll call them differently by using different :source-paths in your
;; leiningen configuration.

;; The function that is called for logging.
(def ^:dynamic *logger* 'klang.core/log!)
;; True if every macro call also attaches the environemtn (local bindings) to a
;; log call.
(def ^:dynamic *meta-env* false)
;; True if every macro call also attaches a console.trace
(def ^:dynamic *trace* false)
;; Hold the keywords of which metadata of &form should be added to a log! call
(def ^:dynamic *form-meta-keywords* #{})

;; Holds the 
(def ^:dynamic *ns-type-whitelist* [])
(def ^:dynamic *ns-type-blacklist* [])
;; If we get nil: What value to map it:
(def ^:dynamic *ns-type-default* true)

(defmacro clear-vars!
  "Convenience function to use while REPLing and figwheel'ing."
  []
  (alter-var-root #'*ns-type-whitelist* (fn[_] []))
  (alter-var-root #'*trace* (fn[_] false))
  (alter-var-root #'*meta-env* (fn[_] false))
  (alter-var-root #'*ns-type-blacklist* (fn[_] []))
  (alter-var-root #'*form-meta-keywords* (fn[_] #{}))
  nil)

(defmacro default-emit!
  "Sets the default wheater to emit or elide a log call. This is only used when
  neither the whitelist nor the blacklist matches anything. If both match then
  the blacklist will win."
  [bool]
  (alter-var-root #'*ns-type-default* (fn[_] (eval bool)))
  nil)

(defmacro add-whitelist!
  "Add a string to the whitelist. The string is later used (when calling log) to
  determine if the log message should be elided or will survive macro
  expansion. In the end a log call will consult ns-type-match? to determine what
  to do. Therefore see the example below.
  If a log call matches the whitelist it will be included.
  If a log call matches the whitelist and the blacklist it will be elided.
  If a log call matches neither it will be included.
  Example:
  (ns-type-match? \"x.y\" \"x.y\") ;; true
  (ns-type-match? \"x.y\" \"x.y.*\") ;; false
  (ns-type-match? \"yes.foo/INFO\" \"yes.*/(INFO|WARN)\") ;; true
  (ns-type-match? \"yes.foo/DEBG\" \"*/(DEBG|TRAC)\") ;; true"
  [& ns-type]
  (alter-var-root #'*ns-type-whitelist*
                  (fn[prev] (apply conj prev (eval `(vector ~@ns-type)))))
  nil)

(defmacro add-blacklist!
  "See docstring for add-whitelist!"
  [& ns-type]
  (alter-var-root #'*ns-type-blacklist*
                  (fn[prev] (apply conj prev (eval `(vector ~@ns-type)))))
  nil)

;; Stolen from timbre
(defn- ns-match?
  "(ns-type-match? \"x.y\" \"x.y\") ;; true
  (ns-type-match? \"x.y\" \"x.y.*\") ;; false
  (ns-type-match? \"yes.foo/INFO\" \"yes.*/(INFO|WARN)\") ;; true
  (ns-type-match? \"yes.foo/DEBG\" \"*/(DEBG|TRAC)\") ;; true"
  [ns match]
  (-> (str "^" (-> (str match) (.replace "." "\\.") (.replace "*" "(.*)")) "$")
      re-pattern (re-find (str ns)) boolean))

;; Stolen from timbre
(defn relevant-ns-type?
  "Returns:
  - true : if the namespace should be included to the log call.
  - false : if the namespace should be elided.
  - false : if namespace is in blacklist and whitelist
  - nil : indifferent, ie not specified by white nor blacklist"
  [ns-type]
  (if-some
      [bool (if (and (empty? *ns-type-whitelist*)
                     (empty? *ns-type-blacklist*))
              true ;; true since then the user likely wants all included
              (and
               ;; Need to check the blacklist first since it takes priority
               ;; and otherwise won't get evaluated due to lazyness of and
               (or (empty? *ns-type-blacklist*)
                   (not-any? (partial ns-match? ns-type) *ns-type-blacklist*))
               (or (empty? *ns-type-whitelist*)
                   (some (partial ns-match? ns-type) *ns-type-whitelist*))))]
    bool
    *ns-type-default*))

(defmacro logger!
  "Changes the logger that will be called to log-sym.
  Needs to be a fully qualified symbol.
  Example:
  (logger! 'myapp.logging/log!)"
  [log-sym]
  (alter-var-root #'*logger* (fn[_] (eval log-sym)))
  nil)

(defmacro add-form-env!
  "Adds the environment (the local bindings) to each log call."
  [bool]
  (alter-var-root #'*meta-env* (fn[_] (eval bool)))
  nil)

(defmacro add-trace!
  "Adds a console.trace() to each log call as meta data.."
  [bool]
  (alter-var-root #'*trace* (fn[_] (eval bool)))
  nil)

(defmacro add-form-meta!
  "You can add &form metadata whenever you call log!. Sensible metadata:
  :file - The filename form where you call log!
  :line - The line number form where you call log!"
  [& meta-keywords]
  (alter-var-root #'*form-meta-keywords*
                  (fn[prev] (apply conj prev (eval `(hash-set ~@meta-keywords)))))
  nil)

;; Stolen from lazytest and adapted for cljs
(defn- local-bindings
  "Returns a map of the names of local bindings to their values."
  [env]
  (into {} (for [sym (keys env)] [`'~sym sym])))

(defn- merge-env
  "Attaches the local bindings to the :env of m if the config *meta-env* says
  so."
  [m env]
  (if *meta-env*
    (assoc m :env (local-bindings (:locals env)))
    m))

(defn- trace-data
  "Attaches the local bindings to the :env of m if the config *meta-env* says
  so. NOT WORKING."
  [m]
  (if *trace*
    ;;(assoc m :trace `(js/console.trace))
    ;;(assoc m :trace `(try (goog.debug.Error.) (catch :default ~'err ~'err)))
    ;;(assoc m :trace `(.getStacktrace goog.debug))
    m
    m))

(defn- log'
  "Helper function to emit the actual log function call. Will attach meta data
  according to the config."
  [form env ns-type msg]
  (let [meta-d (select-keys (meta form) *form-meta-keywords*)
        meta-d (merge-env meta-d env)
        ;;trace-d (trace-data)
        meta-d (trace-data meta-d)
        meta-s `(with-meta 'klang.core/meta-data ~meta-d)]
    (when (relevant-ns-type? (apply str (rest (str ns-type))))
      (if (not-empty meta-d)
        ;; We need some method to pass in the meta data: We cannot assoc the
        ;; metadata to the function or keyword so we have to pass it in as an
        ;; argument which we have to remove again
        `(~*logger* ~ns-type ~meta-s ~@msg)
        `(~*logger* ~ns-type ~@msg)))))

(defmacro log!
  "Logs to the main logger. Example:
  (log! ::INFO :my nil \"debug message\")"
  [ns-type & msg]
  (log' &form &env ns-type msg))

(defn- ns-kw
  "Helper function to turn a string like \"foo\" into a namespaced keyword like
  ::foo."
  [kw]
  (keyword (name (ns-name *ns*)) kw))

(defn- log-type-str
  "Helper function that takes a string keyword and turns it into a namespaced
  keyword of the current namespace."
  [form env type msg]
  (log' form env (ns-kw type) msg))

(defmacro trac! [& msg]
  (log-type-str &form &env "TRAC" msg))

(defmacro debg! [& msg]
  (log-type-str &form &env "DEBG" msg))

(defmacro info! [& msg]
  (log-type-str &form &env "INFO" msg))

(defmacro warn! [& msg]
  (log-type-str &form &env "WARN" msg))

(defmacro erro! [& msg]
  (log-type-str &form &env "ERRO" msg))

(defmacro crit! [& msg]
  (log-type-str &form &env "CRIT" msg))

(defmacro fata! [& msg]
  (log-type-str &form &env "FATA" msg))

(defmacro env!
  "Logs your local bindings as data (not attached as meta data). Optionally
  takes some message data."
  [& msg]
  (log-type-str &form &env "TRAC"
                (concat msg [:env]
                        (list ;; concatting the map would turn it into a vector
                         (local-bindings (:locals &env))))))

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RDD:
(comment


  )
