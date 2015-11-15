(defproject retrycorder "0.1.0-SNAPSHOT"
  :description "Screen-recording with retries. Save only the best sequence."
  :url ""
  :license {:name "GNU Public License version 3"
            :url  ""}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.48"]
                 [org.clojure/core.async "0.2.374"]
                 [sablono "0.3.6"]
                 [org.omcljs/om "0.9.0"]
                 [figwheel "0.4.0"]
                 [figwheel-sidecar "0.4.0"]]


  :plugins [[lein-cljsbuild "1.1.0"]
            [lein-figwheel "0.4.0"]]

  :source-paths ["src/tools"]

  :clean-targets ^{:protect false} ["resources/public/js/ui-core"
                                    "resources/public/js/ui-core.js"
                                    "target"]

  :cljsbuild {
              :builds
              [{:source-paths ["src/atom"],
                :id           "atom-dev",
                :compiler     {:output-to            "resources/main.js",
                               :optimizations        :simple
                               :pretty-print         true
                               :cache-analysis       true
                               :source-map-timestamp true}}
               {:source-paths ["src/frontend"],
                :id           "frontend-dev",
                :figwheel     {:on-jsload  "retrycorder.ui.core/on-js-reload"
                               :nrepl-port 7889}
                :compiler     {:main                 "retrycorder.ui.core"
                               :asset-path           "js/ui-out"
                               :output-dir           "resources/public/js/ui-out"
                               :output-to            "resources/public/js/ui-core.js",
                               :optimizations        :none
                               ; :pretty-print true
                               :source-map-timestamp true
                               :cache-analysis       true}}
               ;{:source-paths [],
               ; :id           "lib"}
               ]}
  :figwheel {:ring-handler figwheel-middleware/app
             :server-port  3449
             :css-dirs     ["resources/public/css"]
             ;; To be able to open files in your editor from the heads up display
             ;; you will need to put a script on your path.
             ;; that script will have to take a file path and a line number
             ;; ie. in  ~/bin/myfile-opener
             ;; #! /bin/sh
             ;; emacsclient -n +$2 $1
             ;;
             ;; :open-file-command "myfile-opener"
             })
