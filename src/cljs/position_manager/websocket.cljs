(ns position-manager.websocket
  "WebSocket connection management."
  (:require [re-frame.core :as rf]
            [position-manager.config :as config]))

(defonce ^:private ws-instance (atom nil))

(defn- on-open [_event]
  (rf/dispatch [:ws/connected]))

(defn- on-close [_event]
  (reset! ws-instance nil)
  (rf/dispatch [:ws/disconnected]))

(defn- on-error [event]
  (rf/dispatch [:ws/error (.-message event)]))

(defn- on-message [event]
  (rf/dispatch [:ws/message (.-data event)]))

(defn connect!
  "Establish WebSocket connection."
  [url]
  (when @ws-instance
    (.close @ws-instance))
  
  (rf/dispatch [:ws/connecting url])
  
  (try
    (let [ws (js/WebSocket. url)]
      (set! (.-onopen ws) on-open)
      (set! (.-onclose ws) on-close)
      (set! (.-onerror ws) on-error)
      (set! (.-onmessage ws) on-message)
      (reset! ws-instance ws))
    (catch js/Error e
      (rf/dispatch [:ws/error (.-message e)]))))

(defn disconnect!
  "Close WebSocket connection."
  []
  (when-let [ws @ws-instance]
    (.close ws)
    (reset! ws-instance nil)))

(defn send!
  "Send message over WebSocket."
  [data]
  (when-let [ws @ws-instance]
    (when (= 1 (.-readyState ws))  ; OPEN
      (.send ws (js/JSON.stringify (clj->js data))))))

(defn connected?
  "Check if WebSocket is connected."
  []
  (when-let [ws @ws-instance]
    (= 1 (.-readyState ws))))

;; Re-frame effects

(rf/reg-fx
 :ws/connect
 (fn [url]
   (connect! url)))

(rf/reg-fx
 :ws/disconnect
 (fn [_]
   (disconnect!)))

(rf/reg-fx
 :ws/send
 (fn [data]
   (send! data)))
