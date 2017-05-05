.PHONY: demo

man-build:
	java -cp cljs.jar:src clojure.main manual-build.clj

prod:
	lein cljsbuild auto prod

clean :
	lein cljsbuild clean
	lein clean

print-%: ; @echo $*=$($*)

demo-js:
	lein cljsbuild auto demo

# Requires make demo-js
demo :
	echo "<html><head><title>Klang logging demo</title></head>" > demo.html
	echo "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/react/15.5.4/react.min.js\"></script>" >> demo.html
	echo "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/react/15.5.4/react-dom.min.js\"></script>" >> demo.html
	echo "<body><script>" >> demo.html
	cat ./resources/public/cljs/demo/app.js >> demo.html
	echo "</script></body></html>" >> demo.html

debug-jar :
	rm -rf jar_extract
	mkdir -p jar_extract
	cp target/klang.jar jar_extract
	cd jar_extract && jar xf klang.jar

deploy :
	rm -rf target/classes
	rm -rf target/stale
	lein deploy clojars

