# Klang - A Clojurescript logging libary/viewer

Simple logging library for clojurescript.

![Example](https://github.com/rauhs/klang/blob/master/docs/img/example.png)
![Example](https://github.com/rauhs/klang/blob/master/docs/img/demo_console.png)

# Demo
(Old) Demo is here: [Demo][].

# Features

* Central hub to push all log messages in your client app
* **Zero overhead and removal of all log function calls for production by eliding
  all calls with macros. No Klang code goes into your app.**
* Enter a search term to quickly find log messages
* Pausing the UI in case of many logs arriving. This will not discard
  the logs but buffer them.
* Click on any log message and dump the object to your javascript console (as an
  object). This allows you to inspect the object and even log functions and
  invoke them in your javascript console. See the demo.
  Also works great with [Devtools](https://github.com/binaryage/cljs-devtools)

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
understand linear trace of events.
The javascript console is not powerful enough, hence this library.

# Clojars

[![Clojars Project](http://clojars.org/klang/latest-version.svg)](http://clojars.org/klang)

# Usage

The following is the simplest usage, using no macros (see below for advanced
usage):
```clj
(ns your.app.somens
  (:require [klang.core :refer-macros [info! warn! erro! crit! fata! trac!]]))

;; Show/hide the logs:
(k/show!)
;; Or just press they `m` key
```

Note that if you use this in production facing code then you'll want
to use this library somewhat differently in order to elide any logging
for production (see below).

# Production eliding of log calls
In most use cases for web app development you'll want to remove logging data and
the overhead of Klang from your JS code for deployment.
For this you have to do two things:

1. Configure Klang to elide log calls you don't want.
2. Create your own CLJS function that get's called for the severe errors/warnings.
   There you can log them or send them to the server etc.

Klang offers:

* Setup of whitelist and blacklist (similar to timbre) to elide specific log
  calls. For instance: whitelist a namspace. Whitelist a specific `:type` of
  logs (only `:FATAL`). This means you specify which log macro calls generate
  cljs function calls.
* Optionally add line number and file name in your cljs file to every log call
* Optionally add local bindings that exist when you make a log call (by default
  enabled)
* Change the actual log function being called (for instance your own function
  instead of klang for production)

## Ways to configure

Klang gets configures by a single map. The default map is the following:

```clj
{:logger-fn 'klang.core/log!
;; Hold the keywords of which metadata of &form should be added to a log! call
;; Usually :file & :line are available
:form-meta #{}
;; Allow shortening namespaces:
:compact-ns? false
;; True if every macro call also attaches the environment (local bindings) to a
;; log call.
:meta-env? true
:default-emit? true
:whitelist ""
:blacklist ""}
```

You can change the behaviour of the macroexpansion of Klang by configuring it in
various ways.  All involve setting a Java system property.
Which you can set in leiningen like so:

```clj
{:jvm-opts ["-Dklang.config-file=klang-prod.edn"]}
```

1. Set  `klang.config-file=klang-prod.edn`. It should be on the classpath since
   the file will be called with `io/resource`
2. Set the Java defines:

   - `klang.logger-fn=klang.core/log!`
   - `klang.form-meta=\"#{:line :file}\"`
   - `klang.compact-ns=false`
   - `klang.meta-env=false`
   - `klang.trace=false`
   - `klang.default-emit=true`
   - `klang.whitelist=\"(ERRO|FATA|WARN)\"`
   - `klang.blaclist=\"TRAC\"`

The options are all `read-string`ed except the whitelist and blacklist.

The options mean:

`:logger-fn`: A symbol. This the CLJS function that gets called for the log calls. The
macro emit the proper call (or not if it's elided). 
The arguments of the `logger-fn` are:

1. `ns`, the namespace string
2. `severity`, the serverity as a string (like `"INFO"`, `"WARN"`)
3. `& args`, the rest of the message.

- `:form-meta`: A set. Can be set to `#{:file, :line}` to include file and line location
of the log call.

- `:trace`: If true, will include the stacktrace in the meta data of every log call.
You can then click on it in the JS console and jump to the sources of the stacktrace.

- `:compact-ns?`: If true the namespace will be shortend. `Foo.Barr.Bazzz.Wuzz`
will be `F.B.B.W`. Useful for production builds.

- `:meta-env?`: If true will include the local bindings in a map as the first
argument to the log function. These bindings can be inspected by clicking on
the log message in the overlay.

- `:default-emit?`: If neither whitelist nor blacklist kicks in, then this
decides if the log call should be emitted.

- `:whitelist`/`:blacklist`: A regular expression that can whitelist/blacklist
log calls. It gets matched to the string: `the-namespace/the-severity`.

## License

Copyright &copy; 2015-2017 Andre Rauh. Distributed under the
[Eclipse Public License][], the same as Clojure.


[Eclipse Public License]: <https://raw2.github.com/rauhs/klang/master/LICENSE>
[Wiki Macros]: <https://github.com/rauhs/klang/wiki/Flexible-macros-recipe>
[Demo]: <http://arauh.net/projects/klang/>

