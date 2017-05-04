(ns klang.core
  "

   # TODO:
   Also highlight all namespaces with a smart method?
   For instance all parents vs childs have stronger colors...
   How to define a tree of colors such that they're similar yet different?
   Sounds like a coloring problem??
   Actually sounds more like locality sensitve hashing algorithm!
   Easiest LSH:
   Just explode each '.' and project it into some numbers.
   Actually probably a weighted projection that gives more weights to the
   leftmost parts of the namespace.
   What color space to use?

   Note: Some of the code here is written like this to get get 100% DCE'd"
  (:require
    [cljsjs.highlight]
    [cljsjs.highlight.langs.clojure]
    [goog.events :as gevents]
    [goog.object :as gobj]
    [goog.string :as gstring]
    [goog.string.format]
    [goog.style :as gstyle])
  (:import
    goog.ui.KeyboardShortcutHandler))

(defonce db (atom {:showing? true
                   :max-logs 500
                   :search ""
                   :logs #js[]
                   :frozen-at nil}))

(defn !!
  [& args]
  (apply swap! db args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TINY React layer
(defn dom-el
  "Ensures there is a div element in the body and returns it."
  []
  (let [domid "__klang2__id__"
        domel (js/document.getElementById domid)]
    (or domel
        (let [newdom (doto (js/document.createElement "div")
                       (.setAttribute "id" domid))]
          (js/document.body.appendChild newdom)
          newdom))))

(defn add-array
  [ary xs]
  (reduce (fn [ary x] (.push ary x) ary)
          ary xs)
  ary)

(defn h
  "Helper for creating dom elements."
  [tag props & children]
  (.apply js/React.createElement js/React.createElement
          (add-array #js[tag props] children)))

(defn possibly-set-lifecycle!
  "This is all done for performance... Smaller and more used functions can easier get optimized."
  [spec name f]
  (when-not (empty? f)
    (gobj/set spec name f))
  nil)

(defn build-class
  "The render function will always be called with 1 arg, the rum state.
   It should return [dom rum-state]."
  [render lcm]
  (let [constr (fn [props]
                 (this-as this
                   ;; Call parent constructor:
                   (.call js/React.Component this props)
                   (set! (.-props this) props)
                   (set! (.-state this) #js{:comp this})
                   this))
        should-update (aget lcm "should-update") ;; old-state state -> boolean
        will-unmount (aget lcm "will-unmount") ;; state -> state
        will-mount (aget lcm "will-mount") ;; state -> state
        will-update (aget lcm "will-update") ;; state -> state
        did-update (aget lcm "did-update") ;; state -> state
        did-mount (aget lcm "did-mount") ;; state -> state
        class-props (aget lcm "class-properties")] ;; custom properties+methods
    (goog/inherits constr js/React.Component)
    ;; Displayname gets set on the constructor itself:
    (gobj/set constr "displayName" (aget lcm "name"))
    (let [proto (.-prototype constr)]
      (gobj/extend proto #js{:render (fn []
                                       (this-as this
                                         (apply render (aget (.. this -props) "props"))))})
      (possibly-set-lifecycle! proto "componentWillMount" will-mount)
      (possibly-set-lifecycle! proto "componentDidMount" did-mount)
      (possibly-set-lifecycle! proto "componentDidUpdate" did-update)
      (possibly-set-lifecycle! proto "componentWillUnmount" will-unmount)
      (when (some? will-update)
        (gobj/set proto "componentWillUpdate" will-update))
      (when (some? should-update)
        (gobj/set proto "shouldComponentUpdate" should-update))
      (when (some? class-props)
        (when-some [cp (clj->js (apply merge class-props))]
          (gobj/extend proto cp)))
      constr)))

(defn component
  [lcm render]
  (let [cls (build-class render lcm)
        key-fn (aget lcm "key-fn")]
    (fn component [& props]
      (let [react-props (if (some? key-fn)
                          #js{:props props
                              :key (apply key-fn props)}
                          #js{:props props})]
        (js/React.createElement cls react-props)))))

(defn mount
  "Add component to the DOM tree. Idempotent. Subsequent mounts will just update component"
  [component node]
  (js/ReactDOM.render component node))

(defn unmount
  "Removes component from the DOM tree"
  [node]
  (js/ReactDOM.unmountComponentAtNode node))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce id-counter 0)
(defn gens
  "Generates a new log id."
  []
  (set! id-counter (inc id-counter))
  id-counter)

(defn toggle-freeze
  "Freezes the UI. Toggle on no param"
  []
  (!! update :frozen-at (fn [idx]
                          (if (some? idx)
                            nil
                            id-counter))))

(defn show!
  "Makes the overlay show/hide. Toggle on no param"
  ([]
   (!! update :showing? not))
  ([tf]
   (!! assoc :showing? tf)))

(defn parent?
  "Return true if the namespace p is a parent of c. Expects two string"
  [p c]
  (let [pd (str p ".")]
    (= (subs c 0 (count pd)) pd)))

(defn self-or-parent?
  "Return true if the namespace p==c or p is a parent of c. Expects two string"
  [p c]
  (or (= c p)
      (parent? p c)))

(defn format-time
  [d]
  (when (instance? js/Date d)
    (.slice (aget (.split (.toJSON d) "T") 1) 0 -1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core rendering the logs
(defn dump-to-console!
  "Takes a log event and dumps all kinds of info about it to the developer
  console. Works under chrome. Probably also under firefox."
  [lg-ev]
  (js/console.group
    (gstring/format ;; firefox can't deal with format style stuff
      "%s%s%s%s -- %s"
      (:ns lg-ev)
      (if (empty? (:ns lg-ev)) "" "/")
      (:type lg-ev)
      ;; The line number if we have one:
      (if-let [lnum (:line (:meta lg-ev))] (str ":" lnum) "")
      ;; We can't dered DB here to get the formatter
      ;; or reagent will re-render everything always
      (format-time (:time lg-ev))))
  ;; The meta data contains things like filename and line number of the original
  ;; log call. It might also catch the local bindings so we print them here.
  (when-some [meta (:meta lg-ev)]
    (.group js/console "Meta Data")
    ;;(some->> (:line meta) (.log js/console "Line-num: %d"))
    (some->> (:file meta) (.log js/console "Filename: %s"))
    (when-some [env (seq (:env meta))]
      ;; 3rd level nested group. Oh yeah
      (js/console.group "Local Bindings")
      (doseq [[k v] env]
        (.log js/console "%s : %o" (pr-str k) v))
      (js/console.groupEnd))
    ;; The rest of the meta info, not sure if this should ever happen:
    (when-some [meta' (seq (dissoc meta :file :line :env))]
      (doseq [[k v] meta']
        (js/console.log "%s : %o" (pr-str k) v)))
    (js/console.groupEnd))
  ;; console.dir firefox & chrome only?
  ;; %o calls either .dir() or .dirxml() if it's a DOM node
  ;; This means we get a real string and a real DOM node into
  ;; our console. Probably better than always calling dir
  (doseq [v (:msg lg-ev)]
    ;; truncate adds the elippsis...
    (js/console.log "%O --- %s" v (-> v pr-str (gstring/truncate 20))))
  (js/console.groupEnd))

(defn severity->color
  "Returns a color for the given severity
   http://www.w3schools.com/cssref/css_colornames.asp"
  [severity]
  (case severity
    "DEBG" "gray"
    "TRAC" "darkgray"
    "INFO" "steelblue"
    "ERRO" "darkred"
    "CRIT" "red"
    "FATA" "firebrick"
    "WARN" "orange"
    nil))

(defn hl-clj-str
  "Returns a string containing HTML that highlights the message. Takes a string
  of clojure syntax. Such as map, set etc.
  Ex:
  (hl-clj-str \"{:foo :bar}\")"
  [msg]
  (.-value (.highlight js/hljs "clojure" msg true)))

(defn msg->str
  "Converts a message to a string."
  [msg]
  (let [s (pr-str msg)]
    ;; Remove closing and opening bracket of the vectorized message:
    (.substr s 1 (- (.-length s) 2))))

(defn render-msg
  [msg]
  (h "span" #js{"dangerouslySetInnerHTML" #js{"__html" (hl-clj-str (msg->str msg))}}))

(def render-log-event
  "Renders a single log message."
  (component
    #js{:name "LogEvent"
        :key-fn (fn [props] (:id props))
        :should-update #(-> false)}
    (fn [{:keys [time ns type msg] :as lg-ev}]
      (h "li" #js{:style #js{:listStyleType "none"}}
         (format-time time)
         " "
         ns
         (when-not (empty? ns) "/")
         (h "span" #js{:style #js{:color (severity->color type)}} type)
         " "
         (h "span" #js{:style #js{:cursor "pointer"}
                       :onClick #(dump-to-console! lg-ev)}
            (render-msg msg))))))

(defn search-filter-fn
  "Returns a transducer that filters given the log messages according to the
  search term given in the database for the current active tab.
  Does a full text search on time, namespace, type and message.
  The format is like:
  11:28:27.793 my.ns/INFO [\"Log msg 0\"]"
  [search]
  (let [search (.replace search " " ".*")
        re (try (js/RegExp. search "i")
                (catch :default _ (js/RegExp "")))]
    (fn [lg-ev]
      (let [log-str (.join
                      #js[(format-time (:time lg-ev))
                          " "
                          (:ns lg-ev)
                          (when-not (empty? (:ns lg-ev)) "/")
                          (:type lg-ev) " "
                          (str (:msg lg-ev))]
                      "")
            test (.test re log-str)]
        ;; The .test might be undefined if the search str is empty?
        (if (undefined? test)
          true ;; Include all of them then
          test)))))

(defn render-logs
  "Renders an array of log messages."
  [logs]
  (h "ul" #js{:style #js{:padding ".5em"
                         :margin "0em"
                         :lineHeight "1.06em"}}
     ;; Fast array reverse:
     (let [frozen-idx (:frozen-at @db)
           last-to-start (if (some? frozen-idx)
                           frozen-idx
                           (count logs))
           search (not-empty (:search @db))
           filter-fn (if search
                       (search-filter-fn search)
                       identity)

           aout #js[]]
       (dotimes [i last-to-start]
         (let [lg-ev (aget logs (- last-to-start i 1))]
           (when ^boolean (filter-fn lg-ev)
             (.push aout (render-log-event lg-ev)))))
       aout)))


(defn- render-overlay
  "Renders the entire log message overlay in a div when :showing? is true."
  []
  (h "div" #js{:style #js{:display (if (:showing? @db) "block" "none")
                          :position "fixed"
                          :left "6px"
                          :top "6px"
                          :width "calc(100% - 12px)"
                          :height "calc(100% - 12px)"
                          :fontFamily "monospace"
                          :zIndex 9922 ;; fighweel has 10k
                          :fontSize "90%"}}
     (h "div" #js{:style #js{:height "28px"
                             :width "calc(100% - 12px)"
                             :justifyContent "center"
                             :display "flex"}}
        (h "input" #js{:style #js{:background "#000"
                                  :color "white"
                                  :width "350px"}
                       :onChange (fn [e] (!! assoc :search (.. e -target -value)))
                       :type "text"
                       :defaultValue (:search @db "")
                       :placeholder "Search"})
        (h "button" #js{:style #js{:cursor "pointer"
                                   :color (if (:frozen-at @db) "orange" "green")}
                        :onClick #(toggle-freeze)}
           (if (:frozen-at @db) "Thaw" "Freeze")))
     (h "div" #js{:style #js{:width "calc(100% - 12px)"
                             :height "calc(100% - 40px)"
                             :color "#fff"
                             :padding 0
                             :outline "none"
                             :opacity 0.9
                             :background "#000"
                             :zIndex 9922 ;; fighweel has 10k
                             :position "fixed"
                             :overflowY "auto"}}
        (render-logs (:logs @db)))))

;; Taken from highlight-js
(defn css-molokai
  []
  ".hljs {
  display: block;
  overflow-x: auto;
  padding: 0.2em;
  background: #23241f;
  -webkit-text-size-adjust: none;
}
.hljs,.hljs-tag,.css .hljs-rule,.css .hljs-value,.css .hljs-function .hljs-preprocessor,
.hljs-pragma {
  color: #f8f8f2;
}
.hljs-strongemphasis,.hljs-strong,.hljs-emphasis {
  color: #a8a8a2;
}
.hljs-bullet,.hljs-blockquote,.hljs-horizontal_rule,.hljs-number,.hljs-regexp,
.alias .hljs-keyword,.hljs-literal,.hljs-hexcolor {
  color: #ae81ff;
}
.hljs-tag .hljs-value,.hljs-code,.hljs-title,.css .hljs-class,
.hljs-class .hljs-title:last-child {
  color: #a6e22e;
}
.hljs-link_url {
  font-size: 80%;
}
.hljs-strong,.hljs-strongemphasis {
  font-weight: bold;
}
.hljs-emphasis,.hljs-strongemphasis,.hljs-class .hljs-title:last-child,.hljs-typename {
  font-style: italic;
}
.hljs-keyword,.hljs-function,.hljs-change,.hljs-winutils,.hljs-flow,.hljs-header,.hljs-attribute,
.hljs-symbol,.hljs-symbol .hljs-string,.hljs-tag .hljs-title,.hljs-value,.alias .hljs-keyword:first-child,
.css .hljs-tag,.css .unit,.css .hljs-important {
  color: #f92672;
}
.hljs-function .hljs-keyword,.hljs-class .hljs-keyword:first-child,.hljs-aspect .hljs-keyword:first-child,
.hljs-constant,.hljs-typename,.hljs-name,.css .hljs-attribute {
  color: #66d9ef;
}
.hljs-variable,.hljs-params,.hljs-class .hljs-title,.hljs-aspect .hljs-title {
  color: #f8f8f2;
}
.hljs-string,.hljs-subst,.hljs-type,.hljs-built_in,.hljs-attr_selector,.hljs-pseudo,.hljs-addition,
.hljs-stream,.hljs-envvar,.hljs-prompt,.hljs-link_label,.hljs-link_url {
  color: #e6db74;
}
.hljs-comment,.hljs-javadoc,.hljs-annotation,.hljs-decorator,.hljs-pi,.hljs-doctype,.hljs-deletion,
.hljs-shebang {
  color: #75715e;
}")


(defn install-shortcut!
  "Installs a Keyboard Shortcut handler that show/hide the log overlay.
   Call the return function to unregister."
  [shortcut]
  ;; If previous one exist just unregister it:
  (when-some [prev (:shortcut-keys @db)]
    (prev))
  (let [handler (KeyboardShortcutHandler. js/window)]
    (.registerShortcut handler "klang.toggle" shortcut)
    (gevents/listen
      handler
      KeyboardShortcutHandler.EventType.SHORTCUT_TRIGGERED
      (fn [e] (show!)))
    (js/console.info "Klang: Keyboard shortcut installed:" shortcut)
    (!! assoc :shortcut-keys #(.unregisterShortcut handler shortcut))))

(defn set-max-logs!
  "Only keep the last n logs. If nil: No truncating."
  [n]
  (!! assoc :max-logs n))

(def ^:const rAF js/window.requestAnimationFrame)
(def scheduled? false)

(defn request-rerender!
  []
  (when-not scheduled?
    (set! scheduled? true)
    (rAF
      (fn []
        (mount (render-overlay) (dom-el))
        (set! scheduled? false)))))

(def ensure-klang-init
  "TODO: Check if this get's DCE'd: (It should)"
  (delay
    (when-not (exists? js/React)
      (js/console.error "Klang: Can't find React. Load by yourself beforehand."))
    (install-shortcut! "m")
    (set-max-logs! 2000)
    (add-watch db :rerender request-rerender!)
    (gstyle/installStyles (css-molokai))))

(defn possibly-truncate
  [db]
  (when-some [num (:max-logs db)]
    (let [logs (:logs db)]
      (.splice logs 0 (- (alength logs) num)))))

(defn add-log!
  "This is the main log functions:
  - ns - string
  - severity - string, like \"INFO\" or \"WARN\"
  - msg0 - If the map {::meta-data {...}} attaches this to the msg
    Otherwise the first message"
  [ns severity msg0 & msg]
  (deref ensure-klang-init)
  (let [db @db
        meta (::meta-data msg0)
        msg (if (some? meta) (vec msg) (into [msg0] msg))]
    (.push (:logs db) {:time (js/Date.)
                       :id (gens)
                       :ns (str ns)
                       :type (name severity)
                       :meta meta ;; Potentially nil
                       :msg msg})
    (possibly-truncate db)
    (request-rerender!)))

(defn clear!
  "Clears all logs"
  []
  (set! (:logs @db) -length 0)
  (request-rerender!))

