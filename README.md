# Klang - A Clojurescript logging libary/viewer

This library offers a simple logging interface in clojurescript for
usage in the browser. It allows for powerful (user defined) log
filtering and syntax highlighting for clojure data structures:

![Example](https://github.com/rauhs/klang/blob/master/docs/img/example.png)

# Features

* Central hub to push all log messages in your client app
* Define multiple tabs that filter only the messages that you're interested in.
* Filter exactly what you want in a tab with transducers (allows things like:
  "wait for event X, then show the next 10 events")
* Enter a search term to find log messages
* Pausing the UI in case of many logs arriving. This will not discard
  the logs but buffer them.
* Clone existing tabs to quickly apply different search terms.
* Customize rendering of any log data (attach render function to any log
  message that emits hiccup)
* Log is asynchronous so you don't have to worry about blocking.
* Click on any log message and dump the object to your javascript console (as an
  object). This allows you (at least in Chrome) to inspect the object. This even
  allows you to log functions and invoke them in your javascript console.
* No global state, you *could* create multiple completely independent loggers
  and have mutliple overlays. For instance if somebody wanted to have one
  browser window to display the logs of the server and browser.

# Motivation
By now (2015) the javascript and clojurescript community seems to have arrived
at the fact that javascript applications need to be asynchronous throughout.
React's Flux architecture, core.async, re-frame (which uses core.async), RxJS,
CSP etc etc. are all examples of such design decisions.

Asyncronous systems have many advantages but also make reasoning about the
system harder.
One very simple and effective way to cope (or rather: "help") with understanding
behavior is extensive logging from the start of the development.
This projects the concurrent nature of the control flow into a single, easy to
understand and linear trace of events.
The javascript console is not powerful enough, hence this library.

# Clojars

Warning: I've never deployed to clojars.

[![Clojars Project](http://clojars.org/klang/latest-version.svg)](http://clojars.org/klang)

# Usage

The following is the simplest usage:
```clj
(ns your.ns
  (:require [klang.core :as k]))

;; We could potentially use multiple independent loggers. Single mode
;; puts everthing in a local *db*
(k/init-single-mode!)
(k/init!)
;; Setups reasonable default colors for :INFO etc
(k/default-config!)

;; Setup some new tabs (left menu)
;; Only hold events from one namespace
(k/tab->ns! k/*db* :my-tab-name "my.ns" "some.other.ns")
;; Only hold events from ns and it's children
;; so here: my.ns.* and other.ns.*
(k/tab->ns*! k/*db* :my-ns* "my.ns" "other.ns")
;; Or only hold certain types. Yes same tab name as tab->ns! call:
(k/tab->type! k/*db* :my-tab-name :INFO :WARN)

;; Define a logger that always logs as ::INFO
(def lg
  (k/logger ::INFO))

;; Now call the ::INFO logger with whatever parameters you like
(lg :db-init "Db initiaized" 'another-arbitrary-param)
(lg :validation :ok {:user userid})

;; Or without the indirection of k/logger:
(k/log! ::INFO "User logged in")

;; Or the low level raw log:
;; Can be useful if we get log message externally from a server.
(k/raw-log! {:time (goog.date.DateTime.)
             :type :INFO
             :ns "whatever.ns.you.like"
             :msg [:one :two "foo"]})

;; Show the logs in an div overlay:
(k/show!)
;; Or just press `l` if you applied (default-config!)

(k/hide!) ;; hide it again.
```

There is nothing special about `::INFO`, you can use any arbitrary keyword.
The `default-config!` sets up some default color rendering and also
registers the keyboard shortcut `l` to view/hide the logs.

There is also many ways you can customize the rendering yourself. See
the section below for more details on this.

Note that if you use this in production facing code then you'll want
to use this library somewhat differently in order to elide any logging
for production (see below).

# Log message layout
Each log message *internally* has the following fields:

* `:time` is a `goog.date.Date` instance. Either user supplied or filled when
  logged
* `:ns` is a `string` holding the namespace where the log came from. User
  supplied or the empty string "".
* `:type` is a `keyword` specifying the "type" of a log message. This can really
  be anything you want. But most people will use `:INFO`, `:ERRO` or `:WARN`
  and the like.
* `:msg` the actual log message. Is always a vector, even if a single item. This
  allows for arbitrary parameters passed to the various logging functions.
  The default renderer always calls `js->clj` on the message
* `:uuid` is a UUID that the log message is identified with.

The only required fields are `:msg` and `:type`, all others can be omitted.
The `:time` defaults to the current time when the message is added.

# Use cases
The library can be used for different use cases which are described in the following sections:

## Web browser app development
This is likely the most common use case. You're developing a
Clojurescript app for the browser. You want powerful logging.
Simply require the library and call the API functions to log.

### With deployment to clients
In most use cases for web app development you'll want to remove logging data and
the overhead of Klang from your JS code for deployment.
In this case you'll need to use a few macros to introduce a level of indirection
that allows you to elide whatever logs you don't want to make it into function
calls.

Personally, I wouldn't even want any Klang code to stay in a production app
since the logging code of Klang isn't too small.
Hence, in production I want to log a subset of messages (such as warn/error/info
but *not* trace/debug) to be pushed into a global core.async channel where I can
send them to my server (or wherever) in case an error occurs.

This library *could* offer this functionality but I think that most
developers will have a slight different opinion on what to do with
their logs so it's best to just write them yourself.
This is why I've chosen to give this recipe that only has a few lines
of code and everybody can adapt it to their needs:

```clj
;; file-name: your/project/logging.clj -- note: /Not/ cljs
;; 
;; This requires Clojure 1.7 due to the use of transducers. But it can
;; be modified easily to use simple (predicate) functions.
;; This macro file (.clj) is used in both, your production and dev environment.
;; You'll call them differently by using different :source-paths in your
;; leiningen configuration.

;; The global atom holds the filters/transducers that determine if the log! call
;; should be elided or not:
;; These transducers live only during the compilation phase and will not result
;; in any javascript code.
;; Q: Why transducers and not just an array of predicate functions?
;; A: We may be interested in changing (ie. (map..)) the passed in keyword. For
;; instance by removing the namespace from the keyword.
;; The function transduces on (namespaced) keywords which is just the very first
;; argument of the `log!' function.

;; The transducers that are applied before emitting code:
(defonce xforms (atom [(filter (constantly true))]))

;; The clojurescript function that is called when we emit code with the macro:
(def logger 'klang.core/log!)

(defn logger! [log-sym]
  (alter-var-root (var logger) (fn[_] log-sym)))

;; True if the macro should add line information to each log! call
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
  call. So: ::FOO -> :FOO"
  []
  (swap! xforms conj
         (map (fn[type] (keyword (name type)))))
  nil)

;; You'll often see return nil here because we don't want to return anything in
;; the macro calls
(defmacro init-dev! []
  (line-nr! true)
  nil)

(defmacro init-debug-prod!
  "Sets up logging for production "
  []
  ;; For production we call this log function which can do whatever:
  (logger! 'my.app.log/log->server!)
  (line-nr! false)
  (strip-ns!)
  (swap! xforms conj
         ;; Only emit error messages log calls:
         (comp 
          (filter (fn[type] (= (name type) "ERRO")))
          ))
  nil)

(defmacro init-prod!
  "Production: Strip all logging calls."
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

;; Note that while writing macros you may need some figwheel restarts in case of
;; crashes and/or errors.
```

This is a long template. But I think it's better to not include this in Klang
since it's more flexible if users set it up themself.

You can also add file name in the meta information of `&form` but I see no need
for it dues to namespaced keywords.

Then setup and call your logging like so:

```clj
;; -- filename: my/app/setup.cljs
(ns my.app.setup
  (:require-macros
   [klang.macros :refer [log!] :as lgmacros]))

(lgmacros/init-dev!) ;; Or whatever you're in (use leiningen profiles)

(log! ::INFO "hello" :there)
```

You can then switch over to production and get rid of all log calls or forward
them to your own function.

You now lost the `logger` convenience function.
But you have gained total controll over what makes it into your clojurescript
(and javascript) code.

## Server mode
In this use case you're only interested in viewing logs in a browser window but
the browser window itself does not host a client app that is interested in
logging.

For instance, you're forwarding all your logs from your clojure
backend app to Klang. You may also have multiple backends or even your
browser app to also forward the logging to one klang instance.

This mean that you'll have a central location (the browser app running
klang) where you display your client and server-side logging data in
realtime.

* TODO: Add recipe to receive logs over websocket and push them in with `raw-log!`

### Timbre
For instance writing a custom appender in timbre (TODO) could push the log
messages to a browser window and then displaying them with Klang. The times of
your log message wouldn't be touched since you can supply a date/time with
`raw-log!`.

* TODO: Code a timbre appender that also sends the log messages to a websocket
  for displaying with Klang.

# Customizing

## Tabs
The example code above already defined some code that showed off defining custom
tabs.
You're not limited to filter by `:type` and `:ns` however, you can register
arbitrary transducers for a tab.
This allows you to filter and even modify the log messages.
See the function `tab->transducer`.
You can find an example of how to use that function in the source code of this
library.
In fact, `tab->type` and `tab->ns` are implemented using `tab->transducer`.

## Message rendering
By default the messages get transformed with `js->clj` function and then get
syntax highlighted by highlight-js.
You can change this by not applying the default-config 

You can find an example of how to use that function in the source code of this
library.

## Listening for logs
Every log is pushed on a `mult` channel (see core.async docs) which you can tap
into. The channel is in `:log-pub-ch` of the `db` atom.
This allows you to also forward the logs to other places (localStorage or a
server).

## Reacting to klang actions
Most actions are pushed on an the action channel `actions-pub-ch` in the `db`
atom. Which again, is a `mult`.
You can `tap` into it and react however you like.
Events include showing/hiding the log overlay, new log, freezing the channel etc
etc.

# Suggested log types
I'll suggest those log types in order to have same string lengths (similar to
supervisord):

* `:TRAC` -- trace
* `:DEBG` -- debug
* `:INFO`
* `:WARN`
* `:ERRO`
* `:CRIT` -- critical
* `:FATAL`

# TODO

* Improve performance by only rendering the subset of log messages that are seen
  for the current scrolling position.
* Go through the `TODO:` items in `klang/core.cljs`

## License

Copyright &copy; 2015 Andre Rauh. Distributed under the
[Eclipse Public License][], the same as Clojure.


[Eclipse Public License]: <https://raw2.github.com/rauhs/klang/master/LICENSE>
