(ns client.scenarios
  "Test scenarios for the matching engine client.
   Mirrors the Zig scenarios.zig functionality.
   
   Usage:
     (run! conn 1)      ; Simple orders
     (run! conn 2)      ; Matching trade
     (run! conn 20)     ; 1K matching stress
     (list-scenarios)   ; Show available scenarios"
  (:refer-clojure :exclude [run!])
  (:require [client.client :as client]
            [client.protocol :as proto]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:dynamic *quiet* false)
(def ^:dynamic *progress-fn* println)

(defmacro with-quiet [& body]
  `(binding [*quiet* true] ~@body))

;; =============================================================================
;; Response Statistics
;; =============================================================================

(defn make-stats []
  {:acks (atom 0)
   :cancel-acks (atom 0)
   :trades (atom 0)
   :top-of-book (atom 0)
   :rejects (atom 0)
   :parse-errors (atom 0)})

(defn stats-total [stats]
  (+ @(:acks stats)
     @(:cancel-acks stats)
     @(:trades stats)
     @(:top-of-book stats)
     @(:rejects stats)))

(defn count-message! [stats msg]
  (case (:type msg)
    :ack (swap! (:acks stats) inc)
    :cancel-ack (swap! (:cancel-acks stats) inc)
    :trade (swap! (:trades stats) inc)
    :top-of-book (swap! (:top-of-book stats) inc)
    :reject (swap! (:rejects stats) inc)
    :parse-error (swap! (:parse-errors stats) inc)
    nil))

(defn merge-stats! [target source]
  (swap! (:acks target) + @(:acks source))
  (swap! (:cancel-acks target) + @(:cancel-acks source))
  (swap! (:trades target) + @(:trades source))
  (swap! (:top-of-book target) + @(:top-of-book source))
  (swap! (:rejects target) + @(:rejects source))
  (swap! (:parse-errors target) + @(:parse-errors source)))

(defn print-stats [stats]
  (println "\n=== Server Response Summary ===")
  (println (format "ACKs:            %d" @(:acks stats)))
  (when (pos? @(:cancel-acks stats))
    (println (format "Cancel ACKs:     %d" @(:cancel-acks stats))))
  (when (pos? @(:trades stats))
    (println (format "Trades:          %d" @(:trades stats))))
  (println (format "Top of Book:     %d" @(:top-of-book stats)))
  (when (pos? @(:rejects stats))
    (println (format "Rejects:         %d" @(:rejects stats))))
  (when (pos? @(:parse-errors stats))
    (println (format "Parse errors:    %d" @(:parse-errors stats))))
  (println (format "Total messages:  %d" (stats-total stats))))

(defn print-validation [stats expected-acks expected-trades]
  (print-stats stats)
  (println "\n=== Validation ===")
  
  (let [actual-acks @(:acks stats)
        actual-trades @(:trades stats)]
    (if (>= actual-acks expected-acks)
      (println (format "ACKs:            %d/%d ✓ PASS" actual-acks expected-acks))
      (let [pct (if (pos? expected-acks) (quot (* actual-acks 100) expected-acks) 0)]
        (println (format "ACKs:            %d/%d (%d%%) ✗ MISSING %d"
                         actual-acks expected-acks pct (- expected-acks actual-acks)))))
    
    (when (pos? expected-trades)
      (if (>= actual-trades expected-trades)
        (println (format "Trades:          %d/%d ✓ PASS" actual-trades expected-trades))
        (let [pct (if (pos? expected-trades) (quot (* actual-trades 100) expected-trades) 0)]
          (println (format "Trades:          %d/%d (%d%%) ✗ MISSING %d"
                           actual-trades expected-trades pct (- expected-trades actual-trades))))))
    
    (let [passed (and (>= actual-acks expected-acks)
                      (or (zero? expected-trades) (>= actual-trades expected-trades)))]
      (cond
        (pos? @(:rejects stats))
        (println (format "\n*** TEST FAILED - %d REJECTS ***" @(:rejects stats)))
        
        passed
        (println "\n*** TEST PASSED ***")
        
        :else
        (println "\n*** TEST FAILED - MISSING RESPONSES ***"))
      
      passed)))

;; =============================================================================
;; Response Handling
;; =============================================================================

(defn drain-responses
  "Drain all pending responses with timeout."
  [conn timeout-ms]
  (let [stats (make-stats)
        start (System/currentTimeMillis)
        poll-timeout 100
        max-empty 100]
    (loop [consecutive-empty 0]
      (let [elapsed (- (System/currentTimeMillis) start)]
        (if (or (>= elapsed timeout-ms)
                (>= consecutive-empty max-empty))
          stats
          (if-let [msg (client/recv-message conn poll-timeout)]
            (do
              (count-message! stats msg)
              (recur 0))
            (recur (inc consecutive-empty))))))))

(defn recv-and-count!
  "Try to receive a message and count it."
  [conn stats timeout-ms]
  (when-let [msg (client/recv-message conn timeout-ms)]
    (count-message! stats msg)
    msg))

(defn recv-and-print-responses [conn]
  (Thread/sleep 50)
  (doseq [msg (client/recv-all conn 100)]
    (println (str "  [RECV] " (proto/format-message msg)))))

;; =============================================================================
;; Formatting Helpers
;; =============================================================================

(defn format-time [ms]
  (cond
    (>= ms 60000) (format "%dm %ds" (quot ms 60000) (rem (quot ms 1000) 60))
    (>= ms 1000)  (format "%.3f sec" (/ ms 1000.0))
    :else         (format "%d ms" ms)))

(defn format-rate [rate]
  (cond
    (>= rate 1000000) (format "%.2fM/sec" (/ rate 1000000.0))
    (>= rate 1000)    (format "%.1fK/sec" (/ rate 1000.0))
    :else             (format "%d/sec" rate)))

(defn format-count [n]
  (cond
    (>= n 1000000) (format "%dM" (quot n 1000000))
    (>= n 1000)    (format "%dK" (quot n 1000))
    :else          (str n)))

;; =============================================================================
;; Basic Scenarios
;; =============================================================================

(defn scenario-1
  "Simple Orders - two non-matching orders"
  [conn]
  (println "=== Scenario 1: Simple Orders ===\n")
  (client/send-order! conn 1 "IBM" 100 50 :buy 1)
  (Thread/sleep 100)
  (recv-and-print-responses conn)
  (client/send-order! conn 1 "IBM" 105 50 :sell 2)
  (Thread/sleep 100)
  (recv-and-print-responses conn)
  (println "\n[FLUSH] Cleaning up server state")
  (client/send-flush! conn)
  (Thread/sleep 100)
  (recv-and-print-responses conn))

(defn scenario-2
  "Matching Trade - buy and sell at same price"
  [conn]
  (println "=== Scenario 2: Matching Trade ===\n")
  (client/send-order! conn 1 "IBM" 100 50 :buy 1)
  (Thread/sleep 75)
  (recv-and-print-responses conn)
  (client/send-order! conn 1 "IBM" 100 50 :sell 2)
  (Thread/sleep 75)
  (recv-and-print-responses conn)
  (println "\n[FLUSH] Cleaning up server state")
  (client/send-flush! conn)
  (Thread/sleep 100)
  (recv-and-print-responses conn))

(defn scenario-3
  "Cancel Order - place then cancel"
  [conn]
  (println "=== Scenario 3: Cancel Order ===\n")
  (client/send-order! conn 1 "IBM" 100 50 :buy 1)
  (Thread/sleep 100)
  (recv-and-print-responses conn)
  (client/send-cancel! conn 1 "IBM" 1)
  (Thread/sleep 100)
  (recv-and-print-responses conn)
  (println "\n[FLUSH] Cleaning up server state")
  (client/send-flush! conn)
  (Thread/sleep 100)
  (recv-and-print-responses conn))

;; =============================================================================
;; Unmatched Stress Test
;; =============================================================================

(defn stress-test
  "Send many non-matching orders."
  [conn count]
  (println (format "=== Unmatched Stress: %s Orders ===\n" (format-count count)))
  
  (client/send-flush! conn)
  (Thread/sleep 200)
  (drain-responses conn 500)
  
  (let [batch-size (cond (>= count 100000) 500
                         (>= count 10000) 200
                         :else 100)
        delay-ms (cond (>= count 100000) 10
                       (>= count 10000) 5
                       :else 2)
        progress-interval (max 1 (quot count 10))
        start (System/currentTimeMillis)
        send-errors (atom 0)]
    
    (when-not *quiet*
      (println (format "Throttle: %d/batch, %dms delay" batch-size delay-ms)))
    
    (dotimes [i count]
      (let [price (+ 100 (mod i 100))]
        (try
          (client/send-order! conn 1 "IBM" price 10 :buy (inc i))
          (catch Exception _ (swap! send-errors inc))))
      
      (when (and (not *quiet*)
                 (pos? i)
                 (zero? (mod i progress-interval)))
        (println (format "  %d%%" (quot (* i 100) count))))
      
      (when (and (pos? i) (zero? (mod i batch-size)))
        (Thread/sleep delay-ms)))
    
    (let [end (System/currentTimeMillis)
          total-time (- end start)
          sent (- count @send-errors)]
      
      (println "\n=== Send Results ===")
      (println (format "Orders sent:     %d" sent))
      (println (format "Send errors:     %d" @send-errors))
      (println (format "Total time:      %s" (format-time total-time)))
      
      (println "\nDraining responses...")
      (let [stats (drain-responses conn 15000)]
        (print-validation stats sent 0))))
  
  (println "\n[FLUSH] Cleaning up server state")
  (client/send-flush! conn)
  (Thread/sleep 200))

;; =============================================================================
;; Matching Stress Test (Single Symbol)
;; =============================================================================

(defn matching-stress
  "Send matching buy/sell pairs to generate trades."
  [conn trades]
  (let [orders (* trades 2)]
    (if (>= trades 100000000)
      (do
        (println)
        (println "╔══════════════════════════════════════════════════════════╗")
        (println (format "║  ★★★ LEGENDARY MATCHING STRESS TEST ★★★                  ║"))
        (println (format "║  %sM TRADES (%sM ORDERS)                              ║"
                         (quot trades 1000000) (quot orders 1000000)))
        (println "╚══════════════════════════════════════════════════════════╝")
        (println))
      (println (format "=== Matching Stress Test: %s Trades ===\n" (format-count trades))))
    
    (println (format "Target: %s trades (%s orders)" (format-count trades) (format-count orders)))
    
    (client/send-flush! conn)
    (Thread/sleep 200)
    (drain-responses conn 500)
    
    (let [pairs-per-batch (cond (>= trades 100000000) 100
                                (>= trades 1000000) 100
                                (>= trades 100000) 100
                                (>= trades 10000) 50
                                :else 50)
          delay-ms (cond (>= trades 100000000) 50
                         (>= trades 1000000) 50
                         (>= trades 100000) 30
                         (>= trades 10000) 20
                         :else 10)
          progress-pct (if (>= trades 1000000) 5 10)
          progress-interval (max 1 (quot trades (quot 100 progress-pct)))
          
          start (System/currentTimeMillis)
          send-errors (atom 0)
          pairs-sent (atom 0)
          running-stats (make-stats)]
      
      (println (format "Throttling: %d pairs/batch, %dms delay (interleaved recv)"
                       pairs-per-batch delay-ms))
      
      (dotimes [i trades]
        (let [price (+ 100 (mod i 50))
              buy-oid (inc (* i 2))
              sell-oid (+ 2 (* i 2))]
          (try
            (client/send-order! conn 1 "IBM" price 10 :buy buy-oid)
            (client/send-order! conn 1 "IBM" price 10 :sell sell-oid)
            (swap! pairs-sent inc)
            (catch Exception _ (swap! send-errors inc))))
        
        (when (and (not *quiet*)
                   (pos? i)
                   (> (quot i progress-interval) (quot (dec i) progress-interval)))
          (let [elapsed (- (System/currentTimeMillis) start)
                rate (if (pos? elapsed) (quot (* @pairs-sent 1000) elapsed) 0)]
            (println (format "  %d%% | %d pairs | %d ms | %d trades/sec | recv'd: %d"
                             (quot (* i 100) trades)
                             @pairs-sent
                             elapsed
                             rate
                             (stats-total running-stats)))))
        
        (when (and (pos? i) (zero? (mod i pairs-per-batch)))
          ;; Drain aggressively
          (let [drain-target (* pairs-per-batch 5)]
            (dotimes [_ drain-target]
              (when-let [msg (client/recv-message conn 1)]
                (count-message! running-stats msg))))
          (Thread/sleep delay-ms)))
      
      (let [end (System/currentTimeMillis)
            total-time (- end start)
            orders-sent (* @pairs-sent 2)]
        
        (println "\n=== Send Results ===")
        (println (format "Trade pairs:     %d" @pairs-sent))
        (println (format "Orders sent:     %d" orders-sent))
        (println (format "Send errors:     %d" @send-errors))
        (println (format "Total time:      %s" (format-time total-time)))
        
        (when (pos? total-time)
          (let [throughput (quot (* orders-sent 1000) total-time)
                trade-rate (quot (* @pairs-sent 1000) total-time)]
            (println "\n=== Throughput ===")
            (println (format "Orders/sec:      %s" (format-rate throughput)))
            (println (format "Trades/sec:      %s" (format-rate trade-rate)))))
        
        (println (format "\nReceived during send: %d messages" (stats-total running-stats)))
        
        (println "Waiting for TCP buffers to flush...")
        (Thread/sleep 3000)
        
        (let [expected-acks orders-sent
              expected-trades @pairs-sent
              expected-total (+ expected-acks expected-trades (* expected-trades 2))
              remaining (- expected-total (stats-total running-stats))
              drain-timeout (cond (>= trades 100000000) 1800000
                                  (>= trades 1000000) 600000
                                  (>= trades 500000) 300000
                                  (>= trades 250000) 180000
                                  (>= trades 100000) 120000
                                  :else 60000)]
          
          (println (format "Final drain (expecting ~%d more)..." remaining))
          (let [final-stats (drain-responses conn drain-timeout)]
            (merge-stats! running-stats final-stats)
            
            (let [passed (print-validation running-stats expected-acks expected-trades)]
              (when (and (>= trades 100000000) 
                         (>= @(:trades running-stats) expected-trades))
                (println)
                (println "╔══════════════════════════════════════════════════════════╗")
                (println "║  ★★★ LEGENDARY ACHIEVEMENT UNLOCKED ★★★                  ║")
                (println "╚══════════════════════════════════════════════════════════╝"))
              
              passed))))))
  
  (println "\n[FLUSH] Cleaning up server state")
  (client/send-flush! conn)
  (Thread/sleep 2000))

;; =============================================================================
;; Dual-Processor Stress Test (IBM + NVDA)
;; =============================================================================

(defn dual-processor-stress
  "Send matching pairs to two symbols (for dual-processor engines)."
  [conn trades]
  (let [orders (* trades 2)
        trades-per-proc (quot trades 2)]
    
    (if (>= trades 10000000)
      (do
        (println)
        (println "╔══════════════════════════════════════════════════════════╗")
        (println "║  ★★★ DUAL-PROCESSOR STRESS TEST ★★★                      ║")
        (println (format "║  %sM TRADES (%sM ORDERS)                              ║"
                         (quot trades 1000000) (quot orders 1000000)))
        (println (format "║  Processor 0 (A-M): IBM  - %sM trades                   ║"
                         (quot trades-per-proc 1000000)))
        (println (format "║  Processor 1 (N-Z): NVDA - %sM trades                   ║"
                         (quot trades-per-proc 1000000)))
        (println "╚══════════════════════════════════════════════════════════╝")
        (println))
      (println (format "=== Dual-Processor Stress: %s Trades ===\n" (format-count trades))))
    
    (println (format "Target: %s trades (%s orders)" (format-count trades) (format-count orders)))
    (println (format "  Processor 0 (A-M): IBM  - %s trades" (format-count trades-per-proc)))
    (println (format "  Processor 1 (N-Z): NVDA - %s trades" (format-count trades-per-proc)))
    
    (client/send-flush! conn)
    (Thread/sleep 200)
    (drain-responses conn 500)
    
    (let [symbols ["IBM" "NVDA"]
          pairs-per-batch (cond (>= trades 10000000) 100
                                (>= trades 1000000) 100
                                :else 50)
          delay-ms (cond (>= trades 10000000) 50
                         (>= trades 1000000) 50
                         :else 20)
          progress-pct (if (>= trades 1000000) 5 10)
          progress-interval (max 1 (quot trades (quot 100 progress-pct)))
          
          start (System/currentTimeMillis)
          send-errors (atom 0)
          pairs-sent (atom 0)
          running-stats (make-stats)]
      
      (println (format "Throttling: %d pairs/batch, %dms delay (interleaved recv)"
                       pairs-per-batch delay-ms))
      
      (dotimes [i trades]
        (let [symbol (nth symbols (mod i 2))
              price (+ 100 (mod i 50))
              buy-oid (inc (* i 2))
              sell-oid (+ 2 (* i 2))]
          (try
            (client/send-order! conn 1 symbol price 10 :buy buy-oid)
            (client/send-order! conn 1 symbol price 10 :sell sell-oid)
            (swap! pairs-sent inc)
            (catch Exception _ (swap! send-errors inc))))
        
        (when (and (not *quiet*)
                   (pos? i)
                   (> (quot i progress-interval) (quot (dec i) progress-interval)))
          (let [elapsed (- (System/currentTimeMillis) start)
                rate (if (pos? elapsed) (quot (* @pairs-sent 1000) elapsed) 0)]
            (println (format "  %d%% | %d pairs | %d ms | %d trades/sec | recv'd: %d"
                             (quot (* i 100) trades)
                             @pairs-sent
                             elapsed
                             rate
                             (stats-total running-stats)))))
        
        (when (and (pos? i) (zero? (mod i pairs-per-batch)))
          (let [drain-target (* pairs-per-batch 5)]
            (dotimes [_ drain-target]
              (when-let [msg (client/recv-message conn 2)]
                (count-message! running-stats msg))))
          (Thread/sleep delay-ms)))
      
      (let [end (System/currentTimeMillis)
            total-time (- end start)
            orders-sent (* @pairs-sent 2)]
        
        (println "\n=== Send Results ===")
        (println (format "Trade pairs:     %d" @pairs-sent))
        (println (format "Orders sent:     %d" orders-sent))
        (println (format "Send errors:     %d" @send-errors))
        (println (format "Total time:      %s" (format-time total-time)))
        
        (when (pos? total-time)
          (let [throughput (quot (* orders-sent 1000) total-time)
                trade-rate (quot (* @pairs-sent 1000) total-time)]
            (println "\n=== Throughput ===")
            (println (format "Orders/sec:      %s" (format-rate throughput)))
            (println (format "Trades/sec:      %s" (format-rate trade-rate)))))
        
        (println (format "\nReceived during send: %d messages" (stats-total running-stats)))
        
        (println "Waiting for TCP buffers to flush...")
        (Thread/sleep 3000)
        
        (let [expected-acks orders-sent
              expected-trades @pairs-sent
              drain-timeout (cond (>= trades 10000000) 1800000
                                  (>= trades 1000000) 600000
                                  :else 300000)]
          
          (println (format "Final drain..."))
          (let [final-stats (drain-responses conn drain-timeout)]
            (merge-stats! running-stats final-stats)
            
            (let [passed (print-validation running-stats expected-acks expected-trades)]
              (when (and (>= trades 10000000)
                         (>= @(:trades running-stats) expected-trades))
                (println)
                (println "╔══════════════════════════════════════════════════════════╗")
                (println "║  ★★★ ULTIMATE DUAL-PROCESSOR ACHIEVEMENT ★★★             ║")
                (println "╚══════════════════════════════════════════════════════════╝"))
              
              passed))))))
  
  (println "\n[FLUSH] Cleaning up server state")
  (client/send-flush! conn)
  (Thread/sleep 2000))

;; =============================================================================
;; Scenario Registry
;; =============================================================================

(def scenarios
  {1  {:name "Simple Orders" :fn scenario-1}
   2  {:name "Matching Trade" :fn scenario-2}
   3  {:name "Cancel Order" :fn scenario-3}
   10 {:name "Unmatched 1K" :fn #(stress-test % 1000)}
   11 {:name "Unmatched 10K" :fn #(stress-test % 10000)}
   12 {:name "Unmatched 100K" :fn #(stress-test % 100000)}
   20 {:name "1K trades" :fn #(matching-stress % 1000)}
   21 {:name "10K trades" :fn #(matching-stress % 10000)}
   22 {:name "100K trades" :fn #(matching-stress % 100000)}
   23 {:name "250K trades" :fn #(matching-stress % 250000)}
   24 {:name "500K trades" :fn #(matching-stress % 500000)}
   25 {:name "250M trades ★★★ LEGENDARY ★★★" :fn #(matching-stress % 250000000)}
   30 {:name "Dual 500K (250K each)" :fn #(dual-processor-stress % 500000)}
   31 {:name "Dual 1M (500K each)" :fn #(dual-processor-stress % 1000000)}
   32 {:name "Dual 100M (50M each) ★★★ ULTIMATE ★★★" :fn #(dual-processor-stress % 100000000)}})

(defn list-scenarios
  "Print available scenarios."
  []
  (println "Available scenarios:")
  (println "\nBasic: 1 (orders), 2 (trade), 3 (cancel)")
  (println "\nUnmatched: 10 (1K), 11 (10K), 12 (100K)")
  (println "\nMatching (single processor - IBM):")
  (println "  20 - 1K trades")
  (println "  21 - 10K trades")
  (println "  22 - 100K trades")
  (println "  23 - 250K trades")
  (println "  24 - 500K trades")
  (println "  25 - 250M trades ★★★ LEGENDARY ★★★")
  (println "\nDual-Processor (IBM + NVDA):")
  (println "  30 - 500K trades  (250K each)")
  (println "  31 - 1M trades    (500K each)")
  (println "  32 - 100M trades  (50M each) ★★★ ULTIMATE ★★★"))

(defn run!
  "Run a scenario by number."
  [conn scenario-num]
  (if-let [{:keys [fn]} (get scenarios scenario-num)]
    (fn conn)
    (do
      (println (format "Unknown scenario: %d" scenario-num))
      (list-scenarios)
      false)))
