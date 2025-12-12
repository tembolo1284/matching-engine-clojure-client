(ns client.core
  "REPL-friendly trading API for matching engine.
   
   Quick Start:
     (start!)                           ; Connect and auto-detect protocol
     (buy \"IBM\" 100.00 50)            ; Buy 50 IBM @ $100.00
     (sell \"IBM\" 100.00 50)           ; Sell 50 IBM @ $100.00
     (show)                             ; Show recent messages
     (scenario 2)                       ; Run scenario 2 (matching trade)
     (stop!)                            ; Disconnect
   
   Protocol Detection:
     The client auto-detects whether the server uses binary or CSV protocol.
     You can also force a protocol with (start! :binary) or (start! :csv).
   
   Transport:
     (start!)                           ; TCP (default)
     (start! {:transport :udp})         ; UDP"
  (:require [client.client :as client]
            [client.protocol :as proto]
            [client.scenarios :as scenarios]))

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
  "Connect to matching engine with auto protocol detection.
   
   Examples:
     (start!)                       ; localhost:1234, TCP, auto-detect protocol
     (start! 9000)                  ; localhost:9000
     (start! \"host\" 1234)         ; custom host
     (start! :binary)               ; force binary protocol
     (start! :csv)                  ; force CSV protocol
     (start! {:transport :udp})     ; use UDP
     (start! \"host\" 1234 {:transport :udp :protocol :binary})"
  ([] (start! "localhost" 1234 {}))
  ([arg]
   (cond
     (number? arg) (start! "localhost" arg {})
     (keyword? arg) (start! "localhost" 1234 {:protocol arg})
     (map? arg) (start! "localhost" 1234 arg)
     (string? arg) (start! arg 1234 {})
     :else (start!)))
  ([host port] (start! host port {}))
  ([host port opts]
   (when-let [old-conn (:conn @state)]
     (client/disconnect old-conn))
   
   (let [conn (client/connect host port opts)
         transport (or (:transport opts) :tcp)]
     (swap! state assoc :conn conn)
     (println (format "Connected to %s:%d via %s" host port (name transport)))
     
     ;; Protocol detection or explicit setting
     (if-let [proto (:protocol opts)]
       (do
         (client/set-protocol! conn proto)
         (println (format "Protocol: %s (forced)" (name proto))))
       (do
         (print "Detecting protocol... ")
         (flush)
         (let [detected (client/detect-protocol! conn)]
           (println (name detected)))))
     
     :connected)))

(defn stop!
  "Disconnect from server."
  []
  (when-let [conn (:conn @state)]
    (client/disconnect conn)
    (swap! state assoc :conn nil)
    (println "Disconnected")
    :disconnected))

(defn connected?
  "Check if connected to server."
  []
  (when-let [conn (:conn @state)]
    (client/connected? conn)))

(defn protocol
  "Get current protocol (:binary or :csv)."
  []
  (when-let [conn (:conn @state)]
    (client/get-protocol conn)))

(defn user!
  "Set the default user ID for orders."
  [id]
  (swap! state assoc :user-id id)
  (println (format "User ID set to %d" id))
  id)

;; =============================================================================
;; Order Entry
;; =============================================================================

(defn buy
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

(defn cancel
  "Cancel an order.
   
   Args:
     symbol: Stock symbol
     order-id: Order ID to cancel
   
   Example:
     (cancel \"IBM\" 1)"
  [symbol order-id]
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

(defn flush!
  "Flush all order books on server."
  []
  (let [conn (:conn @state)]
    (when-not conn
      (throw (ex-info "Not connected. Call (start!) first." {})))
    (client/send-flush! conn)
    (println "→ FLUSH")
    (swap! state assoc :next-order-id 1)
    :flushed))

;; =============================================================================
;; Reading Responses
;; =============================================================================

(defn recv
  "Receive and print any pending messages from server."
  []
  (let [conn (:conn @state)]
    (when-not conn
      (throw (ex-info "Not connected. Call (start!) first." {})))
    (let [msgs (client/recv-all conn)]
      (if (seq msgs)
        (doseq [msg msgs]
          (print-msg msg))
        (println "  (no messages)"))
      (count msgs))))

(defn show
  "Show recent message history.
   
   Args:
     n: Number of messages to show (default 10)"
  ([] (show 10))
  ([n]
   (let [history (:history @state)
         recent (take-last n history)]
     (if (seq recent)
       (doseq [msg recent]
         (println (str "  " (proto/format-message msg))))
       (println "  (no history)"))
     (count recent))))

;; =============================================================================
;; Batch Operations
;; =============================================================================

(defn match!
  "Create a matching trade: buy and sell at same price.
   
   Args:
     symbol: Stock symbol
     price: Price in dollars
     qty: Quantity
   
   Returns: Vector of [buy-order-id sell-order-id]"
  [symbol price qty]
  (let [buy-id (buy symbol price qty)
        sell-id (sell symbol price qty)]
    [buy-id sell-id]))

(defn ladder!
  "Place orders at multiple price levels.
   
   Args:
     symbol: Stock symbol
     side: :buy or :sell
     base-price: Starting price in dollars
     qty: Quantity per level
     levels: Number of price levels
     step: Price step in dollars (default 0.01)
   
   Example:
     (ladder! \"IBM\" :buy 100.00 10 5 0.10)
     ; Places buy orders at 100.00, 99.90, 99.80, 99.70, 99.60"
  ([symbol side base-price qty levels]
   (ladder! symbol side base-price qty levels 0.01))
  ([symbol side base-price qty levels step]
   (let [order-fn (if (= side :buy) buy sell)
         step-mult (if (= side :buy) -1 1)]
     (dotimes [i levels]
       (let [price (+ base-price (* i step step-mult))]
         (order-fn symbol price qty))))))

;; =============================================================================
;; Scenarios
;; =============================================================================

(defn scenario
  "Run a test scenario.
   
   Examples:
     (scenario 1)   ; Simple orders
     (scenario 2)   ; Matching trade
     (scenario 20)  ; 1K matching stress test
     (scenario)     ; List available scenarios"
  ([]
   (scenarios/list-scenarios))
  ([n]
   (let [conn (:conn @state)]
     (when-not conn
       (throw (ex-info "Not connected. Call (start!) first." {})))
     (scenarios/run! conn n))))

(defn scenarios
  "List available test scenarios."
  []
  (scenarios/list-scenarios))

;; =============================================================================
;; Status
;; =============================================================================

(defn status
  "Print current status."
  []
  (let [{:keys [conn user-id next-order-id history]} @state]
    (println "=== Matching Engine Client ===")
    (println (format "  Connected: %s" (if (and conn (client/connected? conn))
                                         (format "yes (%s:%d via %s)"
                                                 (:host conn) 
                                                 (:port conn)
                                                 (name (:type conn)))
                                         "no")))
    (when conn
      (println (format "  Protocol:  %s" (name (or (client/get-protocol conn) :unknown)))))
    (println (format "  User ID:   %d" user-id))
    (println (format "  Next Order: %d" next-order-id))
    (println (format "  History:   %d messages" (count history)))))

(defn help
  "Print available commands."
  []
  (println "
=== Matching Engine Client Commands ===

Connection:
  (start!)                    Connect, auto-detect protocol
  (start! port)               Connect to localhost:port
  (start! host port)          Connect to host:port
  (start! :binary)            Force binary protocol
  (start! :csv)               Force CSV protocol
  (start! {:transport :udp})  Use UDP transport
  (stop!)                     Disconnect
  (connected?)                Check connection status
  (protocol)                  Show current protocol

Trading:
  (buy sym price qty)         Place buy order
  (sell sym price qty)        Place sell order
  (cancel sym order-id)       Cancel order
  (flush!)                    Clear all order books

Reading:
  (recv)                      Get pending messages
  (show)                      Show recent history
  (show n)                    Show last n messages

Scenarios:
  (scenario)                  List available scenarios
  (scenario 1)                Run scenario 1 (simple orders)
  (scenario 2)                Run scenario 2 (matching trade)
  (scenario 20)               Run 1K matching stress test
  (scenario 22)               Run 100K matching stress test

Utilities:
  (user! id)                  Set user ID
  (match! sym price qty)      Create matching trade
  (ladder! sym side price qty levels)  Place ladder of orders
  (status)                    Show status
  (help)                      Show this help

Examples:
  (start!)
  (buy \"IBM\" 100.50 100)
  (sell \"IBM\" 100.50 100)   ; Creates trade
  (scenario 20)               ; Run 1K matching stress test
"))

;; Print welcome on load
(println "
╔════════════════════════════════════════════════════════════╗
║           Clojure Matching Engine Client                   ║
╠════════════════════════════════════════════════════════════╣
║  (start!)                 Connect to localhost:1234        ║
║  (start! 9000)            Connect to different port        ║
║  (start! \"host\" 1234)     Connect to remote host           ║
╠════════════════════════════════════════════════════════════╣
║  (buy \"IBM\" 100.50 100)   Buy 100 shares @ $100.50         ║
║  (sell \"IBM\" 100.50 100)  Sell 100 shares @ $100.50        ║
║  (cancel \"IBM\" 1)         Cancel order #1                  ║
║  (flush!)                 Clear all order books            ║
╠════════════════════════════════════════════════════════════╣
║  (scenario)               List all test scenarios          ║
║  (scenario 2)             Run matching trade test          ║
║  (scenario 20)            Run 1K stress test               ║
║  (scenario 22)            Run 100K stress test             ║
╠════════════════════════════════════════════════════════════╣
║  (show)                   Show recent messages             ║
║  (status)                 Show connection status           ║
║  (stop!)                  Disconnect                       ║
║  (help)                   Show all commands                ║
╚════════════════════════════════════════════════════════════╝
")

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
