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
* Log is asynchronous so you don't have to worry about blocking.
* Click on any log message and dump the object to your javascript console (as an
  object). This allows you (at least in Chrome) to inspect the object. This even
  allows you to log functions and invoke them (after assigning them to a global
  variable with a right click) in your javascript console.

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
TODO

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

(def lg
  (k/logger ::INFO))

(lg :db-init "Db initiaized" 'another-arbitrary-param)
(lg :validation :ok {:user userid})

;; Or without the indirection:
(k/log! ::INFO "User logged in")

;; Or the low level raw log:
;; Can be useful if we get log message externally from a server.
(k/raw-log! {:time (goog.date.DateTime.)
             :type :INFO
             :ns "whatever.ns.you.like"
             :msg [:one :two "foo"]})

;; Show the logs.
(k/show!)
;; Or just press `l` if you applied (default-config!)
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
  be anything you want. But most people will use `:INFO`, `:ERROR` or `:WARN`
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
since the logging code of klang isn't too small.
Hence, in production I want to log a subset of messages (such as warn/error/info
but *not* trace/debug) to be pushed into a global core.async channel where I can
send them to my server (or wherever) in case an error occurs.

This library *could* offer this functionality but I think that most
developers will have a slight different opinion on what to do with
their logs so it's best to just write them yourself.
This is why I've chosen to give this recipe that only has a few lines
of code and everybody can adapt it to their needs:

```clj
;; This requires Clojure 1.7 due to the use of transducers. But it can
;; be modified easily to use simple functions.
;; This macro file (.clj) is included in both, your production and dev
;; environment.
;; You'll call them differently by using different :source-paths in your
;; leiningen conf

;; The global atom holds the filters/transducers that determine if the log! call
;; should be elided, cod
(defonce filters (atom []))

(defmacro log!
  [type & msg]
  )

```

Using this macro indirection would also allow you to include filename and line
number (meta data of `&form` in a macro) for each log message. Feel free to do
so and send a pull request for this section.

## Server mode
In this use case you're only interested in viewing logs in a browser
window but the browser window itself does not host a client app that
is interested in logging.

For instance, you're forwarding all your logs from your clojure
backend app to Klang. You may also have multiple backends or even your
browser app to also forward the logging to one klang instance.

This mean that you'll have a central location (the browser app running
klang) where you display your client and server-side logging data in
realtime.

### Timbre
For instance writing a custom appender in timbre (TODO) could push the log
messages to a browser window and then displaying them with Klang. The times of
your log message wouldn't be touched since you can supply a date/time with
`raw-log!`.

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
By default 

You can find an example of how to use that function in the source code of this
library.

## Listening for logs
Every log is pushed on a `mult` channel (see core.async docs) which you can tab
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
