(ns client.core
  "Interactive REPL client for the matching engine."
  (:require [client.client :as client]
            [client.protocol :as proto])
  (:gen-class))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private state
  (atom {:conn nil
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

(defn- drain-and-print!
  "Drain all available responses and print them."
  ([] (drain-and-print! 100))
  ([timeout-ms]
   (when-let [conn (:conn @state)]
     (doseq [msg (client/recv-all conn timeout-ms)]
       (print-msg msg)))))

;; =============================================================================
;; Connection Management
;; =============================================================================

(defn start!
  "Connect to matching engine."
  ([] (start! "localhost" 1234 {}))
  ([arg]
   (cond
     (number? arg) (start! "localhost" arg {})
     (map? arg) (start! "localhost" 1234 arg)
     (string? arg) (start! arg 1234 {})
     :else (start!)))
  ([host port] (start! host port {}))
  ([host port opts]
   (when-let [old (:conn @state)]
     (client/disconnect old))

   (let [conn (client/connect host port opts)]
     (swap! state assoc :conn conn)
     (println (format "Connected to %s:%d via %s" host port (name (:type conn))))
     
     ;; Detect protocol
     (let [proto (client/detect-protocol! conn)]
       (println (format "Protocol: %s" (name proto)))))

   :connected))

(defn stop!
  "Disconnect from matching engine."
  []
  (when-let [conn (:conn @state)]
    (client/disconnect conn)
    (swap! state assoc :conn nil)
    (println "Disconnected"))
  :disconnected)

(defn status
  "Show connection status."
  []
  (if-let [conn (:conn @state)]
    (if (client/connected? conn)
      (println (format "Connected (%s)" (name (or (client/get-protocol conn) :unknown))))
      (println "Disconnected (stale)"))
    (println "Not connected")))

;; =============================================================================
;; Order Commands
;; =============================================================================

(defn buy
  "Send a buy order."
  [symbol price qty]
  (when-let [conn (:conn @state)]
    (let [oid (next-order-id!)
          user-id (:user-id @state)]
      (println (format "Sent BUY %s %d @ %.2f (order #%d)" symbol qty (double price) oid))
      (client/send-order! conn user-id symbol (int price) qty :buy oid)
      oid)))

(defn sell
  "Send a sell order."
  [symbol price qty]
  (when-let [conn (:conn @state)]
    (let [oid (next-order-id!)
          user-id (:user-id @state)]
      (println (format "Sent SELL %s %d @ %.2f (order #%d)" symbol qty (double price) oid))
      (client/send-order! conn user-id symbol (int price) qty :sell oid)
      oid)))

(defn cancel
  "Cancel an order."
  [symbol order-id]
  (when-let [conn (:conn @state)]
    (let [user-id (:user-id @state)]
      (println (format "Sent CANCEL %s order #%d" symbol order-id))
      (client/send-cancel! conn user-id symbol order-id))))

(defn flush!
  "Flush all order books."
  []
  (when-let [conn (:conn @state)]
    (println "Sent FLUSH")
    (client/send-flush! conn)))

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
  (Thread/sleep 100)
  (drain-and-print!)
  
  (println "\nSending: SELL IBM 50@105")
  (sell "IBM" 105 50)
  (Thread/sleep 100)
  (drain-and-print!)
  
  (println "\nSending: FLUSH")
  (flush!)
  (Thread/sleep 150)
  (drain-and-print!)
  
  (println "\n*** Scenario 1 Complete ***"))

(defn- scenario-matching-trade []
  (println "\n=== Scenario 2: Matching Trade ===\n")
  (reset-order-id!)
  
  (println "Sending: BUY IBM 50@100")
  (buy "IBM" 100 50)
  (Thread/sleep 100)
  (drain-and-print!)
  
  (println "\nSending: SELL IBM 50@100 (should match!)")
  (sell "IBM" 100 50)
  (Thread/sleep 150)
  (drain-and-print!)
  
  (println "\n*** Scenario 2 Complete ***"))

(defn- scenario-cancel-order []
  (println "\n=== Scenario 3: Cancel Order ===\n")
  (reset-order-id!)
  
  (println "Sending: BUY IBM 50@100")
  (let [oid (buy "IBM" 100 50)]
    (Thread/sleep 100)
    (drain-and-print!)
    
    (println (format "\nSending: CANCEL order %d" oid))
    (cancel "IBM" oid)
    (Thread/sleep 100)
    (drain-and-print!))
  
  (println "\n*** Scenario 3 Complete ***"))

;; =============================================================================
;; Stress Test (Non-Matching)
;; =============================================================================

(defn- scenario-stress-test [count]
  (println (format "\n=== Stress Test: %s Orders (non-matching) ===\n" (format-count count)))
  (reset-order-id!)
  
  (flush!)
  (Thread/sleep 100)
  (drain-and-print! 200)
  
  (let [conn (:conn @state)
        user-id (:user-id @state)
        progress-interval (max 1 (quot count 20))
        start-time (System/currentTimeMillis)
        sent (atom 0)]
    
    (dotimes [i count]
      (let [price (+ 100 (mod i 100))
            oid (next-order-id!)]
        (client/send-order! conn user-id "IBM" price 10 :buy oid)
        (swap! sent inc))
      
      (when (and (pos? i) (zero? (mod i progress-interval)))
        (let [elapsed (- (System/currentTimeMillis) start-time)
              pct (quot (* i 100) count)
              rate (if (pos? elapsed) (quot (* @sent 1000) elapsed) 0)]
          (println (format "  %d%% (%d orders, %s, %s)"
                          pct @sent (format-time elapsed) (format-rate rate)))))
      
      ;; Interleave receives to prevent TCP buffer issues
      (when (zero? (mod i 100))
        (client/recv-all conn 1)))
    
    (let [elapsed (- (System/currentTimeMillis) start-time)]
      (println (format "\nSent %d orders in %s" @sent (format-time elapsed)))
      (println "Waiting for responses...")
      (Thread/sleep 2000)
      (drain-and-print! 500)
      
      (println "\nSending FLUSH...")
      (flush!)
      (Thread/sleep 2000)
      (drain-and-print! 500)
      
      (println "\n*** Stress Test Complete ***"))))

;; =============================================================================
;; Matching Stress Test (Single Symbol)
;; =============================================================================

(defn- scenario-matching-stress [pairs]
  (println (format "\n=== Matching Stress: %s Trade Pairs ===\n" (format-count pairs)))
  (println (format "Target: %s trades (%s orders)\n" (format-count pairs) (format-count (* pairs 2))))
  (reset-order-id!)
  
  (flush!)
  (Thread/sleep 100)
  (drain-and-print! 200)
  
  (let [conn (:conn @state)
        user-id (:user-id @state)
        progress-interval (max 1 (quot pairs 20))
        batch-size (cond (>= pairs 100000) 100
                        (>= pairs 10000) 50
                        :else 25)
        start-time (System/currentTimeMillis)
        pairs-sent (atom 0)]
    
    (dotimes [i pairs]
      (let [price (+ 100 (mod i 50))
            buy-oid (next-order-id!)
            sell-oid (next-order-id!)]
        (client/send-order! conn user-id "IBM" price 10 :buy buy-oid)
        (client/send-order! conn user-id "IBM" price 10 :sell sell-oid)
        (swap! pairs-sent inc))
      
      (when (and (pos? i) (zero? (mod i progress-interval)))
        (let [elapsed (- (System/currentTimeMillis) start-time)
              pct (quot (* i 100) pairs)
              rate (if (pos? elapsed) (quot (* @pairs-sent 1000) elapsed) 0)]
          (println (format "  %d%% | %d pairs | %s | %s trades/sec"
                          pct @pairs-sent (format-time elapsed) (format-rate rate)))))
      
      ;; Interleave receives
      (when (zero? (mod i batch-size))
        (client/recv-all conn 1)))
    
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
        (Thread/sleep wait-ms)
        (drain-and-print! 500))
      
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
    (Thread/sleep 100)
    (drain-and-print! 200)
    
    (let [conn (:conn @state)
          user-id (:user-id @state)
          progress-interval (max 1 (quot pairs 20))
          batch-size (cond (>= pairs 100000) 100
                          (>= pairs 10000) 50
                          :else 25)
          start-time (System/currentTimeMillis)
          pairs-sent (atom 0)]
      
      (dotimes [i pairs]
        (let [symbol (nth symbols (mod i 2))
              price (+ 100 (mod i 50))
              buy-oid (next-order-id!)
              sell-oid (next-order-id!)]
          (client/send-order! conn user-id symbol price 10 :buy buy-oid)
          (client/send-order! conn user-id symbol price 10 :sell sell-oid)
          (swap! pairs-sent inc))
        
        (when (and (pos? i) (zero? (mod i progress-interval)))
          (let [elapsed (- (System/currentTimeMillis) start-time)
                pct (quot (* i 100) pairs)
                rate (if (pos? elapsed) (quot (* @pairs-sent 1000) elapsed) 0)]
            (println (format "  %d%% | %d pairs | %s | %s trades/sec"
                            pct @pairs-sent (format-time elapsed) (format-rate rate)))))
        
        ;; Interleave receives
        (when (zero? (mod i batch-size))
          (client/recv-all conn 1)))
      
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
          (Thread/sleep wait-ms)
          (drain-and-print! 500))
        
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
