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

(defn- reset-order-id! []
  (swap! state assoc :next-order-id 1))

(defn- add-to-history! [msg]
  (swap! state update :history
         (fn [h]
           (let [h' (conj h msg)]
             (if (> (count h') 100)
               (subvec h' (- (count h') 100))
               h')))))

(defn- print-msg [msg]
  (println (str "  [RECV] " (proto/format-message msg)))
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
                         (catch java.net.SocketTimeoutException _)
                         (catch Exception e
                           (when (transport/connected? tp)
                             (println "Reader error:" (.getMessage e))))))))]
       (.setDaemon thread true)
       (.setName thread "client-reader")
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
      (:order-id order))))

(defn buy
  "Send a buy order."
  [symbol price qty]
  (let [oid (send-order! :buy symbol price qty)]
    (println (format "Sent BUY %s %d @ %.2f (order #%d)" symbol qty (double price) oid))
    oid))

(defn sell
  "Send a sell order."
  [symbol price qty]
  (let [oid (send-order! :sell symbol price qty)]
    (println (format "Sent SELL %s %d @ %.2f (order #%d)" symbol qty (double price) oid))
    oid))

(defn cancel
  "Cancel an order."
  [symbol order-id]
  (when-let [tp (:transport @state)]
    (let [msg {:type :cancel
               :user-id (:user-id @state)
               :order-id order-id
               :symbol symbol}]
      (transport/send-msg! tp msg)
      (println (format "Sent CANCEL %s order #%d" symbol order-id)))))

(defn flush!
  "Flush all order books."
  []
  (when-let [tp (:transport @state)]
    (transport/send-msg! tp {:type :flush})
    (println "Sent FLUSH")))

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
;; Formatting Helpers
;; =============================================================================

(defn- format-rate [rate]
  (cond
    (>= rate 1000000) (format "%.2fM/sec" (/ rate 1000000.0))
    (>= rate 1000)    (format "%.1fK/sec" (/ rate 1000.0))
    :else             (format "%.0f/sec" (double rate))))

(defn- format-time [ms]
  (cond
    (>= ms 60000) (format "%dm %ds" (quot ms 60000) (rem (quot ms 1000) 60))
    (>= ms 1000)  (format "%.2f sec" (/ ms 1000.0))
    :else         (format "%d ms" ms)))

(defn- format-count [n]
  (cond
    (>= n 1000000) (format "%.1fM" (/ n 1000000.0))
    (>= n 1000)    (format "%.1fK" (/ n 1000.0))
    :else          (str n)))

;; =============================================================================
;; Basic Scenarios
;; =============================================================================

(defn- scenario-simple-orders []
  (println "\n=== Scenario 1: Simple Orders ===\n")
  (reset-order-id!)
  
  (println "Sending: BUY IBM 50@100")
  (buy "IBM" 100 50)
  (Thread/sleep 200)
  
  (println "\nSending: SELL IBM 50@105")
  (sell "IBM" 105 50)
  (Thread/sleep 200)
  
  (println "\nSending: FLUSH")
  (flush!)
  (Thread/sleep 300)
  
  (println "\n*** Scenario 1 Complete ***"))

(defn- scenario-matching-trade []
  (println "\n=== Scenario 2: Matching Trade ===\n")
  (reset-order-id!)
  
  (println "Sending: BUY IBM 50@100")
  (buy "IBM" 100 50)
  (Thread/sleep 200)
  
  (println "\nSending: SELL IBM 50@100 (should match!)")
  (sell "IBM" 100 50)
  (Thread/sleep 300)
  
  (println "\n*** Scenario 2 Complete ***"))

(defn- scenario-cancel-order []
  (println "\n=== Scenario 3: Cancel Order ===\n")
  (reset-order-id!)
  
  (println "Sending: BUY IBM 50@100")
  (let [oid (buy "IBM" 100 50)]
    (Thread/sleep 200)
    
    (println (format "\nSending: CANCEL order %d" oid))
    (cancel "IBM" oid)
    (Thread/sleep 200))
  
  (println "\n*** Scenario 3 Complete ***"))

;; =============================================================================
;; Stress Test (Non-Matching)
;; =============================================================================

(defn- scenario-stress-test [count]
  (println (format "\n=== Stress Test: %s Orders (non-matching) ===\n" (format-count count)))
  (reset-order-id!)
  
  (flush!)
  (Thread/sleep 200)
  
  (let [progress-interval (max 1 (quot count 20))
        start-time (System/currentTimeMillis)
        sent (atom 0)]
    
    (dotimes [i count]
      (let [price (+ 100 (mod i 100))]
        (send-order! :buy "IBM" price 10)
        (swap! sent inc))
      
      (when (and (pos? i) (zero? (mod i progress-interval)))
        (let [elapsed (- (System/currentTimeMillis) start-time)
              pct (quot (* i 100) count)
              rate (if (pos? elapsed) (quot (* @sent 1000) elapsed) 0)]
          (println (format "  %d%% (%d orders, %s, %s)"
                          pct @sent (format-time elapsed) (format-rate rate)))))
      
      (when (zero? (mod i 100))
        (Thread/sleep 2)))
    
    (let [elapsed (- (System/currentTimeMillis) start-time)]
      (println (format "\nSent %d orders in %s" @sent (format-time elapsed)))
      (println "Waiting for responses...")
      (Thread/sleep 2000)
      
      (println "\nSending FLUSH...")
      (flush!)
      (Thread/sleep 2000)
      
      (println "\n*** Stress Test Complete ***"))))

;; =============================================================================
;; Matching Stress Test (Single Symbol)
;; =============================================================================

(defn- scenario-matching-stress [pairs]
  (println (format "\n=== Matching Stress: %s Trade Pairs ===\n" (format-count pairs)))
  (println (format "Target: %s trades (%s orders)\n" (format-count pairs) (format-count (* pairs 2))))
  (reset-order-id!)
  
  (flush!)
  (Thread/sleep 200)
  
  (let [progress-interval (max 1 (quot pairs 20))
        batch-size (cond (>= pairs 100000) 100
                        (>= pairs 10000) 50
                        :else 25)
        start-time (System/currentTimeMillis)
        pairs-sent (atom 0)]
    
    (dotimes [i pairs]
      (let [price (+ 100 (mod i 50))]
        (send-order! :buy "IBM" price 10)
        (send-order! :sell "IBM" price 10)
        (swap! pairs-sent inc))
      
      (when (and (pos? i) (zero? (mod i progress-interval)))
        (let [elapsed (- (System/currentTimeMillis) start-time)
              pct (quot (* i 100) pairs)
              rate (if (pos? elapsed) (quot (* @pairs-sent 1000) elapsed) 0)]
          (println (format "  %d%% | %d pairs | %s | %s trades/sec"
                          pct @pairs-sent (format-time elapsed) (format-rate rate)))))
      
      (when (zero? (mod i batch-size))
        (Thread/sleep 5)))
    
    (let [elapsed (- (System/currentTimeMillis) start-time)
          orders-sent (* @pairs-sent 2)]
      (println (format "\n=== Send Complete ==="))
      (println (format "Trade pairs:  %d" @pairs-sent))
      (println (format "Orders sent:  %d" orders-sent))
      (println (format "Send time:    %s" (format-time elapsed)))
      (when (pos? elapsed)
        (println (format "Send rate:    %s orders/sec" (format-rate (quot (* orders-sent 1000) elapsed)))))
      
      (let [wait-ms (cond (>= pairs 100000) 5000
                         (>= pairs 10000) 2000
                         :else 1000)]
        (println (format "\nWaiting %d ms for server to finish..." wait-ms))
        (Thread/sleep wait-ms))
      
      (println "\n*** Matching Stress Complete ***"))))

;; =============================================================================
;; Dual-Processor Stress Test (IBM + NVDA)
;; =============================================================================

(defn- scenario-dual-processor-stress [pairs]
  (let [symbols ["IBM" "NVDA"]]
    (println "\n============================================================")
    (println "  DUAL-PROCESSOR MATCHING STRESS TEST")
    (println "============================================================")
    (println (format "Trade Pairs:     %s" (format-count pairs)))
    (println (format "Total Orders:    %s" (format-count (* pairs 2))))
    (println (format "Processor 0:     IBM  (%s trades)" (format-count (quot pairs 2))))
    (println (format "Processor 1:     NVDA (%s trades)" (format-count (quot pairs 2))))
    (println "============================================================\n")
    (reset-order-id!)
    
    (flush!)
    (Thread/sleep 200)
    
    (let [progress-interval (max 1 (quot pairs 20))
          batch-size (cond (>= pairs 100000) 100
                          (>= pairs 10000) 50
                          :else 25)
          start-time (System/currentTimeMillis)
          pairs-sent (atom 0)]
      
      (dotimes [i pairs]
        (let [symbol (nth symbols (mod i 2))
              price (+ 100 (mod i 50))]
          (send-order! :buy symbol price 10)
          (send-order! :sell symbol price 10)
          (swap! pairs-sent inc))
        
        (when (and (pos? i) (zero? (mod i progress-interval)))
          (let [elapsed (- (System/currentTimeMillis) start-time)
                pct (quot (* i 100) pairs)
                rate (if (pos? elapsed) (quot (* @pairs-sent 1000) elapsed) 0)]
            (println (format "  %d%% | %d pairs | %s | %s trades/sec"
                            pct @pairs-sent (format-time elapsed) (format-rate rate)))))
        
        (when (zero? (mod i batch-size))
          (Thread/sleep 5)))
      
      (let [elapsed (- (System/currentTimeMillis) start-time)
            orders-sent (* @pairs-sent 2)]
        (println "\n============================================================")
        (println "  SEND COMPLETE")
        (println "============================================================")
        (println (format "Trade pairs:  %d" @pairs-sent))
        (println (format "Orders sent:  %d" orders-sent))
        (println (format "Send time:    %s" (format-time elapsed)))
        (when (pos? elapsed)
          (println (format "Send rate:    %s orders/sec" (format-rate (quot (* orders-sent 1000) elapsed)))))
        (println "============================================================")
        
        (let [wait-ms (cond (>= pairs 100000) 5000
                           (>= pairs 10000) 2000
                           :else 1000)]
          (println (format "\nWaiting %d ms for server to finish..." wait-ms))
          (Thread/sleep wait-ms))
        
        (println "\n*** Dual-Processor Stress Complete ***")))))

;; =============================================================================
;; Scenario Registry
;; =============================================================================

(def ^:private scenarios
  {1  {:name "Simple Orders"           :fn scenario-simple-orders}
   2  {:name "Matching Trade"          :fn scenario-matching-trade}
   3  {:name "Cancel Order"            :fn scenario-cancel-order}
   10 {:name "Stress 1K (no match)"    :fn #(scenario-stress-test 1000)}
   11 {:name "Stress 10K (no match)"   :fn #(scenario-stress-test 10000)}
   12 {:name "Stress 100K (no match)"  :fn #(scenario-stress-test 100000)}
   20 {:name "Match 1K trades"         :fn #(scenario-matching-stress 1000)}
   21 {:name "Match 10K trades"        :fn #(scenario-matching-stress 10000)}
   22 {:name "Match 100K trades"       :fn #(scenario-matching-stress 100000)}
   23 {:name "Match 250K trades"       :fn #(scenario-matching-stress 250000)}
   24 {:name "Match 500K trades"       :fn #(scenario-matching-stress 500000)}
   30 {:name "Dual 500K (250K each)"   :fn #(scenario-dual-processor-stress 500000)}
   31 {:name "Dual 1M (500K each)"     :fn #(scenario-dual-processor-stress 1000000)}})

(defn scenario
  "Run a test scenario by ID, or list all scenarios."
  ([]
   (println "\nAvailable scenarios:")
   (println "\nBasic (correctness testing):")
   (println "  1  - Simple Orders (no match)")
   (println "  2  - Matching Trade")
   (println "  3  - Cancel Order")
   (println "\nStress (non-matching):")
   (println "  10 - 1K orders")
   (println "  11 - 10K orders")
   (println "  12 - 100K orders")
   (println "\nMatching (single symbol - IBM):")
   (println "  20 - 1K trades")
   (println "  21 - 10K trades")
   (println "  22 - 100K trades")
   (println "  23 - 250K trades")
   (println "  24 - 500K trades")
   (println "\nDual-Processor (IBM + NVDA):")
   (println "  30 - 500K trades (250K each)")
   (println "  31 - 1M trades (500K each)")
   (println "\nUsage: (scenario <id>)"))
  ([id]
   (if-let [{:keys [name fn]} (get scenarios id)]
     (do
       (println (format "Running scenario %d: %s" id name))
       (fn))
     (do
       (println (format "Unknown scenario: %d" id))
       (scenario)))))

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
║  (scenario 1)             Simple orders (no match)         ║
║  (scenario 2)             Matching trade                   ║
║  (scenario 20)            1K matching trades               ║
║  (scenario 22)            100K matching trades             ║
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

(defn -main
  "Main entry point."
  [& args]
  (println "\n╔════════════════════════════════════════════════════════════╗")
  (println "║         MATCHING ENGINE CLIENT                             ║")
  (println "║                                                            ║")
  (println "║  Type (help) for commands, (start!) to connect             ║")
  (println "╚════════════════════════════════════════════════════════════╝\n")
  (when (seq args)
    (let [host (first args)
          port (if (second args) (Integer/parseInt (second args)) 1234)]
      (start! host port)))
  (when-not (System/getProperty "clojure.main.report")
    (while true
      (Thread/sleep 10000))))
