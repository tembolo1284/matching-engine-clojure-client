(ns me-client.relay.engine
  "Engine connection manager for the relay.
   
   Connects to the matching engine via TCP, UDP, or multicast,
   filters messages by type, and dispatches to registered handlers."
  (:require [me-client.transport :as transport]
            [me-client.protocol :as proto]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private state
  (atom {:transport nil
         :reader-thread nil
         :handlers []
         :filter #{:trade :order-ack :order-reject :cancel-ack :cancel-reject}
         :stats {:received 0
                 :filtered 0
                 :dispatched 0
                 :errors 0}
         :running? false}))

;; =============================================================================
;; Statistics
;; =============================================================================

(defn stats
  "Get current statistics."
  []
  (:stats @state))

(defn reset-stats!
  "Reset statistics counters."
  []
  (swap! state assoc :stats {:received 0
                             :filtered 0
                             :dispatched 0
                             :errors 0}))

(defn- inc-stat! [k]
  (swap! state update-in [:stats k] inc))

;; =============================================================================
;; Message Handlers
;; =============================================================================

(defn add-handler!
  "Register a message handler function.
   Handler receives filtered messages and should not block.
   Returns a handler-id for removal."
  [handler-fn]
  (let [id (java.util.UUID/randomUUID)]
    (swap! state update :handlers conj {:id id :fn handler-fn})
    id))

(defn remove-handler!
  "Remove a handler by its ID."
  [handler-id]
  (swap! state update :handlers
         (fn [handlers]
           (vec (remove #(= (:id %) handler-id) handlers)))))

(defn clear-handlers!
  "Remove all handlers."
  []
  (swap! state assoc :handlers []))

(defn- dispatch!
  "Dispatch a message to all registered handlers."
  [msg]
  (doseq [{:keys [fn]} (:handlers @state)]
    (try
      (fn msg)
      (inc-stat! :dispatched)
      (catch Exception e
        (inc-stat! :errors)
        (binding [*out* *err*]
          (println "Handler error:" (.getMessage e)))))))

;; =============================================================================
;; Message Processing
;; =============================================================================

(defn set-filter!
  "Set the message type filter.
   Only messages with types in this set will be dispatched."
  [type-set]
  (swap! state assoc :filter type-set))

(defn get-filter
  "Get current message type filter."
  []
  (:filter @state))

(defn- process-message!
  "Process an incoming message: filter and dispatch."
  [msg]
  (inc-stat! :received)
  (let [msg-type (:type msg)
        allowed? (contains? (:filter @state) msg-type)]
    (if allowed?
      (dispatch! msg)
      (inc-stat! :filtered))))

;; =============================================================================
;; Reader Loop
;; =============================================================================

(defn- reader-loop
  "Main loop that reads from transport and processes messages."
  []
  (let [tp (:transport @state)]
    (while (:running? @state)
      (try
        (when-let [msg (transport/recv-msg! tp)]
          (process-message! msg))
        (catch java.net.SocketTimeoutException _
          ;; Normal timeout, continue
          nil)
        (catch Exception e
          (when (:running? @state)
            (inc-stat! :errors)
            (binding [*out* *err*]
              (println "Reader error:" (.getMessage e)))
            ;; Brief pause before retry
            (Thread/sleep 100)))))))

;; =============================================================================
;; Connection Lifecycle
;; =============================================================================

(defn connect!
  "Connect to the matching engine.
   
   Options:
     :host      - engine host (default: localhost)
     :port      - engine port (default: 1234)
     :transport - :tcp, :udp, or :multicast (default: :tcp)
     :multicast-group - required for multicast transport
     :multicast-iface - optional network interface for multicast
     :filter    - set of message types to relay"
  [{:keys [host port transport-type multicast-group multicast-iface filter]
    :or {host "localhost"
         port 1234
         transport-type :tcp}}]
  (when (:running? @state)
    (throw (ex-info "Already connected" {})))
  
  ;; Create transport
  (let [tp (case transport-type
             :tcp
             (transport/tcp-connect host port)
             
             :udp
             (transport/udp-connect host port)
             
             :multicast
             (if multicast-group
               (transport/multicast-join multicast-group port
                                         :interface multicast-iface)
               (throw (ex-info "Multicast requires :multicast-group" {}))))]
    
    ;; Update state
    (swap! state assoc
           :transport tp
           :running? true)
    
    (when filter
      (set-filter! filter))
    
    ;; Start reader thread
    (let [thread (Thread. ^Runnable reader-loop "engine-reader")]
      (.setDaemon thread true)
      (.start thread)
      (swap! state assoc :reader-thread thread))
    
    {:transport transport-type
     :host (if (= transport-type :multicast) multicast-group host)
     :port port}))

(defn disconnect!
  "Disconnect from the matching engine."
  []
  (swap! state assoc :running? false)
  
  ;; Close transport
  (when-let [tp (:transport @state)]
    (try
      (transport/close! tp)
      (catch Exception _)))
  
  ;; Wait for reader thread
  (when-let [thread (:reader-thread @state)]
    (try
      (.join thread 1000)
      (catch Exception _)))
  
  (swap! state assoc
         :transport nil
         :reader-thread nil)
  
  :disconnected)

(defn connected?
  "Check if connected to engine."
  []
  (and (:running? @state)
       (when-let [tp (:transport @state)]
         (transport/connected? tp))))

(defn connection-info
  "Get current connection information."
  []
  (when (connected?)
    {:running? true
     :stats (stats)
     :filter (get-filter)
     :handlers (count (:handlers @state))}))

;; =============================================================================
;; Convenience
;; =============================================================================

(defn start-relay!
  "Convenience function to connect and register a broadcast handler.
   
   Example:
     (start-relay! {:host \"localhost\" :port 1234}
                   (fn [msg] (ws/broadcast! msg)))"
  [connect-opts handler-fn]
  (let [conn (connect! connect-opts)]
    (add-handler! handler-fn)
    conn))

(defn stop-relay!
  "Stop relay: disconnect and clear handlers."
  []
  (disconnect!)
  (clear-handlers!)
  (reset-stats!))
