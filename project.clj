(defproject klang "0.5.5"
  :description "A cljs logger and viewer"
  :url "http://www.github.com/rauhs/klang"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :source-paths ["src/cljs"]

  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "0.0-3308" :classifier "aot" :scope "provided"]
                 [cljsjs/highlight "8.4-0"]]

  :repositories [["clojars" {:sign-releases false}]]
  :plugins [[lein-cljsbuild "1.0.4"]
            [lein-environ "1.0.0"]
            [lein-ring "0.9.1"]]
  
  ;; Global exclusions are applied across the board, as an alternative
  ;; to duplication for multiple dependencies with the same excluded libraries.
  :exclusions [org.clojure/tools.nrepl]

  :min-lein-version "2.5.0"

  :uberjar-name "klang.jar"

  :jvm-opts ["-Xverify:none"]

  :clean-targets ^{:protect false} ["resources/public/js"]

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

   :jar-exclusions     [#"resources" #"demo" #"docs" #"env" #"public" #"test" #"main" #"\.swp" #"templates"]
   :uberjar {:hooks [leiningen.cljsbuild]
             ;;:hooks [leiningen.cljsbuild]
             ;;:env {:production true}
             :aot :all
             ;;:resource-paths [];; no resources
             :omit-source false
             :source-paths ["src/cljs"]})
