(defproject klang "0.1.0-SNAPSHOT"
  :description "A cljs logger and viewer"
  :url "http://www.github.com/rauhs/klang"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src/clj" "src/cljs"]

  :dependencies [;; CORE
                 ;;[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojure "1.7.0-beta1"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/clojurescript "0.0-3196" :scope "provided"]
                 ;; CLJS
                 ;;[cljsjs/react "0.13.1-0"]
                 ;;[reagent "0.5.0" :exclusions [cljsjs/react]]
                 [reagent "0.5.0"]
                 [com.andrewmcveigh/cljs-time "0.3.3"]
                 ;;[reagent-forms "0.4.9"]
                 ;;[reagent-utils "0.1.4"];;cookies,crypt,validation,session
                 [cljsjs/highlight "8.4-0"] ;; for code highlighting
                 ;;[im.chit/purnam "0.5.1"];; js interop
                 ];; forgive me

  :plugins [[lein-cljsbuild "1.0.4"]
            [lein-environ "1.0.0"]
            [lein-ring "0.9.1"]
            [lein-asset-minifier "0.2.2"]]
  
  ;; Global exclusions are applied across the board, as an alternative
  ;; to duplication for multiple dependencies with the same excluded libraries.
  :exclusions [org.clojure/tools.nrepl]

  ;;:ring {:handler klang.handler/app
         ;;:uberwar-name "klang.war"}

  :min-lein-version "2.5.0"

  :uberjar-name "klang.jar"

  :main klang.server
  
  ;; Some speedup
  ;; https://github.com/technomancy/leiningen/wiki/Faster
  :jvm-opts ["-Xverify:none"]

  :clean-targets ^{:protect false} ["resources/public/js"]

  :minify-assets
  {:assets
   {"resources/public/css/site.min.css"
    "resources/public/css/site.css"}}

  :cljsbuild
  {:builds
   {:app {:source-paths ["src/cljs"]
          :compiler {:output-to     "resources/public/js/app.js"
                     :output-dir    "resources/public/js/out"
                     :asset-path   "js/out"
                     :optimizations :none
                     :pretty-print  true}}
    :prod_debug {:source-paths ["src/cljs"]
                 :compiler {:output-to     "resources/public/cljs/production_debug/app.js"
                            :output-dir    "resources/public/cljs/production_debug/out"
                            :asset-path   "js/out"
                            :output-wrapper false
                            :pseudo-names true
                            :optimizations :advanced
                            :pretty-print  true}}
    :demo {:source-paths ["src/cljs" "demo"]
                 :compiler {:output-to     "resources/public/cljs/demo/app.js"
                            :output-dir    "resources/public/cljs/demo/out"
                            :asset-path   "js/out"
                            :output-wrapper false
                            ;;:static-fns true
                            :pseudo-names false
                            :optimizations :simple
                            :pretty-print  false}}
    :prod {:source-paths ["src/cljs"]
           :compiler {:output-to     "resources/public/cljs/production/app.js"
                      :output-dir    "resources/public/cljs/production/out"
                      :asset-path   "js/out"
                      :output-wrapper false
                      :static-fns true ;; should be true by default
                      :optimizations :advanced
                      :pretty-print  false}}}}
  :profiles
  {:dev
   {:repl-options {:init-ns klang.dev
                   :nrepl-middleware
                   [
                    cider.nrepl.middleware.apropos/wrap-apropos
                    cider.nrepl.middleware.classpath/wrap-classpath
                    ;; Wrapcomplete will also start piggieback!
                    ;; So we need to depend on it above but not include it
                    cider.nrepl.middleware.complete/wrap-complete
                    cider.nrepl.middleware.format/wrap-format
                    cider.nrepl.middleware.info/wrap-info
                    cider.nrepl.middleware.inspect/wrap-inspect
                    cider.nrepl.middleware.macroexpand/wrap-macroexpand
                    cider.nrepl.middleware.ns/wrap-ns
                    cider.nrepl.middleware.pprint/wrap-pprint
                    cider.nrepl.middleware.resource/wrap-resource
                    cider.nrepl.middleware.stacktrace/wrap-stacktrace
                    cider.nrepl.middleware.test/wrap-test
                    cider.nrepl.middleware.trace/wrap-trace
                    cider.nrepl.middleware.undef/wrap-undef
                    ;;cemerick.piggieback/wrap-cljs-repl
                    ]}

    :dependencies [[ring-mock "0.1.5"]
                   [ring/ring-devel "1.3.2"]
                   [cider/cider-nrepl "0.9.0-SNAPSHOT"]
                   [org.clojure/tools.nrepl "0.2.10"]
                   ;; Clojure
                   [ring "1.3.2"]
                   [ring/ring-defaults "0.1.4"]
                   [prone "0.8.1"];;magnars/prone: Pretty ring exceptions
                   [compojure "1.3.3"]
                   [selmer "0.8.2"];;Django inspired templates
                   [environ "1.0.0"];;Environment variables
                   [leiningen "2.5.1"]
                   [figwheel "0.2.6"]
                   ;;[figwheel-sidecar "0.2.5"]
                   ;;[cljs-tooling "0.1.5-SNAPSHOT"] ;; Cljs autocomplete
                   [weasel "0.6.0"]
                   ;;[com.cemerick/piggieback "0.2.1-SNAPSHOT"]
                   [com.cemerick/piggieback "0.1.6-SNAPSHOT"]
                   [pjstadig/humane-test-output "0.7.0"]]

    ;; All the clojure containing files. Note we also need to add cljs since it
    ;; contains some debugging macros for cljs (but CLJS has macros only through
    ;; clojure)
    :source-paths ["env/dev/clj" "env/dev/cljs" "demo"]
    :plugins [[lein-figwheel "0.2.6"]]

    :injections [(require 'pjstadig.humane-test-output)
                 (pjstadig.humane-test-output/activate!)]

    :figwheel {:http-server-root "public" ;; But really resources/public
               ;;:nrepl-port 7888 ;; Not working
               ;;:repl false
               :server-port 3449
               :css-dirs ["resources/public/css"]
               ;;:ring-handler klang.handler/app
               }

    :env {:dev? true}

    :cljsbuild
    {:builds
     {:app
      {:source-paths ["env/dev/cljs" "demo"]
       :compiler {:main "klang.dev"
                  :source-map true}}}}}

   :uberjar
   {:hooks [leiningen.cljsbuild minify-assets.plugin/hooks]
    :env {:production true}
    :aot :all
    :omit-source true
    :cljsbuild
    {:jar true
     :builds {:app
              {:source-paths ["env/prod/cljs"]
               :compiler
               {:optimizations :advanced
                :pretty-print false}}}}}})
