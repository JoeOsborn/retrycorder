(ns retrycorder.atom.core)

(def app (js/require "app"))
(def browser-window (js/require "browser-window"))
(def crash-reporter (js/require "crash-reporter"))
(def global-shortcut (js/require "global-shortcut"))

(def main-window (atom nil))

(defn init-browser []
  (reset! main-window (browser-window.
                        (clj->js {:width            800
                                  :height           600
                                  :resizable        true
                                  :use-content-size false
                                  :title            "Retrycorder"
                                  :fullscreen       false
                                  :web-preferences  {:text-areas-are-resizable true}})))
  ; Path is relative to the compiled js file (main.js in our case)
  (.loadUrl @main-window (str "file://" js/__dirname "/public/index.html") #js {:ignoreCache true})
  (.openDevTools @main-window #js {:detach true})
  (.register global-shortcut
             "ctrl+r"
             (fn [_]
               (.log js/console "save" @main-window)
               (.send @main-window "commands" "save")))
  (.register global-shortcut
             "ctrl+y"
             (fn [_]
               (.log js/console "back")
               (.send @main-window "commands" "back")))
  (.register global-shortcut
             "ctrl+return"
             (fn [_]
               (.log js/console "end")
               (.send @main-window "commands" "end")))
  (.on @main-window "closed" #(reset! main-window nil)))

(.start crash-reporter)
(.on app "window-all-closed" #(.quit app))
(.on app "ready" init-browser)
