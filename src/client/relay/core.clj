(ns client.relay.core
  "Main entry point for the Matching Engine Relay Server.
   
   Connects to the matching engine, filters messages, and broadcasts
   to WebSocket clients. Optionally serves the ClojureScript UI."
  (:require [client.relay.config :as config]
            [client.relay.websocket :as ws]
            [client.relay.engine :as engine]
            [client.protocol :as proto])
  (:gen-class))

;; =============================================================================
;; Logging
;; =============================================================================

(def ^:private log-levels {:debug 0 :info 1 :warn 2 :error 3})
(defonce ^:private current-log-level (atom :info))

(defn- log [level & args]
  (when (>= (log-levels level) (log-levels @current-log-level))
    (let [timestamp (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss.SSS")
                             (java.time.LocalTime/now))
          prefix (format "[%s] [%s]" timestamp (name level))]
      (println (apply str prefix " " (map str args))))))

(defn- log-debug [& args] (apply log :debug args))
(defn- log-info [& args] (apply log :info args))
(defn- log-warn [& args] (apply log :warn args))
(defn- log-error [& args] (apply log :error args))

;; =============================================================================
;; Message Handler
;; =============================================================================

(defn- make-broadcast-handler
  "Create handler that broadcasts messages to WebSocket clients."
  []
  (fn [msg]
    (log-debug "Broadcasting: " (proto/format-message msg))
    (ws/broadcast! msg)))

;; =============================================================================
;; WebSocket Event Handlers
;; =============================================================================

(defn- on-client-connect [client-id]
  (log-info "Client connected: " client-id " (total: " (ws/client-count) ")"))

(defn- on-client-disconnect [client-id status]
  (log-info "Client disconnected: " client-id " status=" status " (total: " (ws/client-count) ")"))

(defn- on-client-message [client-id message]
  (log-debug "Client " client-id " sent: " message)
  ;; Could handle client commands here (subscribe/unsubscribe, etc.)
  )

;; =============================================================================
;; Server Lifecycle
;; =============================================================================

(defonce ^:private shutdown-hook (atom nil))

(defn- add-shutdown-hook! []
  (let [hook (Thread. ^Runnable
               (fn []
                 (log-info "Shutting down...")
                 (engine/stop-relay!)
                 (ws/stop!)))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (reset! shutdown-hook hook)))

(defn start!
  "Start the relay server with the given configuration."
  [config]
  (reset! current-log-level (:log-level config))
  
  (log-info "Starting Matching Engine Relay")
  (log-info "Engine: " (:engine-host config) ":" (:engine-port config)
            " via " (name (:engine-transport config)))
  (log-info "WebSocket: " (:ws-host config) ":" (:ws-port config))
  (log-info "Filter: " (pr-str (:message-filter config)))
  
  ;; Start WebSocket server
  (ws/start! {:host (:ws-host config)
              :port (:ws-port config)
              :serve-static? (:serve-static config)
              :on-connect on-client-connect
              :on-message on-client-message
              :on-disconnect on-client-disconnect})
  (log-info "WebSocket server started on " (:ws-host config) ":" (:ws-port config))
  
  ;; Connect to engine
  (let [connect-opts {:host (:engine-host config)
                      :port (:engine-port config)
                      :transport-type (:engine-transport config)
                      :multicast-group (:multicast-group config)
                      :multicast-iface (:multicast-iface config)
                      :filter (:message-filter config)}]
    (engine/start-relay! connect-opts (make-broadcast-handler)))
  (log-info "Connected to matching engine")
  
  ;; Register shutdown hook
  (add-shutdown-hook!)
  
  {:ws-server (ws/server-info)
   :engine (engine/connection-info)})

(defn stop!
  "Stop the relay server."
  []
  (log-info "Stopping relay...")
  (engine/stop-relay!)
  (ws/stop!)
  (when-let [hook @shutdown-hook]
    (try
      (.removeShutdownHook (Runtime/getRuntime) hook)
      (catch Exception _)))
  (log-info "Relay stopped"))

;; =============================================================================
;; Status
;; =============================================================================

(defn status
  "Get current relay status."
  []
  {:ws-server (ws/server-info)
   :engine (engine/connection-info)
   :clients (ws/client-info)})

(defn print-status
  "Print formatted status to stdout."
  []
  (let [s (status)]
    (println)
    (println "╔════════════════════════════════════════════════════════════╗")
    (println "║              MATCHING ENGINE RELAY STATUS                  ║")
    (println "╠════════════════════════════════════════════════════════════╣")
    (if-let [ws (:ws-server s)]
      (do
        (println (format "║  WebSocket: ws://%s:%d%s"
                        (:host ws) (:port ws)
                        (apply str (repeat (- 35 (count (str (:host ws))) 
                                              (count (str (:port ws)))) " ")) "║"))
        (println (format "║  Clients:   %-45d ║" (:clients ws)))
        (println (format "║  Uptime:    %-45s ║" 
                        (str (quot (:uptime-ms ws) 1000) "s"))))
      (println "║  WebSocket: NOT RUNNING                                    ║"))
    (println "╠════════════════════════════════════════════════════════════╣")
    (if-let [eng (:engine s)]
      (let [stats (:stats eng)]
        (println (format "║  Engine:    %-45s ║" (if (:running? eng) "CONNECTED" "DISCONNECTED")))
        (println (format "║  Received:  %-45d ║" (:received stats)))
        (println (format "║  Relayed:   %-45d ║" (:dispatched stats)))
        (println (format "║  Filtered:  %-45d ║" (:filtered stats)))
        (println (format "║  Errors:    %-45d ║" (:errors stats))))
      (println "║  Engine:    NOT CONNECTED                                  ║"))
    (println "╚════════════════════════════════════════════════════════════╝")
    (println)))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main entry point for the relay server."
  [& args]
  (let [config (config/load-config args)]
    (cond
      ;; Help requested
      (:help config)
      (do
        (config/print-help)
        (System/exit 0))
      
      ;; Validate config
      :else
      (let [[valid? errors] (config/validate config)]
        (if-not valid?
          (do
            (doseq [err errors]
              (log-error err))
            (System/exit 1))
          
          ;; Start server
          (do
            (start! config)
            (println)
            (println "Relay server running. Press Ctrl+C to stop.")
            (println "WebSocket endpoint: ws://" (:ws-host config) ":" (:ws-port config) "/ws")
            (when (:serve-static config)
              (println "UI available at: http://" (:ws-host config) ":" (:ws-port config) "/"))
            (println)
            
            ;; Block forever (until shutdown)
            (while true
              (Thread/sleep 10000)
              (when (= :debug @current-log-level)
                (print-status)))))))))
