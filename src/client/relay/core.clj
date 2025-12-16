(ns client.relay.core
  "Main entry point for the relay server."
  (:require [client.relay.config :as config]
            [client.relay.engine :as engine]
            [client.relay.websocket :as ws])
  (:gen-class))

(defn -main [& args]
  (let [cfg (config/load-config args)]
    
    ;; Help
    (when (:help cfg)
      (config/print-help)
      (System/exit 0))
    
    ;; Validate
    (let [[ok? errors] (config/validate cfg)]
      (when-not ok?
        (doseq [e errors]
          (println "[error]" e))
        (System/exit 1)))
    
    ;; Print config
    (println "[info] Starting Matching Engine Relay")
    (if (= :multicast (:engine-transport cfg))
      (println (format "[info] Multicast: %s:%d" 
                       (:multicast-group cfg) 
                       (:multicast-port cfg)))
      (println (format "[info] Engine: %s:%d via %s"
                       (:engine-host cfg)
                       (:engine-port cfg)
                       (name (:engine-transport cfg)))))
    (println (format "[info] WebSocket: %s:%d" (:ws-host cfg) (:ws-port cfg)))
    (println (format "[info] Filter: %s" (:message-filter cfg)))
    
    ;; Start WebSocket server
    (ws/start! (:ws-host cfg) (:ws-port cfg))
    
    ;; Connect broadcast function
    (engine/set-broadcast-fn! ws/broadcast!)
    
    ;; Connect to engine/multicast
    (engine/start! cfg)
    
    (println)
    (println "Relay server running. Press Ctrl+C to stop.")
    (println (format "WebSocket endpoint: ws://%s:%d/ws" (:ws-host cfg) (:ws-port cfg)))
    (println (format "UI available at: http://%s:%d/" (:ws-host cfg) (:ws-port cfg)))
    
    ;; Shutdown hook
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (engine/stop!)
                                    (ws/stop!))))
    
    ;; Keep alive
    (while true
      (Thread/sleep 10000))))
