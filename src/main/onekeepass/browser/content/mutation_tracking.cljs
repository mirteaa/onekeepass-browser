(ns onekeepass.browser.content.mutation-tracking
  (:require
   [applied-science.js-interop :as j]
   [clojure.string :as str]))

;; A login form can be added dynamically (SPAs, inline sign-in widgets), so the
;; content script watches the body subtree. A churny page (YouTube, feeds, ads)
;; can add hundreds of divs per second, though, and a full-page detection pass
;; for each of them starves the main thread and makes video playback stutter.
;; Two safeguards keep this cheap:
;;   1. only added <form> / non-okp <div> nodes are relevant; and
;;   2. relevant additions arm one detection pass for a fixed window.
;; This is a throttle, not a reset-debounce: continuous churn still produces one
;; pass per window, so a login that appears during churn is eventually found.
(def ^:private detect-throttle-ms 400)

(defn- relevant-added-node? [node]
  (when-let [tag (j/get node :tagName)]
    (let [tag (str/lower-case tag)]
      (or (= tag "form")
          (and (= tag "div")
               (not (str/starts-with? (or (j/get node :id) "") "okp-")))))))

(defn- relevant-mutations? [mutation-list]
  (some (fn [mutation]
          (and (= (j/get mutation :type) "childList")
               (some relevant-added-node?
                     (array-seq (j/get mutation :addedNodes)))))
        (array-seq mutation-list)))

(defn inputs-on-dom-observation
  "Observes the body subtree for dynamically added login containers and calls
   'callback-on-observe' (with no args) at most once per throttle window. The
   callback is deliberately passed no node: callers run a cheap gate first and
   only pay for a full page scan when the page actually has a password field."
  [callback-on-observe]
  ;; body may be HTMLBodyElement or HTMLFrameSetElement or nil
  (let [body (j/get js/document :body)]
    (when (instance? js/HTMLBodyElement body)
      (let [timer (atom nil)
            run (fn []
                  (reset! timer nil)
                  (callback-on-observe))
            on-mutations (fn [mutation-list _observer]
                           ;; Arm a pass only when one is not already pending.
                           ;; Mutations during the window are absorbed by the
                           ;; single pass, then the next relevant mutation
                           ;; re-arms it - never starved by constant churn.
                           (when (and (nil? @timer)
                                      (relevant-mutations? mutation-list))
                             (reset! timer (js/setTimeout run detect-throttle-ms))))
            observer (js/MutationObserver. on-mutations)]
        (.observe observer body #js {:childList true :subtree true})))))
