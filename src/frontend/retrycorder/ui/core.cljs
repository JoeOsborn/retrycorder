(ns ^:figwheel-always retrycorder.ui.core
  (:require #_[cljs.core.async :as async]
    [clojure.browser.dom]
    [om.core :as om :include-macros true]
    [om.dom :as dom :include-macros true]
    [clojure.string :as string]))

(defonce remote (js/require "remote"))
(defonce app (.require remote "app"))
(defonce ipc (js/require "ipc"))
(defonce process (js/require "child_process"))

(enable-console-print!)

(declare handle-command)
(defonce app-state (atom {:mode :ready, :data {}}))

(defn on-js-reload []
  (om/transact! (om/root-cursor app-state) [:__figwheel_counter] inc))

(defonce
  initialized?
  (do
    (println "Register for IPC")
    (.on ipc "commands" #(handle-command %))
    true))

(defn tempfile [nom]
  (str (.getPath app "temp") "/" nom))

(defn ffmpeg-start-command []
  ["ffmpeg" "-y"
   "-f" "avfoundation" "-r" "30" "-framerate" "30" "-capture_cursor" "1" "-i" "1:"
   "-vcodec" "h264" "-pix_fmt" "yuv420p" (tempfile "/temp.mov")])

(defn ffmpeg-splice-command [clips]
  ;ffmpeg
  ; -ss 4.3 -t 1.5 -i out.mov
  ; -ss 8.0 -t 2.0 -i out.mov
  ; -ss 16.0 -t 2.0 -i out.mov
  ; -filter_complex "[0][1][2]concat=n=3:v=1:a=0" spliced.mov
  (concat ["ffmpeg" "-y"]
          (flatten (map (fn [[s e]] ["-ss" s "-t" (- e s) "-i" (tempfile "temp.mov")]) clips))
          ["-filter_complex"
           (str (string/join "" (map #(str "[" %1 "]") (range 0 (count clips))))
                "concat=n=" (count clips)
                ":v=1:a=0")
           (tempfile "/spliced.mov")]))

(defn run-command [cmd when-finished]
  (println (string/join " " cmd))
  (let [proc (.spawn process (first cmd) (clj->js (rest cmd)))]
    (.on (.-stdout proc) "data" #(println "stdout:" %))
    (.on (.-stderr proc) "data" #(println "stderr:" %))
    (.on proc "error" #(println "spawn error:" %))
    (.on proc "close" #(do (println "proc closed:" %)
                           (when-finished)))
    proc))

(defn kill-proc [proc]
  (.kill proc))

(defn now []
  (/ (.now js/Date) 1000.0))

(defn shift-clips [clips]
  (rest (first (reduce (fn [[shifted offset] [c-start c-end]]
                         (println "offset" offset "last-shifted" (last shifted) "c" [c-start c-end])
                         (let [offset (- c-start (second (last shifted)))
                               c-start (- c-start offset)
                               c-end (- c-end offset)]
                           (println "new-offset" offset "shifted-c" [c-start c-end])
                           [(conj shifted [c-start c-end]) offset]))
                       [[[0 0]] 0]
                       clips))))

(defn handle-command [msg]
  (swap! app-state
         (fn [{mode :mode data :data :as a}]
           (let [now (now)]
             (case mode
               :ready
               (if (= msg "save")
                 (do
                   (let [proc (run-command (ffmpeg-start-command) #(handle-command "recording-finished"))]
                     (assoc a
                       :mode :recording
                       :data {:clips              []
                              :current-clip-start 0
                              :start-time         now
                              :commands           [[0 :save]]
                              :recorder           proc})))
                 a)
               :recording
               (let [{clips     :clips
                      commands  :commands
                      cur-start :current-clip-start
                      start     :start-time
                      recorder  :recorder} data
                     recnow (- now start)]
                 (case msg
                   "save"
                   (update a :data assoc
                           :clips (conj clips [cur-start recnow])
                           :current-clip-start recnow
                           :commands (conj commands [recnow (keyword msg)]))
                   "back"
                   (update a :data assoc
                           :current-clip-start recnow
                           :commands (conj commands [recnow (keyword msg)]))
                   "end"
                   (let [final-clips (conj clips [cur-start recnow])
                         duration (apply + (map (fn [[s e]] (- e s)) final-clips))]
                     (kill-proc recorder)
                     (assoc a
                       :mode :processing-recording
                       :data {:clips             (shift-clips final-clips)
                              :commands          (conj commands [recnow :end])
                              :duration          duration
                              :unedited-clips    final-clips
                              :unedited-duration recnow}))
                   a))
               :processing-recording
               (case msg
                 "recording-finished"
                 (let [splicer (run-command (ffmpeg-splice-command (:clips data)) #(handle-command "editing-finished"))]
                   (assoc a
                     :mode :processing-edits
                     :data (assoc data :splicer splicer)
                     ))
                 a)
               :processing-edits
               (case msg
                 "editing-finished"
                 (assoc a
                   :mode :finished
                   :data (dissoc data :splicer))
                 a)
               a)))))

(defn floor [a] (.floor js/Math a))

(defn pad-right [s filler min-len]
  (if (>= (.-length s) min-len)
    s
    (recur (str s filler) filler min-len)))

(defn pad-left [s filler min-len]
  (if (>= (.-length s) min-len)
    s
    (recur (str filler s) filler min-len)))

; max-time determines how unit padding happens.
; if it's <0, units will always be padded out to hours.
; if it's 0, units will never be padded.
; otherwise, units will be padded so that all timecodes
; have the same number of units as max-time.
(defn s->hms [full-s max-time-s]
  (let [frame (floor (* full-s 30))
        h (floor (/ frame 108000))
        frame (- frame (* h 108000))
        m (floor (/ frame 1800))
        frame (- frame (* m 1800))
        s (floor (/ frame 30))
        frame (- frame (* s 30))
        ; frame to millisecond = (seconds/frame) * frame * (milliseconds/second)
        millis (floor (* (/ 1 30) frame 1000))
        max-time (* max-time-s 30)
        max-time (if (< max-time 0) Infinity max-time)
        max-h (floor (/ max-time 108000))
        max-time (- max-time (* h 108000))
        max-m (floor (/ max-time 1800))
        max-time (- max-time (* m 1800))
        max-s (floor (/ max-time 30))]
    (str (if (> max-h 0) (str (pad-left (str h) "0" 2) ":") "")
         (if (or (> max-m 0) (> max-h 0)) (str (pad-left (str m) "0" 2) ":") "")
         (if (or (> max-s 0) (> max-m 0) (> max-h 0)) (str (pad-left (str s) "0" 2) ".") "")
         (pad-right (str millis) "0" 3))))

(defn vectize [elt-list]
  (loop [v [] i 0]
    (if (<= i (.-length elt-list))
      (recur (conj v (.item elt-list i)) (inc i))
      v)))

(defn video [[app-cursor file mode] owner]
  (reify
    om/IInitState
    (init-state [_]
      {:time 0, :playing false})
    om/IDidMount
    (did-mount [_]
      (let [container (.getDOMNode owner)
            video-elt (.-firstChild container)
            rerender #(om/refresh! owner)]
        (.addEventListener video-elt "canplay" rerender)
        (.addEventListener video-elt "loadeddata" rerender)
        (.addEventListener video-elt "loadedmetadata" rerender)
        (.addEventListener video-elt "timeupdate" #(om/set-state! owner [:time] (.-currentTime video-elt)))
        (.addEventListener video-elt "play" #(om/set-state! owner [:playing] true))
        (.addEventListener video-elt "pause" #(om/set-state! owner [:playing] false))))
    om/IRenderState
    (render-state [_ {playing :playing time :time}]
      (let [duration (if (.isMounted owner)
                       (.-duration (.-firstChild (.getDOMNode owner)))
                       1)
            time-points (case mode :edited (map first (rest (get-in app-cursor [:data :clips])))
                                   :full (map first (get-in app-cursor [:data :commands])))
            btn-pct 5
            seek #(let [container (.getDOMNode owner)
                        video-elt (.-firstChild container)
                        duration (.-duration video-elt)
                        clicked-x (.-pageX %)
                        clicked-ratio (* 100 (/ clicked-x (.-clientWidth (.-body js/document))))
                        clicked-ratio (- clicked-ratio btn-pct)
                        clicked-ratio (/ clicked-ratio (- 100 btn-pct))
                        clicked-time (* clicked-ratio duration)]
                   (set! (.-currentTime video-elt) clicked-time)
                   (.pause video-elt))
            time->pct (fn [t] (str (+ btn-pct (* (/ t duration) (- 100 btn-pct))) "%"))]
        ;(println (subs file (- (.-length file) 8)) "dur" duration "times" time-points)
        (dom/div #js {}
                 (dom/video (clj->js {:controls false :preload "metadata" :src file :style {:width "100%"}}))
                 (apply dom/div #js {:style #js {:width "100%" :height "32px" :position "relative"}}
                        (dom/div #js {:onClick #(let [container (.getDOMNode owner)
                                                      video-elt (.-firstChild container)
                                                      playing (om/get-state owner [:playing])]
                                                 (if playing
                                                   (.pause video-elt)
                                                   (.play video-elt)))
                                      :style   #js {:backgroundColor "blue"
                                                    :color "white"
                                                    :position        "absolute"
                                                    :left            0 :top 0
                                                    :width           (str btn-pct "%") :height "32px"
                                                    :textAlign       "center"
                                                    :MozUserSelect   "none"
                                                    :MsUserSelect    "none"
                                                    :userSelect      "none"
                                                    :cursor          "default"}}
                                 (if playing "| |" "|>"))
                        (dom/div #js {:onMouseDown seek
                                      :onMouseMove #(when (= 1 (.-buttons %)) (seek %))
                                      :style       #js {:backgroundColor "grey"
                                                        :width           (str (- 100 btn-pct) "%") :height "32px"
                                                        :position        "absolute"
                                                        :left            (str btn-pct "%") :top 0
                                                        :MozUserSelect   "none"
                                                        :MsUserSelect    "none"
                                                        :userSelect      "none"
                                                        :cursor          "default"}})
                        (concat
                          (map #(dom/div #js {:style #js {:position        "absolute"
                                                          :left            (time->pct %) :top 0
                                                          :width           "2px" :height "32px"
                                                          :margin-left     "-1px"
                                                          :backgroundColor "black"
                                                          :pointerEvents   "none"}})
                               time-points)
                          [(dom/div #js {:style #js {:position        "absolute"
                                                     :left            (time->pct time) :top "2px"
                                                     :width           "8px" :height "28px"
                                                     :backgroundColor "white"
                                                     :margin-left     "-4px"
                                                     :pointerEvents   "none"
                                                     :MozUserSelect   "none"
                                                     :MsUserSelect    "none"
                                                     :userSelect      "none"
                                                     :cursor          "default"}})])
                        ))))))

(om/root
  (fn [data _owner]
    (reify om/IRender
      (render [_]
        (apply dom/div #js {}
               (dom/h1 #js {} "Retrycorder")
               (case (:mode data)
                 :ready
                 [(dom/p #js {} "Press ctrl-r to start recording.")
                  (dom/code #js {} (string/join " " (ffmpeg-start-command)))]
                 :recording
                 (let [duration (- (now) (get-in data [:data :start-time]))]
                   (concat [(dom/p #js {} "Press ctrl-r to set a save point.")
                            (dom/p #js {} "Press ctrl-y to jump back to the last save point.")
                            (dom/p #js {} "Press ctrl-return to finish recording.")]
                           [(dom/p #js {} (string/join ", " (map (fn [[t cmd]] (str (s->hms t duration) " : " (name cmd)))
                                                                 (get-in data [:data :commands]))))
                            (dom/code #js {} (string/join " " (ffmpeg-splice-command (get-in data [:data :clips]))))]
                           (mapv (fn [[s e]] (dom/p #js {} (str (s->hms s duration) "..." (s->hms e duration))))
                                 (get-in data [:data :clips]))
                           [(dom/p #js {} (str (s->hms (get-in data [:data :current-clip-start]) duration) "..."))]))
                 :processing-recording
                 [(dom/p #js {} "Waiting for recording to finish...")]
                 :processing-edits
                 [(dom/p #js {} "Waiting for edits to finish...")]
                 :finished
                 (let [duration (get-in data [:data :duration])
                       full-duration (get-in data [:data :unedited-duration])]
                   (concat [(dom/p #js {} (string/join ", " (map (fn [[t cmd]] (str (s->hms t duration) " : " (name cmd)))
                                                                 (get-in data [:data :commands]))))
                            (dom/code #js {} (string/join " " (ffmpeg-splice-command (get-in data [:data :unedited-clips]))))]
                           [(dom/p #js {} "Clips:")]
                           (mapv (fn [[s e]] (dom/p #js {} (str (s->hms s duration) "..." (s->hms e duration))))
                                 (get-in data [:data :clips]))
                           [(om/build video [data (tempfile "spliced.mov") :edited])
                            (dom/a (clj->js {:download "spliced-recording.mov" :href (tempfile "spliced.mov")}) "Save spliced video")]
                           [(dom/p #js {} "Unedited clips:")]
                           (mapv (fn [[s e]] (dom/p #js {} (str (s->hms s full-duration) "..." (s->hms e full-duration))))
                                 (get-in data [:data :unedited-clips]))
                           [(om/build video [data (tempfile "temp.mov") :full])
                            (dom/a (clj->js {:download "full-recording.mov" :href (tempfile "temp.mov")}) "Save full video")]
                           )))))))
  app-state
  {:target (.getElementById js/document "app")})
