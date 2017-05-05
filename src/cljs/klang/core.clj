(ns klang.core
  "
   # Configuration

   1. Java define: -Dklang.configurer=your.ns/some-fn

   That gets called and should return a config (see config here)

   2. Java defines. All strings will be read-string'ed.
   - klang.logger-fn=klang.core/log!
   - klang.form-meta=\"[:line :file]\"
   - klang.compact-ns=false
   - klang.meta-env=false
   - klang.trace=false
   - klang.default-emit=true
   - klang.whitelist=\"(ERRO|FATA|WARN)\"
   - klang.blacklist=\"TRAC\"

   3. Config file:
   - klang.config-file=klang-prod.edn"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))


(defonce config
         (atom {:logger-fn 'klang.core/add-log!
                ;; Hold the keywords of which metadata of &form should be added to a log! call
                ;; Usually :file & :line are available
                :form-meta #{}
                :compact-ns? false
                ;; True if every macro call also attaches the environment (local bindings) to a
                ;; log call.
                :meta-env? true
                :trace? true
                :default-emit? true
                :whitelist ""
                :blacklist ""}))

(defn safe-read-string
  [s]
  (try (read-string s)
       (catch RuntimeException _ nil)))

(defn config-map-from-configurer
  "Calls the configurer and gets the config map from it."
  []
  (when-some [prop-val (System/getProperty "klang.configurer")]
    (if-some [sym (safe-read-string prop-val)]
      (do (require (symbol (namespace sym)))
          (if-some [f (resolve sym)]
            (f)
            (prn "ERROR: klang.configurer set but could not resolve")))
      (prn "ERROR: klang.configurer set but could not read"))))
#_(config-map-from-configurer)

(defn config-map-from-props
  "Assembles a config map from the various system properties."
  []
  (let [config->prop {:logger-fn "logger-fn"
                      :form-meta "form-meta"
                      :compact-ns? "compact-ns"
                      :meta-env? "meta-env"
                      :trace? "trace"
                      :default-emit? "default-emit"
                      :whitelist "whitelist"
                      :blacklist "blacklist"}
        dont-read-string #{:whitelist :blacklist}]
    (reduce-kv
      (fn [m k prop-name]
        (let [prop (System/getProperty (str "klang." prop-name))]
          (if prop
            (assoc m k (if (dont-read-string k)
                         prop
                         (safe-read-string prop)))
            m)))
      {}
      config->prop)))

(defn config-map-from-file
  []
  (some-> (System/getProperty "klang.config-file")
          io/resource
          slurp
          read-string))

(defonce init-config
         (let [cfg (swap! config merge
                          (config-map-from-file)
                          (config-map-from-configurer)
                          (config-map-from-props))]
           (prn "Klang.core logging config: " cfg)
           cfg))

(defn- ns-match?
  "(ns-type-match? \"x.y\" \"x.y\") ;; true
  (ns-type-match? \"x.y\" \"x.y.*\") ;; false
  (ns-type-match? \"yes.foo/INFO\" \"yes.*(INFO|WARN)\") ;; true
  (ns-type-match? \"yes.foo/DEBG\" \"*(DEBG|TRAC)\") ;; true"
  [ns match]
  (-> (str "^" (-> (str match) (.replace "." "\\.") (.replace "*" "(.*)")) "$")
      re-pattern (re-find (str ns)) boolean))

(defn relevant-ns-type?
  "Returns:
  - true : if the namespace should be included to the log call.
  - false : if the namespace should be elided.
  - false : if namespace is in blacklist and whitelist
  - nil : indifferent, ie not specified by white nor blacklist"
  [ns-type]
  (let [{:keys [whitelist blacklist default-emit?]} @config]
    (if-some [res (if (and (empty? whitelist)
                           (empty? blacklist))
                    default-emit?
                    (and
                      ;; Need to check the blacklist first since it takes priority
                      ;; and otherwise won't get evaluated due to lazyness of and
                      (or (empty? blacklist)
                          (not (ns-match? ns-type blacklist)))
                      (or (empty? whitelist)
                          (ns-match? ns-type whitelist))))]
      res
      default-emit?)))

(defn- local-bindings
  "Returns a map of the names of local bindings to their values."
  [env]
  (into {} (for [sym (keys env)] [`'~sym sym])))

(defn- merge-env
  "Attaches the local bindings to the :env of m if the config *meta-env* says
  so."
  [m env]
  (if (:meta-env? @config)
    (assoc m :env (local-bindings (:locals env)))
    m))

(defn- trace-data
  "Attaches the local bindings to the :env of m if the config *meta-env* says
  so. NOT WORKING."
  [m]
  (if (:trace? @config)
    (assoc m :trace `(try (throw (js/Error.)) (catch :default e# e#)))
    m))

(defn shorten-dotted
  "foo.bar.baz -> f.b.b"
  [s]
  (str/join \. (mapv first (str/split s #"[.]"))))

(defn- log'
  "Helper function to emit the actual log function call. Will attach meta data
  according to the config."
  [form env severity args]
  (let [ns (name (ns-name *ns*))
        ;; Do the relevant check before we shorten the namespace:
        relevant? (relevant-ns-type? (str ns "/" severity))
        ns (cond-> ns (:compact-ns? @config) shorten-dotted)
        meta-d (select-keys (meta form) (:form-meta @config))
        meta-d (merge-env meta-d env)
        meta-d (trace-data meta-d)
        meta-s `{:klang.core/meta-data ~meta-d}]
    (when relevant?
      (if (not-empty meta-d)
        ;; We need some method to pass in the meta data: We cannot assoc the
        ;; metadata to the function or keyword so we have to pass it in as an
        ;; argument which we have to remove again
        `(~(:logger-fn @config) ~ns ~severity ~meta-s ~@args)
        `(~(:logger-fn @config) ~ns ~severity ~@args)))))

(defn- log-type-str
  "Helper function that takes a string keyword and turns it into a namespaced
  keyword of the current namespace."
  [form env type msg]
  (log' form env type msg))

(defmacro log!
  "Logs to the main logger. Example:

   (log! ::INFO :my nil \"debug message\")
   (log! \"INFO\" :\"debug message\")"
  [ns-type & args]
  (log' &form &env (name ns-type) args))

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
