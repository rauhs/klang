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
;;(def ns-whitelist)
;;(def ns-blacklist)
;;(def type-whitelist)
;;(def type-blacklist)


;; The function that is called for logging.
(def ^:dynamic *logger* 'klang.core/log!)

(defmacro logger!
  "Changes the logger that will be called to log-sym.
  Needs to be a fully qualified symbol.
  Example:
  (logger! 'myapp.logging/log!)"
  [log-sym]
  (alter-var-root #'*logger* (fn[_] (eval log-sym)))
  nil)

;; The function that is called for logging.
(def ^:dynamic *meta-env* false)

(defmacro add-form-env!
  "Adds the environment (the local bindings) to each log call."
  [bool]
  (alter-var-root #'*meta-env* (fn[_] (eval bool)))
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

(defn single-transduce
  "Takes a transducer (xform) and an item and applies the transducer to the
  singe element and returnes the transduced item. Note: No reducing is
  involved. Returns nil if there was no result."
  [xform x]
  ((xform (fn[_ r] r)) nil x))

(defn- log'
  "Helper function to emit the actual log function call. Will attach meta data
  according to the config."
  [form env ns_type msg]
  (let [meta-d (select-keys (meta form) *form-meta-keywords*)
        meta-d (merge-env meta-d env)
        meta-s `(with-meta 'klang.core/meta-data ~meta-d)]
    (when-let [nslv-td (single-transduce (apply comp @xforms) ns_type)]
      (if (not-empty meta-d)
        ;; We need some method to pass in the meta data: We cannot assoc the
        ;; metadata to the function or keyword so we have to pass it in as an
        ;; argument which we have to remove again
        `(~*logger* ~nslv-td ~meta-s ~@msg)
        `(~*logger* ~nslv-td ~@msg)))))

(defmacro log!
  "Logs to the main logger. Example:
  (log! ::INFO :my nil \"debug message\")"
  [ns_type & msg]
  (log' &form &env (single-transduce (apply comp @xforms) ns_type) msg))

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
