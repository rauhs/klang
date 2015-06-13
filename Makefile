.PHONY: demo

# Seems to not work:
CLJS=~/.m2/repository/org/clojure/clojurescript/0.0-3169/clojurescript-0.0-3169.jar

# Download the release from github:
CLJS_JAR=https://github.com/clojure/clojurescript/releases/download/r3208/cljs.jar

man-build:
	java -cp cljs.jar:src clojure.main manual-build.clj

prod:
	lein cljsbuild auto prod

clean :
	lein cljsbuild clean
	lein clean

print-%: ; @echo $*=$($*)


demo :
	echo "<html><head><title>Klang logging demo</title></head>" > demo.html
	echo "<body><script>" >> demo.html
	cat ./resources/public/cljs/demo/app.js >> demo.html
	echo "</script></body></html>" >> demo.html

debug-jar :
	rm -rf jar_extract
	mkdir -p jar_extract
	cp target/klang.jar jar_extract
	cd jar_extract && jar xf klang.jar

