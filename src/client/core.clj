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
  (println (str "  [RECV]  " (proto/format-message msg)))
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
<<<<<<< HEAD
  "Send a buy order."
  [symbol price qty]
  (send-order! :buy symbol price qty))

(defn sell
  "Send a sell order."
  [symbol price qty]
  (send-order! :sell symbol price qty))
=======
  "Place a buy order.
   
   Args:
     symbol: Stock symbol (e.g., \"IBM\")
     price: Price in dollars (e.g., 100.50)
     qty: Quantity
     order-id: Optional order ID (auto-generated if not provided)
   
   Examples:
     (buy \"IBM\" 100.50 100)
     (buy \"AAPL\" 150 50 1001)"
  ([symbol price qty]
   (buy symbol price qty (next-order-id!)))
  ([symbol price qty order-id]
   (let [conn (:conn @state)
         user-id (:user-id @state)]
     (when-not conn
       (throw (ex-info "Not connected. Call (start!) first." {})))
     (println (format "→ BUY %s %.2f qty=%d (order %d)"
                      symbol (double price) (int qty) order-id))
     (client/send-order! conn user-id symbol (double price) (int qty) :buy order-id)
     (Thread/sleep 50)
     (doseq [msg (client/recv-all conn 100)]
       (print-msg msg))
     order-id)))

(defn sell
  "Place a sell order.
   
   Args:
     symbol: Stock symbol
     price: Price in dollars
     qty: Quantity
     order-id: Optional order ID
   
   Examples:
     (sell \"IBM\" 100.50 100)
     (sell \"AAPL\" 150 50 1001)"
  ([symbol price qty]
   (sell symbol price qty (next-order-id!)))
  ([symbol price qty order-id]
   (let [conn (:conn @state)
         user-id (:user-id @state)]
     (when-not conn
       (throw (ex-info "Not connected. Call (start!) first." {})))
     (println (format "→ SELL %s %s qty=%d (order %d)"
                      symbol (double price) qty order-id))
     (client/send-order! conn user-id symbol (double price) (int qty) :sell order-id)
     (Thread/sleep 50)
     (doseq [msg (client/recv-all conn)]
       (print-msg msg))
     order-id)))
>>>>>>> 34ae84e5b78d196ff5b01f6b05178247acc7747f

(defn cancel
  "Cancel an order."
  [symbol order-id]
<<<<<<< HEAD
  (when-let [tp (:transport @state)]
    (let [msg {:type :cancel
               :user-id (:user-id @state)
               :order-id order-id
               :symbol symbol}]
      (transport/send-msg! tp msg)
      (println (format "Sent cancel for order #%d" order-id)))))
=======
  (let [conn (:conn @state)
        user-id (:user-id @state)]
    (when-not conn
      (throw (ex-info "Not connected. Call (start!) first." {})))
    (println (format "→ CANCEL %s order %d" symbol order-id))
    (client/send-cancel! conn user-id symbol order-id)
    (Thread/sleep 50)
    (doseq [msg (client/recv-all conn)]
      (print-msg msg))
    :cancelled))
>>>>>>> 34ae84e5b78d196ff5b01f6b05178247acc7747f

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

<<<<<<< HEAD
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
=======
;; =============================================================================
;; Market Data Listener
;; =============================================================================

(defonce ^:private mcast-state (atom {:conn nil :running false}))

(defn start-market-data!
  "Start listening to multicast market data feed.
   
   Examples:
     (start-market-data!)                    ; Default 239.255.1.1:1236
     (start-market-data! \"239.255.1.1\" 1236)"
  ([] (start-market-data! "239.255.1.1" 1236))
  ([group port]
   (if (:running @mcast-state)
     (do
       (println "Already listening. Call (stop-market-data!) first.")
       :already-running)
     
     (let [conn (client/multicast-connect group port)]
       (swap! mcast-state assoc :conn conn :running true)
       (println (format "Joined multicast group %s:%d" group port))
       
       ;; Start listener thread
       (future
         (while (:running @mcast-state)
           (when-let [msg (client/multicast-recv conn)]
             (when (not= (:type msg) :error)
               (println (str "  [MCAST] " (proto/format-message msg))))))
         (println "Market data listener stopped"))
       
       :listening))))

(defn stop-market-data!
  "Stop listening to multicast market data."
  []
  (swap! mcast-state assoc :running false)
  (when-let [conn (:conn @mcast-state)]
    (client/multicast-disconnect conn)
    (swap! mcast-state assoc :conn nil))
  (println "Stopped market data listener")
  :stopped)
>>>>>>> 34ae84e5b78d196ff5b01f6b05178247acc7747f
