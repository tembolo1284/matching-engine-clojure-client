(ns position-manager.config)

(goog-define DEBUG false)

(def default-ws-url
  (let [protocol (if (= "https:" js/window.location.protocol) "wss:" "ws:")
        host js/window.location.host]
    (str protocol "//" host "/ws")))

(def reconnect-delay-ms 3000)

(def max-trades 100)

(def max-log-entries 200)
