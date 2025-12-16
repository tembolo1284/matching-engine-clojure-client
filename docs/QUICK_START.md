# Quick Start Guide

Get up and running with the Matching Engine Client in 5 minutes.

## Prerequisites

- Java 11+ (`java -version`)
- Clojure CLI (`clj --version`)
- A running matching engine on `localhost:1234`

## Option 1: Interactive REPL (Fastest)
```bash
# Start the REPL client
clojure -M:repl
```

You'll see the welcome screen:
```
╔════════════════════════════════════════════════════════════╗
║         MATCHING ENGINE CLIENT                             ║
║                                                            ║
║  Type (help) for commands, (start!) to connect             ║
╚════════════════════════════════════════════════════════════╝
```

Connect and send orders:
```clojure
;; Connect to engine
(start!)
;; => Connected to localhost:1234 via tcp

;; Send a buy order
(buy "IBM" 100.00 50)
;; => Sent BUY IBM 50 @ 100.00 (order #1)
;; =>   ACK order #1 IBM

;; Send matching sell order
(sell "IBM" 100.00 50)
;; => Sent SELL IBM 50 @ 100.00 (order #2)
;; =>   ACK order #2 IBM
;; =>   TRADE IBM 50 @ 100.00 (buy:1/1 sell:1/2)

;; View recent messages
(show)

;; Run test scenarios
(scenario)    ; List all
(scenario 2)  ; Run matching trade test

;; Disconnect
(stop!)
```

## Option 2: Relay Server + Browser UI
```bash
# Terminal 1: Start relay server
clojure -M:relay -e localhost:1234 -w 8080
```
```
[12:34:56.789] [info] Starting Matching Engine Relay
[12:34:56.790] [info] Engine: localhost:1234 via tcp
[12:34:56.791] [info] WebSocket: 0.0.0.0:8080

Relay server running. Press Ctrl+C to stop.
WebSocket endpoint: ws://0.0.0.0:8080/ws
UI available at: http://0.0.0.0:8080/
```

Open http://localhost:8080 in your browser. You'll see the Position Manager UI.
```bash
# Terminal 2: Send orders via REPL
clojure -M:repl
```
```clojure
(start!)
(scenario 2)  ; Watch trades appear in browser!
```

## Option 3: Standalone JARs

Build once, run anywhere:
```bash
# Build JARs
clojure -X:build-client
clojure -X:build-relay

# Run relay
java -jar target/me-relay.jar --engine localhost:1234 --ws-port 8080

# Run client (in another terminal)
java -jar target/me-client.jar localhost 1234
```

## Common Workflows

### Testing Order Flow
```clojure
(start!)

;; Simple order
(buy "AAPL" 150.00 100)

;; Matching trade
(buy "GOOG" 100.00 50)
(sell "GOOG" 100.00 50)

;; Partial fill
(buy "MSFT" 200.00 100)
(sell "MSFT" 200.00 30)

;; Cancel order
(let [oid (buy "TSLA" 300.00 50)]
  (Thread/sleep 100)
  (cancel "TSLA" oid))

;; Clear books
(flush!)
```

### Stress Testing
```clojure
(start!)

;; 100 orders
(scenario 20)

;; Custom stress test
(dotimes [i 100]
  (buy "STRESS" (+ 100.0 (rand 10)) 10)
  (sell "STRESS" (+ 100.0 (rand 10)) 10))
```

### Different Transports
```clojure
;; TCP (default)
(start!)

;; UDP
(start! {:transport :udp})

;; Custom host/port
(start! "192.168.1.100" 9000)
```

### Relay with Multicast
```bash
# Subscribe to multicast market data feed
clojure -M:relay -t multicast -m 239.255.1.1 -w 8080

# With specific network interface
clojure -M:relay -t multicast -m 239.255.1.1 -i eth0 -w 8080
```

### Filter Message Types
```bash
# Only relay trades
clojure -M:relay -e localhost:1234 -f trade

# Trades and acks
clojure -M:relay -e localhost:1234 -f trade,order-ack,cancel-ack
```

## Configuration File

Create `relay.edn` for persistent settings:
```clojure
{:engine-host "localhost"
 :engine-port 1234
 :ws-port 8080
 :message-filter #{:trade :order-ack :cancel-ack}
 :log-level :info}
```

Then just run:
```bash
clojure -M:relay
```

## Environment Variables
```bash
export ENGINE_HOST=localhost
export ENGINE_PORT=1234
export WS_PORT=8080

clojure -M:relay
```

## Verify Everything Works
```bash
# 1. Check health endpoint
curl http://localhost:8080/health
# {"status":"ok","clients":0,"uptime-ms":12345}

# 2. Check connected clients
curl http://localhost:8080/clients
# []

# 3. Test WebSocket (requires websocat or similar)
websocat ws://localhost:8080/ws
```

## Next Steps

- Read the full [README.md](README.md) for detailed documentation
- Explore the [Protocol](README.md#protocol) specification
- Check out [WebSocket API](README.md#websocket-api) for custom integrations
- Run [tests](README.md#testing) to verify your setup

## Troubleshooting

**Connection refused**
```
Make sure matching engine is running on the specified host:port
```

**No messages in UI**
```
1. Check relay is connected (look for "Connected to matching engine" log)
2. Verify message filter includes the types you're sending
3. Open browser dev tools, check WebSocket connection
```

**REPL won't start**
```bash
# Check Java version
java -version  # Should be 11+

# Check Clojure
clj --version
```
