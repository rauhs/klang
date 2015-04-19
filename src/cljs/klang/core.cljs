(ns klang.core
  ;;(:refer-clojure :exclude [reset!]);; We want our own reset!
  (:require-macros
   [reagent.ratom :refer [reaction] :as re]
   [klang.macros :refer [deflogger dochan] :as macros]
   [cljs.core.async.macros :refer [go-loop go]])
  (:require
   [reagent.core :as r :refer [atom]]
   [cljs-time.core :as t]
   [cljs-time.format :as tf]
   [cljsjs.highlight]
   [cljsjs.highlight.langs.clojure]
   ;;[cljsjs.highlight.langs.json]
   [cljs.core.async :refer [put! chan sliding-buffer <! mult
                            tap close! pub sub timeout take!]]
   ;; Google Closure
   [goog.dom :as dom])
  (:import 
   [goog.ui KeyboardShortcutHandler]))

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

;; If anything goes wrong in register-transducer and the render functions
;; figwheel goes a little crazy and we need to reload the page!


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global stuff / defs

(defonce
  ^{:doc "Holds a global instance of a db in case we want to use this
 library in single user mode. Ie only one loggins system. Will be true for 99%
 of all users."}
  *db* (atom {}))

;; Config for display/tabs of the log viewer
(defn new-db
  "Creates a new logger instance database."
  []
  {
   ;; Where ALL logs will be pushed. The raw log events including time, ns,
   ;; level, msg. DO NEVER LISTEN TO THIS!!
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
   ;; User defined tabs state
   :tabs {
          ;; The main tab holding all logs
          :all {:transducers [identity] ;; basically the filter
                ;; The full text search box content
                ;; Don't nil this
                :search ""
                :logs [] ;; The logs for this tab
                :scroll-top 0} ;; The scroll position
          }
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
   :logs []
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

;; TODO: Just use a simple counter instead
;; OR use goog.string.getRandomString
(defn make-random-uuid
  " Returns pseudo randomly generated UUID,
  like: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"
  []
  (letfn [(f [] (.toString (rand-int 16) 16))
          (g [] (.toString  (bit-or 0x8 (bit-and 0x3 (rand-int 15))) 16))]
    (clojure.string/join (concat
                          (repeatedly 8 f) "-"
                          (repeatedly 4 f) "-4"
                          (repeatedly 3 f) "-"
                          (g) (repeatedly 3 f) "-"
                          (repeatedly 12 f)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Declares
(declare action!)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ensure-uuid
  "Adds a :uuid field to the map if there isn't one yet."
  [msg]
  (if (:uuid msg)
    msg
    (assoc msg :uuid (make-random-uuid))))

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
    (assoc msg :time (t/time-now))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core rendering the logs

;; Renders a line:
;; TIME NS/LEVEL Msg
(defn render-msg
  "Renders a single log message."
  [lg-ev]
  ;;(log-console "render-msg")
  ^{:key (:uuid lg-ev)} ;; Performance?
  [:li {:style {:list-style-type "none"}}
   ;; TODO: Could also accept :render :msg which renders the entire lg-ev?
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
   ;; Wrap the message in a span to allow clicking it and logging it to the
   ;; console
   [:span
    {:style {:cursor "pointer"}
     ;; Not sure how many browsers allow this shortcut (console.dir)
     :on-click (fn[_]
                 (do 
                   (js/console.log "---- %s/%s -- %O"
                                   (:ns lg-ev)
                                   (name (:type lg-ev))
                                   (:time lg-ev))
                   (mapv #(js/console.log "%O" %) (:msg lg-ev))))}
    (if-let [rndr (get-in lg-ev [:render :msg])]
      [(apply comp rndr) (:msg lg-ev)]
      (str (:msg lg-ev)))]])

(defn render-logs
  [logs]
  {:pre [#_(log-console "expensive render called")
         (sequential? logs)]}
  [:ul {:style {:padding ".5em"
                :margin "0em"
                :line-height "1.06em"}}
   ;; Create the rendered log message
   (for [lg logs]
     ^{:key (:uuid lg)} [render-msg lg])])

(defn render-tab-item
  "Renders a tab item on the left menu. A filter (keyword) typically."
  [db tab]
  {:pre [(keyword? tab)]}
  [:li {:style {:color (condp = (:showing-tab @db)
                         tab "white"
                         "grey")
                :cursor "pointer"}
        :on-click (fn[e] (action! db :switch-tab tab))}
   (str tab)
   ;; Also display the current search term for the tab as a subscript:
   [:sub (str "/" (get-in @db [:tabs tab :search] ""))]])

(defn render-overlay
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
       ;; Save scrolling position
       :on-scroll #(action! db ::scroll
                            ;; By the time we handle the action we might
                            ;; have a different showing tab so we need
                            ;; to capture it here
                            {:tab (:showing-tab @db)
                             :y (.. % -target -scrollTop)})}
      ;; First filter the elements for the current tab:
      ;; TODO: This is run every time if we scroll. Optimize.
      [render-logs (get-in @db [:tabs (:showing-tab @db) :logs])]
      ]]))

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
       (log-console "DOM element for klang created")
       newdom))))

;; Ensures there is a link element in the head that injects the css for
;; highlight.js
(def inject-highlightjs-css
  (delay 
   (do 
     ;; TODO: Get this css from somewhere else to be nice?
     (let [cssurl "https://highlightjs.org/static/styles/monokai_sublime.css"
           linkel (dom/createDom "link"
                                 #js{:href cssurl
                                     :rel "stylesheet"
                                     :type "text/css"
                                     :class "codestyle"})] 
       ;; document.head is >IE9
       (dom/appendChild js/document.head linkel)))))

@inject-highlightjs-css

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

(defn log+rules
  "Adds some data to the log message (such as date and uuid) if it's not already
  there"
  [db log-ev]
  {:post [(valid-log? %)]}
  (-> log-ev
      ensure-uuid
      ensure-msg-vec
      ensure-timed))

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
  ;; TODO: Rewrite with mix & toggle
  (let [lg-ch (tap (:log-sub-ch @db) (chan (sliding-buffer (:freeze-buffer @db))))
        freeze-ch (:freeze-ch @db)]
    (go-loop [is-frozen false]
      ;; l-chans: What ch to listen
      (let [l-chans (if is-frozen [freeze-ch] [freeze-ch lg-ch])
            ;; The transducers that do the global transducing such as rendering
            ;; Might change over time so they're re-evaluated here
            transd (apply comp
                          (vals (:transducers @db)))
            [v ch] (alts! l-chans)]
        (when (= ch lg-ch)
          ;; Put the log event into the db
          (action! db :new-log
                   (log+rules
                    ;; TODO: Use transduce here instead
                    db (single-transduce transd v))))
        (recur (and (= ch freeze-ch) v))))))


(defn search-transducer
  "Returns a transducer that filters given the log messages according to the
  search term given in the database for the current active tab.
  Does a full text search on time, namespace, level and message.
  The format is like:
  11:28:27.793 my.ns/INFO [\"Log msg 0\"]"
  ;; TODO: Fuzzy sear
  ([db] (search-transducer db (:showing-tab @db)))
  ([db tab]
   (filter (fn[lg-ev]
             ;; Todo: let some?
             (let [search (get-in @db [:tabs tab :search] "")
                   re    (try (js/RegExp. search "i")
                              (catch :default e (js/RegExp "")))
                   log-str (str
                            (tf/unparse
                             (:hour-minute-second-ms tf/formatters)
                             (:time lg-ev))
                            " "
                            (:ns lg-ev)
                            (when-not (empty? (:ns lg-ev)) "/")
                            (name (:type lg-ev)) " "
                            (str (:msg lg-ev)))
                   test  (.test re log-str)]
               ;; The .test might be undefined if the search str is empty?
               (if (undefined? test)
                 true ;; Include all of them then
                 test)
               )))))

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
  (swap! db update-in [:logs] #(cons data %))
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
        (swap! db update-in kork #(cons data-td %))))))

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
  "Logs a message msg for namespace and level ns_type.
  Eg:
  (log ::WARN :server-down)
  (log db ::WARN :server-down)
  (log :bouncer.core/INFO :server-spotted)"
  (fn [db_or_nslevel & _] (keyword? db_or_nslevel)))

(defmethod log! true
  [ns_type & msg]
  (apply log! *db* ns_type msg))

(defmethod log! false
  [db ns_type & msg]
  ;; Even though we could let the time be added later we do it right here to
  ;; have an accurate time
  (raw-log! db {:time (t/time-now)
                :type (keyword (name ns_type)) ;; name make ::FOO -> "FOO"
                ;; (namespace :FOO) is nil, that's why we need (str) here
                :ns (str (namespace ns_type))
                :msg (vec msg)}))

(defn logger
  "Creates a new logger with the keyword `ns_level'. The keyword ns_level is
  used to determine what namespace and level the logger logs to. For instance
  :my.app/INFO will create a logger with namespace 'my.app and logger level
  :INFO.
  Returns a function that can be called to log to that logger.
  If given db and log will use this instead of the global ones."
  ([ns_level] (logger *db* ns_level))
  ([db ns_level]
   {:pre [(keyword? ns_level)]}
   (fn [& msg]
     (apply log! db ns_level msg))))

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
  ""
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
          (tf/unparse (:hour-minute-second-ms tf/formatters) m)]))))))

(defn hl-clj-str
  "Returns a string containing HTML that highlights the message. Takes a string
  of clojure syntax. Such as map, set etc.
  Ex:
  (hl-clj-str \"{:foo :bar}\")
  "
  [^String msg]
  (.-value ;; hljs returns an object and the HTML is in .value
   (.highlight js/hljs "clojure" msg true)))

(defn msg->str
  "Converts a message to a string. Also ensures the data is "
  [msg]
  (as-> (str (js->clj msg)) s
    (.substr s 1 (- (.-length s) 2))))

(defn register-highlighter!
  "Registeres a transducer :highlight-msg that highlights the :msg of
  log. Uses js->clj to ensure the :msg is cojure.
  TODO: We should highlight functions as javascript and not clojure!"
  [db]
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

(defn pred->color
  "Given a predicate pred? and which (:ns, :type) it wraps the part of
  the log message in a span and applies the color.
  Note that if you get an error here saying that name doesn't support
  some hiccup markup. Then you've nested your coloring. Which is
  possible but you'll have to take care of this in a special hand
  crafted transducer. (Not here)"
  [db pred? which color]
  {:pre [(keyword? which) (or (= :ns which) (= :type which))]}
  (register-transducer!
   db (keyword (make-random-uuid))
   (map
    (fn [msg]
      (if (pred? (which msg)) ;; pluck out :ns or :type
        (update-in msg
                   [:render which] conj
                   (fn [t] ;; Special render function.
                     [:span {:style {:color color}}
                      (name t)])) ;; name for :ns & :type
        msg)))))

(defn type->color
  "Given a type keyword (like :INFO), render the type in color."
  [db type color]
  (pred->color db (partial = type) :type color))

(defn ns*->color
  "Gives all namespaces that are children of ns* a color."
  [db ns* color]
  (pred->color db
               (fn [ns] (or (= ns* ns)
                            (parent? ns* ns)))
               :ns color))

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
  (type->color db :TRAC "lightblue")
  (type->color db :DEBG "gray")
  (type->color db :INFO "steelblue")
  (type->color db :ERRO "red")
  (type->color db :CRIT "darkred")
  (type->color db :FATAL "firebrick")
  (type->color db :WARN "orange"))

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
   (r/render [render-overlay db] (get-dom-el))
   ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; For development
(defn ex-log-data []
  (ns*->color *db* "klang.core" "yellow")
  (tab->type! *db* :erro :ERRO)
  (tab->type! *db* :errwarn :ERRO :WARN)
  (tab->ns! *db* :my.ns "my.ns")
  (tab->type! *db* :my.ns :INFO)
  (tab->ns*! *db* :my.ns* "my.ns")
  ;;(msg->console! *db* :CONSOLE)

  (doseq [x (range 15)
          :let [lg {:time (goog.date.DateTime.)
                    :msg (str "Log msg " (* x 1))
                    :type :INFO
                    :ns "my.ns"}]]
    ;; Will receive a time for the channel listener
    (raw-log! *db* lg))

  (doseq [x (range 15)
          :let [lg {:msg (str "Log msg " (* x 1))
                    :type :TRAC
                    :ns "my.ns.one"}]]
    ;; Will receive a time for the channel listener
    (raw-log! *db* lg))
  ;; These will log to the console
  ;; OBSOLETE:
  #_(let [lg (logger ::CONSOLE)]
    (lg {:test "foo"} :bar))

  (log! :TRAC "World peace not achieved.")
  (log! :DEBG "No ns")
  (log! :INFO "No ns")
  (log! :FATAL "fatal stuff")
  (log! :CRIT "World peace not achieved.")
  (log! *db* :FATAL "With db and stuff")
  (log! *db* :WARN "Warn and stuff")

  (let [lg (logger ::ERRO)]
    (lg {:test "foo"} :bar "this is a problem")
    (lg {:test "twooo"}))


  (let [lg (logger ::ERRO)]
    (lg {:test "foo"} :bar)
    (lg nil)
    (lg :function  (fn[name] (str "Hello " name)))
    ;; Long message should wrap
    (lg :test "This is a longer test message so we can see wrapping it around."
        :nil=also-works nil
        nil 'symbols 'also 'work
        :this :should :really ["wrap" :around "on your small browser window"])))

(def gen-logs
  (delay
   (go-loop [i 0]
     (<! (timeout 100))
     (log! ::INFO {:gen i})
     ;;(l i)
     (recur (+ i 1)))))

(defn demo!
  []
  (init-single-mode!) ;; Sets *db*
  (init!)
  (default-config!)
  (show!)
  (ex-log-data))


(demo!)
;; Deref to generate logs
;; @gen-logs

;;(macros/init-dev!)
;;(macros/init-prod!)
;;(macros/init-debug-prod!)

;; And also won't generate any JS code:
;; (macros/log! ::THIS_WONT_LOG "LOOK_FOR_ME_IN_JS_CODE")
(macros/log! ::INFO :also :test :multi "args just in case" nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RDD
(comment

  ;; Alias
  (def l log-console)

  ;; :hour-minute-second-ms : 11:02:23.009
  ;; :time  : 11:01:56.611-04:00
  (l (tf/unparse (:hour-minute-second-ms tf/formatters) (t/time-now) ))

  (l (tf/unparse :time (t/time-now) ))
  
  (l  (filter #(:fo %)))

  )
