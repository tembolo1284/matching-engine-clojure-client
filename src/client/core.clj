(ns client.core
  "Interactive REPL client for the matching engine.
   
   Provides a friendly interface for sending orders and viewing responses.
   Uses shared protocol and transport modules."
  (:require [client.protocol :as proto]
            [client.transport :as transport])
  (:gen-class))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private state
  (atom {:transport nil
         :reader-thread nil
         :user-id 1
         :next-order-id 1
         :history []}))

(defn- next-order-id! []
  (let [id (:next-order-id @state)]
    (swap! state update :next-order-id inc)
    id))

(defn- add-to-history! [msg]
  (swap! state update :history
         (fn [h]
           (let [h' (conj h msg)]
             (if (> (count h') 100)
               (subvec h' (- (count h') 100))
               h')))))

(defn- print-msg [msg]
  (println (str "  " (proto/format-message msg)))
  (add-to-history! msg))

;; =============================================================================
;; Connection Management
;; =============================================================================

(defn start!
  "Connect to matching engine.
   
   Examples:
     (start!)                       ; localhost:1234 TCP
     (start! 9000)                  ; localhost:9000
     (start! \"host\" 1234)         ; custom host
     (start! {:transport :udp})     ; UDP transport"
  ([] (start! "localhost" 1234 {}))
  ([arg]
   (cond
     (number? arg) (start! "localhost" arg {})
     (map? arg) (start! "localhost" 1234 arg)
     (string? arg) (start! arg 1234 {})
     :else (start!)))
  ([host port] (start! host port {}))
  ([host port opts]
   (when-let [old (:transport @state)]
     (transport/close! old))
   
   (let [transport-type (or (:transport opts) :tcp)
         tp (transport/connect transport-type host port)]
     (swap! state assoc :transport tp)
     (println (format "Connected to %s:%d via %s" host port (name transport-type)))
     
     ;; Start reader thread
     (let [thread (Thread.
                   ^Runnable
                   (fn []
                     (while (and (:transport @state)
                                 (transport/connected? tp))
                       (try
                         (when-let [msg (transport/recv-msg! tp)]
                           (print-msg msg))
                         (catch Exception _)))))]
       (.setDaemon thread true)
       (.start thread)
       (swap! state assoc :reader-thread thread)))
   
   :connected))

(defn stop!
  "Disconnect from matching engine."
  []
  (when-let [tp (:transport @state)]
    (transport/close! tp)
    (swap! state assoc :transport nil :reader-thread nil)
    (println "Disconnected"))
  :disconnected)

(defn status
  "Show connection status."
  []
  (if-let [tp (:transport @state)]
    (if (transport/connected? tp)
      (println "Connected")
      (println "Disconnected (stale)"))
    (println "Not connected")))

;; =============================================================================
;; Order Commands
;; =============================================================================

(defn- send-order! [side symbol price qty]
  (when-let [tp (:transport @state)]
    (let [order {:type :new-order
                 :user-id (:user-id @state)
                 :order-id (next-order-id!)
                 :side side
                 :symbol symbol
                 :price price
                 :qty qty}]
      (transport/send-msg! tp order)
      (println (format "Sent %s %s %d @ %.2f (order #%d)"
                      (name side) symbol qty price (:order-id order)))
      (:order-id order))))

(defn buy
  "Send a buy order."
  [symbol price qty]
  (send-order! :buy symbol price qty))

(defn sell
  "Send a sell order."
  [symbol price qty]
  (send-order! :sell symbol price qty))

(defn cancel
  "Cancel an order."
  [symbol order-id]
  (when-let [tp (:transport @state)]
    (let [msg {:type :cancel
               :user-id (:user-id @state)
               :order-id order-id
               :symbol symbol}]
      (transport/send-msg! tp msg)
      (println (format "Sent cancel for order #%d" order-id)))))

(defn flush!
  "Flush all order books."
  []
  (when-let [tp (:transport @state)]
    (transport/send-msg! tp {:type :flush})
    (println "Sent flush")))

;; =============================================================================
;; History
;; =============================================================================

(defn show
  "Show recent messages."
  ([] (show 10))
  ([n]
   (let [msgs (take-last n (:history @state))]
     (if (empty? msgs)
       (println "No messages yet")
       (doseq [msg msgs]
         (println (str "  " (proto/format-message msg))))))))

(defn clear-history!
  "Clear message history."
  []
  (swap! state assoc :history [])
  (println "History cleared"))

;; =============================================================================
;; Scenarios
;; =============================================================================

(def ^:private scenarios
  [{:id 1  :name "Single buy order"
    :fn #(buy "IBM" 100.00 100)}
   {:id 2  :name "Matching trade"
    :fn #(do (buy "AAPL" 150.00 50)
             (Thread/sleep 100)
             (sell "AAPL" 150.00 50))}
   {:id 3  :name "Partial fill"
    :fn #(do (buy "GOOG" 100.00 100)
             (Thread/sleep 100)
             (sell "GOOG" 100.00 30))}
   {:id 4  :name "Price improvement"
    :fn #(do (sell "MSFT" 200.00 50)
             (Thread/sleep 100)
             (buy "MSFT" 210.00 50))}
   {:id 5  :name "Cancel order"
    :fn #(let [oid (buy "TSLA" 300.00 25)]
           (Thread/sleep 100)
           (cancel "TSLA" oid))}
   {:id 10 :name "Multiple symbols"
    :fn #(do (buy "IBM" 100.00 10)
             (buy "AAPL" 150.00 20)
             (buy "GOOG" 200.00 30)
             (Thread/sleep 100)
             (sell "IBM" 100.00 10)
             (sell "AAPL" 150.00 20)
             (sell "GOOG" 200.00 30))}
   {:id 20 :name "Stress test (100 orders)"
    :fn #(dotimes [i 50]
           (buy "STRESS" (+ 100.0 (rand-int 10)) 10)
           (sell "STRESS" (+ 100.0 (rand-int 10)) 10))}])

(defn scenario
  "Run a test scenario by ID, or list all scenarios."
  ([]
   (println "\nAvailable scenarios:")
   (doseq [{:keys [id name]} scenarios]
     (println (format "  %2d: %s" id name)))
   (println "\nUsage: (scenario <id>)"))
  ([id]
   (if-let [s (first (filter #(= (:id %) id) scenarios))]
     (do
       (println (format "Running scenario %d: %s" id (:name s)))
       ((:fn s)))
     (println "Unknown scenario ID"))))

;; =============================================================================
;; Help
;; =============================================================================

(def ^:private help-text
  "
╔════════════════════════════════════════════════════════════╗
║           MATCHING ENGINE CLIENT - COMMANDS                ║
╠════════════════════════════════════════════════════════════╣
║  (start!)                 Connect to localhost:1234        ║
║  (start! 9000)            Connect to different port        ║
║  (start! \"host\" 1234)     Connect to remote host           ║
║  (start! {:transport :udp}) Use UDP transport              ║
╠════════════════════════════════════════════════════════════╣
║  (buy \"IBM\" 100.50 100)   Buy 100 shares @ $100.50         ║
║  (sell \"IBM\" 100.50 100)  Sell 100 shares @ $100.50        ║
║  (cancel \"IBM\" 1)         Cancel order #1                  ║
║  (flush!)                 Clear all order books            ║
╠════════════════════════════════════════════════════════════╣
║  (scenario)               List all test scenarios          ║
║  (scenario 2)             Run matching trade test          ║
║  (scenario 20)            Run stress test                  ║
╠════════════════════════════════════════════════════════════╣
║  (show)                   Show recent messages             ║
║  (show 20)                Show last 20 messages            ║
║  (status)                 Show connection status           ║
║  (stop!)                  Disconnect                       ║
║  (help)                   Show this help                   ║
╚════════════════════════════════════════════════════════════╝
")

(defn help
  "Show help."
  []
  (println help-text))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(def ^:private welcome
  "
╔════════════════════════════════════════════════════════════╗
║         MATCHING ENGINE CLIENT                             ║
║                                                            ║
║  Type (help) for commands, (start!) to connect             ║
╚════════════════════════════════════════════════════════════╝
")

(defn -main
  "Main entry point - starts REPL with client loaded."
  [& args]
  (println welcome)
  (when (seq args)
    (let [host (first args)
          port (if (second args) (Integer/parseInt (second args)) 1234)]
      (start! host port)))
  ;; Keep running for jar execution
  (when-not (System/getProperty "clojure.main.report")
    (while true
      (Thread/sleep 10000))))
