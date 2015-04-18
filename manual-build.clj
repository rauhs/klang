(require 'cljs.closure)

(cljs.closure/build
 "src/cljs"
 {:output-to "out_advanced/main.js"
  :optimizations :advanced})

