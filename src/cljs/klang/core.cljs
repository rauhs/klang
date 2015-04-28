(ns klang.core
  (:require-macros
   [reagent.ratom :refer [reaction] :as re]
   [klang.macros :refer [dochan] :as macros]
   [cljs.core.async.macros :refer [go-loop go]])
  (:require
   [reagent.core :as r :refer [atom]]
   [clojure.string :as string]
   [cljsjs.highlight]
   [cljsjs.highlight.langs.clojure]
   [cljs.core.async :refer [put! chan sliding-buffer <! mult
                            tap close! pub sub timeout take!]]
   ;; Google Closure
   [goog.dom :as dom]
   [goog.string :as gstring]
   [goog.style :as gstyle])
  (:import 
   goog.date.DateTime
   goog.i18n.DateTimeFormat
   goog.ui.KeyboardShortcutHandler))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Doc:

;; Limitation:
;; The transducers registerd for rendering (via register-transducers) will be
;; applied one by one to each arriving log message. This mean it's basically
;; limited to map & filter.
;; This is could be changed if we setup all transducers first and then start the
;; mult channel hookup that can transduce the items

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dev doc:
;; TODO:
;; Clicking on a namespace should create a transducer with the same key and
;; filter only that ns
;; TODO:
;; Remove cljs-time dependency. I don't really use the functionality
;; Debounce the searching transducer
;; Add line# and filename as meta data when logging with macros.
;; Display them only in the js console when clicking on data

;; If anything goes wrong in register-transducer and the render functions
;; figwheel goes a little crazy and we need to reload the page!


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global stuff / defs

(defonce
  ^{:doc "Holds a global instance of a db in case we want to use this
 library in single user mode. Ie only one logging system. Will be true for 99%
 of all users."}
  *db* (atom {}))

(declare time-formatter)

;; Config for display/tabs of the log viewer
(defn new-db
  "Creates a new logger instance database."
  []
  {
   ;; Where ALL logs will be pushed. The raw log events including time, ns,
   ;; type, msg. DO NEVER LISTEN TO THIS!!
   :log-pub-ch nil              
   ;; Tap into the log-in-ch with mult distributes log events to multiple other
   ;; channels
   :log-sub-ch nil
   ;; The actions pub/sub channel. Again: the pub is actually a mult!
   :actions-pub-ch nil
   :actions-sub-ch nil
   ;;;;;;;;;;;;;; View stuff
   ;; If we're displaying the overlay or not
   :showing false
   ;; THe amount of logs we will buffer while frozen before discarding
   :freeze-buffer 1000
   ;; The current tab
   :showing-tab :all
   ;; Date time formatter
   ;; memfn not working?
   :time-formatter time-formatter
   ;; User defined tabs state
   :tabs (sorted-map
          ;; The main tab holding all logs
          :all {:transducers [identity] ;; basically the filter
                ;; The full text search box content
                ;; Don't nil this
                :search ""
                ;; Should this be a list or vector?
                :logs [] ;; The logs for this tab,
                ;; The scroll position: Pixels hidden on top
                :scroll-top 0}
          )
   ;; The transducers that add data to the logs. For instance by adding
   ;; render function which change the color
   :transducers {}
   ;; True if we pause the channel an don't update the UI
   :frozen false
   ;; channel that tells us if we're going freeze
   :freeze-ch nil
   ;; The actual logs that we render, continuously updated unless frozen.
   ;; If we freeze we won't pull from the mult channel and buffer instead
   ;; Before they get into this array they will be transduced!
   :logs [] ;; List or vector?
   ;; Stuff for rendering part of the log messages depending on the scrolling
   ;; position
   ;; sp == scrolling position
   ;; How many logs to skip (since we're somewhere scrolled down)
   :sp-skip 0
   ;; How many logs to actually render each time.
   ;; For my laptop: ~1sec render time per 100 elements :(
   :sp-take 100
   })


;; Warning: I can't do CSS to save my life
(def ^:const css
  {;; The outer popup div
   :div-outer {:font-family "monospace"
               :font-size "90%"
               }
   ;; The div holding the log messages
   :div-logs {:left "calc(8em + 1em)"
              :width "calc(100% - 2em - 8em)"
              :top "1em"
              :height "calc(100% - 2em)"
              :z-index 87509
              :color "#fff"
              :background "#000"
              :padding 0
              :opacity .9
              :outline "none"  ;; ??
              :position "fixed"
              ;;:margin-top "2em" ;; For the tabs, or tabs to the right?
              :overflow-y "auto" ;; So we can scroll the logs 
              }
   ;; The side nav for choosing which tab etc
   :nav
   {:left "calc(.5em)"
    :width "calc(8em)"
    :top "1em"
    :height "calc(100% - 2em)"
    :z-index 87509
    :color "#fff"
    :background "#000"
    :opacity .9
    :outline "none"  ;; ??
    :position "fixed"
    ;;:margin-top "2em" ;; For the tabs, or tabs to the right?
    :overflow-y "auto" ;; So we can scroll the logs 
    }})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
(defn log-console
  "Logs a string to the console"
  [s]
  ;; Don't accept db in this case. If we shall be quiet we're in single mode
  ;; anyways very likely
  (js/console.log (str s))
  s)

;; Watch out for:
;; (is-parent "abcd.e" "abcd.ef.")
;; Should be false!
(defn parent?
  "Return true if the namespace p is a parent of c. Expects two string"
  [^String p ^String c]
  ;;{:pre [(and (string? p) (string? c))]}
  (let [pd (str p ".")]
    (= (subs c 0 (count pd)) pd)))

(defn self-or-parent?
  "Return true if the namespace p==c or p is a parent of c. Expects two string"
  [^String p ^String c]
  (or (= c p) 
      (parent? p c)))

(def random-uuid gstring/getRandomString)

(defn time-formatter
  [time]
  (.format (goog.i18n.DateTimeFormat. "HH:mm:ss.SSS") time))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Declares
(declare action!)
(declare valid-log?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ensure-uuid
  "Adds a :uuid field to the map if there isn't one yet."
  [msg]
  (if (:uuid msg)
    msg
    (assoc msg :uuid (random-uuid))))

(defn ensure-msg-vec
  "Ensures the :msg is a vector. Can happen that it's not if raw-log was called"
  [lg-ev]
  (if (vector? (:msg lg-ev))
    lg-ev
    (update-in lg-ev [:msg] vector)))

(defn ensure-timed
  "Adds a :time field to the map if there isn't one yet."
  [msg]
  (if (:time msg)
    msg
    (assoc msg :time (goog.date.DateTime.))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core rendering the logs

(defn log-dump-console
  "Takes a log event and dumps all kinds of info about it to the developer
  console. Works under chrome. Probably also under firefox."
  [lg-ev]
  (.group js/console
          "%s%s%s -- %s"
          (:ns lg-ev)
          (if (empty? (:ns lg-ev)) "" "/")
          (name (:type lg-ev))
          ;; We can't dered DB here to get the formatter
          ;; or reagent will re-render everything always
          (time-formatter (:time lg-ev)))
  ;; The meta data contains things like filename and line number of the original
  ;; log call. It might also catch the local bindings so we print them here.
  (when-let [meta (:meta lg-ev)]
    (.group js/console "Meta Data")
    (some->> (:line meta) (.log js/console "Line-num: %d"))
    (some->> (:file meta) (.log js/console "Filename: %s"))
    (when-let [env (seq (:env meta))]
      ;; 3rd level nested group. Oh yeah
      (.group js/console "Local Bindings")
      (doseq [[k v] env]
        (.log js/console "%s : %o" (pr-str k) v))
      (.groupEnd js/console))
    ;; The rest of the meta info, not sure if this should ever happen:
    (when-let [meta' (seq (dissoc meta :file :line :env))]
      (doseq [[k v] meta']
        (js/console.log "%s : %o" (pr-str k) v)))
    (.groupEnd js/console))
  ;; console.dir firefox & chrome only?
  ;;(mapv #(js/console.dir %) (:msg lg-ev))
  ;; %o calls either .dir() or .dirxml() if it's a DOM node
  ;; This means we get a real string and a real DOM node into
  ;; our console. Probably better than always calling dir
  (doseq [v (:msg lg-ev)]
    ;; truncate adds the elippsis...
    (js/console.log "%O --- %s" v (-> v pr-str (gstring/truncate 20))))
  #_(mapv #(js/console.log "%o" %) (:msg lg-ev))
  (.groupEnd js/console))

;; Renders a line:
;; TIME NS/TYPE Msg
;; TODO: We can also pre-render an element when it arrives.
(defn render-msg
  "Renders a single log message."
  [lg-ev]
  {:pre [(valid-log? lg-ev)]}
  ;; Caching only improves rendering by like 10% :(
  (or
   (::cached-render lg-ev)
   [:li {:style {:list-style-type "none"}}
    ;; TODO: Could also accept :render :log-ev which renders the entire lg-ev?
    ;; TODO: Refactor into a let-fn and func calls
    (if-let [rndr (get-in lg-ev [:render :time])]
      ;; Still need to compose all the given render functions here
      [(apply comp rndr) (:time lg-ev)]
      (str (:time lg-ev)))
    " "
    (if-let [rndr (get-in lg-ev [:render :ns])]
      [(apply comp rndr) (:ns lg-ev)]
      (:ns lg-ev))
    (when-not (empty? (:ns lg-ev)) "/")
    (if-let [rndr (get-in lg-ev [:render :type])]
      [(apply comp rndr) (:type lg-ev)]
      (name (:type lg-ev)))
    " "
    [:span
     {:style {:cursor "pointer"}
      :on-click (partial log-dump-console lg-ev)}
     ;;:on-click (fn[_] (partial) log-dump-console lg-ev)}
     (if-let [rndr (get-in lg-ev [:render :msg])]
       [(apply comp rndr) (:msg lg-ev)]
       (str (:msg lg-ev)))]]))

;; For rendering only the parts we see in a list: we have to know OR
;; estimate:
;; NUMBERS:
;; * Total #el: n -- easy
;; * #el visible in the div. -- harder, needs to be estimated due to
;;   multi line logs. Def changes over time since we might start with
;;   a lot of multi line logs and also the js console will open and
;;   resize it. So re-calc continuously: k
;; Given a scroll position:
;; * How many elem above: p
;; * How many elem below: q
;; p + q + k = n ;; known: n, estimated: choose 2 of p,q,k :(
;; 
;; DIMENSIONS:
;; are all estimated b/c we don't know the size of a log message:
;; * Total height of the (inner) div as it would have if we put all
;;   log message in there
;; * Space below/above scroll position.
;; * Actual height we can use to show logs: Given by the user: H
;;
;; Some math:
;; - p, q, k, n ~~ T, B, VH, OH
;; Given: n, VH, scroll position and thus the ratio of T/B
;; Not known: OH, p, q, k, T, B
;; :((, so much estimation
;; Actually: We render a fixed with font, so we should know very well what the
;; size is:
;; * 

;; Log message size:
;; Q: What if we pre-render a log message when it arrives do we then
;; know the exact size? Not really, since we might have a different
;; div width when the user displays the overlay.
;; But we could pre-calc it and then set a dirty flag or re-calc on
;; resize.

;; This we can get with JS:
;; * getBoundingClientRect
;; Returns the height/width of the div. Not taking in account the
;; content (scrolling)
;; * div.scrollHeight should be the absolute size if all elements were
;;   rendered.
;; * scrollTop: Pixels hidden on top
;; * clientHeight: The current height of the div not taking content
;;   into account

(defn render-logs
  "Renders an array of log messages."
  [logs]
  {:pre [(sequential? logs)]}
  [:ul {:style {:padding ".5em"
                :margin "0em"
                :line-height "1.06em"}}
   ;; Create the rendered log message
   (for [lg (rseq logs) #_(subvec logs 30)]
     ^{:key (:uuid lg)} [render-msg lg])])

(defn render-tab-item
  "Renders a tab item on the left menu. A filter (keyword) typically."
  [db tab]
  {:pre [(keyword? tab)]}
  [:li {:style {:color (condp = (:showing-tab @db)
                         tab "white"
                         "grey")
                :cursor "pointer"}
        :on-click (fn[_] (action! db :switch-tab tab))}
   (str tab)
   ;; Also display the current search term for the tab as a subscript:
   [:sub (str "/" (get-in @db [:tabs tab :search] ""))]])

(defn render-overlay
  "Renders the entire log message overlay in a div when :showing is true."
  [db]
  (when (:showing @db)
    [:div {:style (:div-outer css)}
     ;;;;;;;;;; The left nav menu ;;;;;;;;;
     [:div.klang-nav
      {:style (:nav css)}
      ^{:key (:showing-tab @db)}
      ;;;;;;;;;;;;;; Search input ;;;;;;;;;;;;;;;
      [:input {:style {:margin ".4em"
                       :width "calc(100% - 1em)"}
               :on-change (fn[e] (action! db :search
                                          {:tab (:showing-tab @db)
                                           :regex (.. e -target -value)}))
               :type "text"
               :default-value (get-in @db [:tabs (:showing-tab @db) :search])
               :placeholder "search"}]
      ;;;;;;;;;;;;;; Tabs ;;;;;;;;;;;;;;;
      [:ul {:style {:padding ".5em"
                    :margin "0em"
                    :line-height "1.4em"}}
       (for [tab (keys (:tabs @db))]
         ;; TODO: Pass in showing-tab cursor
         ^{:key tab} [render-tab-item db tab])]
      ;;;;;;;;;;;;;; Bottom, new tabs and pause ;;;;;;;;;;;;;;;
      [:span {:style {:bottom 0
                      :left ".5em"
                      :position "absolute"}}
       [:a {:style
            {:cursor "pointer"
             :color "green"}
            :on-click (fn[e] (action! db :clone-tab))}
        "Clone"]
       ","
       [:a {:style
            {:cursor "pointer"
             :color (if (:frozen @db) "orange" "green")}
            :on-click (fn[e] (action! db :freeze.toggle))}
        (if (:frozen @db) "Thaw" "Freeze")]]
      ]
     ;;;;;;;;;; The main logs ;;;;;;;;;
     [:div.klang-logs
      {:style (:div-logs css)
       :id "KLANG_LOG_DIV"
       ;; Save scrolling position
       :on-scroll (fn[ev] (action!
                           db ::scroll
                           ;; By the time we handle the action we might
                           ;; have a different showing tab so we need
                           ;; to capture it here
                           {:tab (:showing-tab @db)
                            :y (.. ev -target -scrollTop)}))}
      [render-logs (get-in @db [:tabs (:showing-tab @db) :logs])]
      ]]))

;; TODO:
;;(js/console.dir (gstyle/getSize (dom/getElement "KLANG_LOG_DIV")))

(defn get-dom-el
  "Ensures there is a div element in the body that we can render to and returns
  it."
  []
  (let [domid "__klang__id__"
        domel (dom/getElement domid)]
    (or
     domel ;; Already exists and we return it
     ;; Otherwise create it:
     (let [newdom (dom/createDom "div" #js{:id domid})] 
       (dom/appendChild js/document.body newdom)
       (log-console "Klang: DOM element created.")
       newdom))))

;; Taken from highlight-js
(defonce css-molokai ".hljs {
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

;; Deref this to inject the CSS
(def inject-highlightjs-css
  (delay (gstyle/installStyles css-molokai)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Log message data manip
(defn valid-log?
  "Returns true if the argument is a valid log event. Must include date/time."
  [log-ev]
  (and 
   (instance? goog.date.DateTime (:time log-ev))
   ;; Only used by closure compiler:
   ;;(instance? goog.date.DateLike (:time log-ev))
   (string? (:ns log-ev)) ;; Namespace needs to be a string.
   (keyword? (:type log-ev))
   (contains? log-ev :msg) ;; can be anything (even nil!), but must exist
   ))

(defn cache-render
  [lg-ev]
  (assoc lg-ev ::cached-render (render-msg lg-ev)))

(defn move-meta-data
  "If the log message came from a macro, it might pass along meta information
  such as filename and line number of the log call. This is passed in as a
  special symbol of the first element in the :msg of the log event. This
  function removes this special symbol and instead put the meta information into
  :meta of the log event."
  [lg-ev]
  (condp = (first (:msg lg-ev))
    'klang.core/meta-data
    (-> lg-ev 
        ;; Remove the symbol from the log :msg
        (update-in [:msg] rest)
        ;; Instead put the meta data into :meta
        (assoc :meta (meta (-> lg-ev :msg first))))
    lg-ev))

(defn log+rules
  "Adds some data to the log message (such as date and uuid) if it's not already
  there"
  [db log-ev]
  {:post [(valid-log? %)]}
  (-> log-ev
      ensure-uuid
      ensure-msg-vec
      ensure-timed
      move-meta-data ;; Needs to come before cache-render!
      cache-render))

(defn single-transduce
  "Takes a transducer (xform) and an item and applies the transducer to the
  singe element and returnes the transduced item. Note: No reducing is
  involved. Returns nil if there was no result."
  [xform x]
  ;; First pass the reducers to the transducer. This is just a function that
  ;; returns the second argument (the result).
  ;; Then invoke it with nil as the accumulator and x the current actual
  ;; element
  ((xform (fn[_ r] r)) nil x))

(defn ch->logs!
  "Taps into the mult chan of the db and pushed the log event into the logs
  atom when a log event is received."
  [db]
  ;; We have a big buffer here since if we're in a frozen state the buffer will
  ;; be filling up.
  ;; Problem: Can't put the transducer into the chan argument since it's empty
  ;; when called in the beginning
  ;; TODO: Rewrite with mix & toggle. Then remix everytime the transducers
  ;; change?
  (let [lg-ch (tap (:log-sub-ch @db) (chan (sliding-buffer (:freeze-buffer @db))))
        freeze-ch (:freeze-ch @db)]
    (go-loop [is-frozen false]
      ;; l-chans: What chnnels to listen to
      (let [l-chans (if is-frozen [freeze-ch] [freeze-ch lg-ch])
            ;; The transducers that do the global transducing such as rendering
            ;; Might change over time so they're re-evaluated here
            transd (apply comp (vals (:transducers @db)))
            [v ch] (alts! l-chans)]
        (when (= ch lg-ch)
          ;; Put the log event into the db
          (action! db :new-log
                   (log+rules db (single-transduce transd v))))
        (recur (and (= ch freeze-ch) v))))))


(defn search-transducer
  "Returns a transducer that filters given the log messages according to the
  search term given in the database for the current active tab.
  Does a full text search on time, namespace, type and message.
  The format is like:
  11:28:27.793 my.ns/INFO [\"Log msg 0\"]"
  ([db] (search-transducer db (:showing-tab @db)))
  ([db tab]
   (filter (fn[lg-ev]
             (let [search (get-in @db [:tabs tab :search] "")
                   search (string/replace search " " ".*")
                   re    (try (js/RegExp. search "i")
                              (catch :default e (js/RegExp "")))
                   log-str (str
                            ((:time-formatter @db) (:time lg-ev))
                            " "
                            (:ns lg-ev)
                            (when-not (empty? (:ns lg-ev)) "/")
                            (name (:type lg-ev)) " "
                            (str (:msg lg-ev)))
                   test  (.test re log-str)]
               ;; The .test might be undefined if the search str is empty?
               (if (undefined? test)
                 true ;; Include all of them then
                 test))))))

(defn register-transducer!
  [db key transducer]
  (action! db :register-transducer {:key key :fn transducer}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Action management

(defmulti action!
  "Sends an action on the event bus. First argument can either be the
  action or the db atom.
  Ex:
  (action! :show)
  (action! db :show)"
  (fn [db_or_action & _] (keyword? db_or_action)))

(defmethod action! true
  [action & ev]
  (apply action! *db* action ev))

(defmethod action! false
  [db action & ev]
  (put! (:actions-pub-ch @db)
        {:action action
         :data (if (= (count ev) 1) (first ev) ev)}))

(defmulti handle-action
  (fn [db ev] (:action ev))
  :default nil)

;; We're fine missing out on actions:
(defmethod handle-action nil
  [_ e]
  (log-console (str "Klang: Unhandled event. Weird." e)))

;; Scrolling is a private event, hence namespaced keyword
(defmethod handle-action ::scroll
  [db {{:keys [tab y]} :data}]
  {:pre [(keyword? tab)]}
  (swap! db assoc-in [:tabs tab :scroll-top] y))

(defmethod handle-action :show.toggle
  [db ev]
  (action! db (if (:showing @db) :hide :show)))

(defmethod handle-action :show
  [db ev]
  (swap! db assoc :showing true))

(defmethod handle-action :hide
  [db ev]
  (swap! db assoc :showing false))

(defn cloned-tab-name
  "Returns the next unused name for a tab. Ex:
  Existing: :erro, :info, :my-ns, my-ns0
  if tab-kw is :my-ns this function will return :my-ns1"
  [db tab-kw]
  (let [candidates (map #(->> % (str (name tab-kw))
                              keyword)
                        (range)) ;; lazy
        existing (set (keys (:tabs @db)))]
    (first (filter #(not (contains? existing %)) candidates))))

(defmethod handle-action :clone-tab
  [db _]
  (let [tab (:showing-tab @db)]
    (swap! db assoc-in
           [:tabs (cloned-tab-name db tab)]
           (get-in @db [:tabs tab]))))

(defmethod handle-action :switch-tab
  [db {tab :data}]
  (swap! db assoc :showing-tab tab))

;; Takes a \"rich\" log event `log-ev' and appends it to the `logs' atoms in the
;; db.
(defmethod handle-action :new-log
  [db {:keys [data]}]
  {:pre [(valid-log? data)]}
  ;; First put it in the global log message vector:
  (swap! db update-in [:logs] conj data)
  ;; Then also put it into all tabs :logs vectors
  (doseq [tab (keys (:tabs @db))]
    (let [td (apply comp
                    (search-transducer db tab)
                    (get-in @db [:tabs tab :transducers]))
          ;; A hack: Run a transducer on a single element and pick out the elem
          data-td (single-transduce td data)
          kork [:tabs tab :logs]]
      ;; Don't swap if it's empty. Doh
      (when-not (empty? data-td) 
        (swap! db update-in kork conj data-td)))))

;; Invalidades the cached log vector in a certain tab. This is needed when the
;; search term is changed or new tab-transduceres were added
(defmethod handle-action :recalc-tab-logs
  [db {tab :data}]
  {:pre [(keyword? tab)]}
  (let [td (apply comp
                  (search-transducer db tab)
                  ;;(take 100) ;; Todo: Implement take etc with scroll position
                  (get-in @db [:tabs tab :transducers]))
        ;; Run the transducers on all global logs in :logs
        data-td (into [] td (:logs @db))
        ;;(log-console (map :type (:logs @db)))
        kork [:tabs tab :logs]]
    (swap! db update-in kork (fn[_] data-td))))

(defmethod handle-action :search
  [db {{:keys [tab regex]} :data}]
  (swap! db assoc-in [:tabs tab :search] regex)
  (action! db :recalc-tab-logs tab))

(defmethod handle-action :register-transducer
  [db {{:keys [key fn]} :data}]
  (swap! db assoc-in [:transducers key] fn))

(defmethod handle-action :register-tab-transducer
  [db {{:keys [tab fn]} :data}]
  {:pre [(keyword? tab) (fn? fn)]}
  (swap! db update-in [:tabs tab :transducers] conj fn)
  ;; We have to recalculate the :logs of the tab:
  (action! db :recalc-tab-logs tab))

(defmethod handle-action :freeze.toggle
  [db _]
  (action! db (if (:frozen @db)
                :thaw
                :freeze)))

(defmethod handle-action :freeze
  [db _]
  ;; Make the log handler stop reading form the logs:
  (put! (:freeze-ch @db) true)
  (swap! db assoc :frozen true))

(defmethod handle-action :thaw
  [db _]
  ;; Make the log handler stop reading form the logs:
  (put! (:freeze-ch @db) false)
  (swap! db assoc :frozen false))

;; TODO: Refactor me into a function call.
(defn init-action-handler
  "Initializes the main action handlers by tapping into the action channel."
  [db]
  (let [action-ch (chan 37)]
    (tap (:actions-sub-ch @db) action-ch)
    (dochan [ev action-ch] 
            (handle-action db ev))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API

(defn freeze!
  "Makes the logger pause streaming logs. The logs will be buffered to up to
  :freeze-buffer messages"
  ([] (freeze! *db*))
  ([db]
   (action! db :freeze)))

(defn thaw!
  "Makes the logger resume streaming logs."
  ([] (thaw! *db*))
  ([db]
   (action! db :thaw)))

(defn show!
  "Makes the overlay show."
  ([] (show! *db*))
  ([db]
   (action! db :show)))

(defn hide!
  "Makes the overlay hide."
  ([] (hide! *db*))
  ([db]
   (action! db :hide)
   (thaw! db))) ;; We probably want to continue logging I think

(defn raw-log!
  "Adds a log event lg-ev to db. Log can contain :time. Async.
  Ex:
  (raw-log! {:type :INFO :ns \"my.xy\" :msg :woah})"
  ([lg-ev] (raw-log! *db* lg-ev))
  ([db lg-ev]
   ;; Will never block due to dropping buffer
   (put! (:log-pub-ch @db) lg-ev)))

(defmulti log!
  "Logs a message msg for namespace and ns_type.
  Eg:
  (log! ::WARN :server-down)
  (log! db ::WARN :server-down)
  (log! :bouncer.core/INFO :server-spotted)"
  (fn [db_or_nstype & _] (keyword? db_or_nstype)))

(defmethod log! true
  [ns_type & msg]
  (apply log! *db* ns_type msg))

(defmethod log! false
  [db ns_type & msg]
  ;; Even though we could let the time be added later we do it right here to
  ;; have an accurate time
  (raw-log! db {:time (goog.date.DateTime.)
                :type (keyword (name ns_type)) ;; name make ::FOO -> "FOO"
                ;; (namespace :FOO) is nil, that's why we need (str) here
                :ns (str (namespace ns_type))
                :msg (vec msg)}))

(defn logger
  "Creates a new logger with the keyword `ns_type'. The keyword ns_type is
  used to determine what namespace and type the logger logs to. For instance
  :my.app/INFO will create a logger with namespace 'my.app and logger type
  :INFO.
  Returns a function that can be called to log to that logger.
  If given db and log will use this instead of the global ones."
  ([ns_type] (logger *db* ns_type))
  ([db ns_type]
   {:pre [(keyword? ns_type)]}
   (fn [& msg]
     (apply log! db ns_type msg))))

(defn init-single-mode!
  "Inits the logging library for single user mode. Ie you can only have one log
  window and one global logger. This should likely be your default."
  []
  (reset! *db* (new-db)))

(defn init!
  "It will re-init all channels so you'll have to re-listen to them.
  If you want to set freeze-buffer you need to set it before and pass
  in the db."
  ([] (init! *db*))
  ([db]
   (some-> (:log-pub-ch @db) close!) ;; Close previous channel if there is one
   ;; The main channel that receives the raw log events.
   ;; We want to buffer a few message when we freeze the UI
   ;; Drop oldest
   (swap! db assoc :log-pub-ch (chan (sliding-buffer 100)))
   ;; Create mutli channel for the main log-in-ch
   ;; This is so the user can also listen on any log messages
   (swap! db assoc :log-sub-ch (mult (:log-pub-ch @db)))
   (swap! db assoc :actions-pub-ch (chan 10))
   (swap! db assoc :actions-sub-ch (mult (:actions-pub-ch @db)))
   ;; If we freeze/thaw quickly and don't keep up with handling them, we don't
   ;; care if we miss the values in between. Just discard them:
   (swap! db assoc :freeze-ch (chan (sliding-buffer 1)))
   (init-action-handler db)
   (ch->logs! db) ;; Start the go loop which puts in the logs
   (log-console "Klang: Logging initialized.")))

(defn install-shortcut!
  "Installs a Keyboard Shortcut handler that show/hide the log overlay."
  [db keys]
  (swap! db assoc :shortcut-keys keys)
  (let [handler (new KeyboardShortcutHandler js/window)]
    (.registerShortcut handler "klang.toggle" (:shortcut-keys @db))
    (.listen goog.events
             handler
             KeyboardShortcutHandler.EventType.SHORTCUT_TRIGGERED
             (fn[e] ;; e.identifier will be "toggle-klang"
               (action! db :show.toggle)))
    (swap! db assoc :shortcut-handler handler) ;; Needed to unregister it.
    (log-console "Klang: Keyboard shortcut installed.")))

(defn uninstall-shortcut!
  "Uninstalls a shortcut create by klang."
  [db]
  (.unregisterShortcut (:shortcut-handler @db) (:shortcut-keys @db)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functionality thru standard API:

(defn sensible-time-format
  "Installs a transducer to format the time"
  [db]
  (register-transducer!
   db :time-formatter
   (map
    (fn [x]
      (update-in
       x [:render :time] conj
       (fn [m]
         [:span
          ((:time-formatter @db) m)]))))))

(defn hl-clj-str
  "Returns a string containing HTML that highlights the message. Takes a string
  of clojure syntax. Such as map, set etc.
  Ex:
  (hl-clj-str \"{:foo :bar}\")"
  [^String msg]
  (.-value ;; hljs returns an object and the HTML is in .value
   (.highlight js/hljs "clojure" msg true)))

(defn msg->str
  "Converts a message to a string. Also calls js->clj."
  [msg]
  (as-> (pr-str (js->clj msg)) s
    (.substr s 1 (- (.-length s) 2))))

(defn register-highlighter!
  "Registeres a transducer :highlight-msg that highlights the :msg of
  log. Uses js->clj to ensure the :msg is cojure.
  TODO: We should highlight functions as javascript and not clojure!"
  [db]
  @inject-highlightjs-css
  (register-transducer!
   db :highlight-msg
   (map
    (fn [x] (update-in
             x [:render :msg] conj
             (fn [m]
               [:span
                {"dangerouslySetInnerHTML"
                 #js{:__html (hl-clj-str (msg->str m))}}]))))))

;; TODO: Also highlight all namespaces with a smart method?
;; For instance all parents vs childs have stronger colors...
;; How to define a tree of colors such that they're similar yet different?
;; Sounds like a coloring problem??
;; Actually sounds more like locality sensitve hashing algorithm!
;; Easiest LSH:
;; Just explode each '.' and project it into some numbers.
;; Actually probably a weighted projection that gives more weights to the
;; leftmost parts of the namespace.
;; What color space to use? So many questions...

(defn pred->color!
  "Given a predicate pred? and which (:ns, :type) it wraps the part of
  the log message in a span and applies the color.
  Note that if you get an error here saying that name doesn't support
  some hiccup markup. Then you've nested your coloring. Which is
  possible but you'll have to take care of this in a special hand
  crafted transducer. (Not here)"
  [db pred? which color]
  {:pre [(keyword? which) (or (= :ns which) (= :type which))]}
  (register-transducer!
   db (keyword (random-uuid))
   (map
    (fn [msg]
      (if (pred? (which msg)) ;; pluck out :ns or :type
        (update-in msg
                   [:render which] conj
                   (fn [t] ;; Special render function.
                     [:span {:style {:color color}}
                      (name t)])) ;; name for :ns & :type
        msg)))))

(defn type->color!
  "Given a type keyword (like :INFO), render the type in color."
  [db type color]
  (pred->color! db (partial = type) :type color))

(defn ns->color!
  "Gives the namespace ns a color."
  [db ns-str color]
  (pred->color! db (partial = ns-str) :ns color))

(defn ns*->color!
  "Gives all namespaces that are children of ns* a color."
  [db ns* color]
  (pred->color! db (partial self-or-parent? ns*) :ns color))

;; This is mostly obsolete now that we can click on a message and log it.
(defn msg->console!
  "Registers a listener to the log channel which will --in addition to
  logging it-- also output the :msg to the console with %O.
  Example:
  (msg->console! db :CONSOLE)"
  [db type]
  (let [ch (tap (:log-sub-ch @db) (chan 10))]
    (go-loop []
      (let [lg (<! ch)] 
        ;; This logs the object to the console an allow it to inspect.
        ;; Chrome only?
        (when (= (:type lg) type)
          (.log js/console "%s/%s %O" (:ns lg) (name (:type lg)) (:msg lg))))
      (recur))))

(defn tab->transducer!
  [db tab-key transducer]
  (action! db :register-tab-transducer
           {:tab tab-key
            :fn transducer}))

(defn tab->type!
  [db tab-key & types]
  (tab->transducer! db tab-key (filter #(contains?
                                         (set types)
                                         (:type %)))))

(defn tab->ns*!
  [db tab-key & namespaces]
  (tab->transducer! db tab-key (filter #(some
                                         (fn[ns] (self-or-parent? ns (:ns %)))
                                         namespaces))))


(defn tab->ns!
  [db tab-key & namespaces]
  (tab->transducer! db tab-key (filter #(some
                                         (fn[ns] (= ns (:ns %)))
                                         namespaces))))

;; http://www.w3schools.com/cssref/css_colornames.asp
(defn color-types! [db]
  ;;(type->color! db :TRAC "lightblue")
  (type->color! db :DEBG "gray")
  (type->color! db :TRAC "darkgray")
  (type->color! db :INFO "steelblue")
  (type->color! db :ERRO "darkred")
  (type->color! db :CRIT "red")
  (type->color! db :FATA "firebrick")
  (type->color! db :WARN "orange"))

(defn default-config!
  "Sets up key presses and some default tabs."
  ;; Add highlight renderer,
  ;; error -> red etc
  ([] (default-config! *db*))
  ([db]
   (color-types! db) ;; This errors and I don't know why
   (install-shortcut! db "l")
   (register-highlighter! db)
   (sensible-time-format db)
   (r/render [render-overlay db] (get-dom-el))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RDD
(comment

  ;; Alias
  (def l log-console)

  (js/console.dir (goog.date.DateTime.))

  ;; :hour-minute-second-ms : 11:02:23.009
  ;; :time  : 11:01:56.611-04:00
  
  (l  (filter #(:fo %)))

  )
