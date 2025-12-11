# Quick Start Guide

Get trading in 60 seconds.

## Prerequisites

- Java 21+ (for the matching engine)
- [Clojure CLI](https://clojure.org/guides/install_clojure)

## 1. Start a Matching Engine

Pick any engine (they're all wire-compatible):

```bash
# Java
cd java-matching-engine
java -jar target/java-matching-engine-0.1.0-SNAPSHOT.jar

# Zig
cd zig-matching-engine
./zig-out/bin/matching-engine

# Rust
cd rust-matching-engine
cargo run --release

# C
cd c-matching-engine
./build/matching_engine
```

You should see:
```
====================================
  Matching Engine v0.1.0
====================================
TCP Port:    1234
Multicast:   239.255.1.1:5555
====================================
```

## 2. Start the Clojure Client

```bash
cd clojure-me-client
clj -M:repl
```

## 3. Connect

```clojure
(start!)
;; Connected to localhost:1234 via tcp
;; Detecting protocol... binary
;; :connected
```

## 4. Trade!

```clojure
;; Place a buy order
(buy "IBM" 100.00 50)
;; → BUY IBM $100.00 qty=50 (order 1)
;;   ACK: IBM user=1 order=1
;;   TOB: IBM buy $100.00 qty=50

;; Place a matching sell order
(sell "IBM" 100.00 50)
;; → SELL IBM $100.00 qty=50 (order 2)
;;   ACK: IBM user=1 order=2
;;   TRADE: IBM $100.00 qty=50 buy=1/1 sell=1/2
;;   TOB: IBM buy ELIMINATED
```

## 5. Run a Stress Test

```clojure
(scenario 20)    ; 1,000 matching trades
```

## 6. Disconnect

```clojure
(stop!)
```

---

## Common Commands

| Command | Description |
|---------|-------------|
| `(start!)` | Connect to localhost:1234 |
| `(start! 9000)` | Connect to different port |
| `(stop!)` | Disconnect |
| `(buy "SYM" price qty)` | Place buy order |
| `(sell "SYM" price qty)` | Place sell order |
| `(cancel "SYM" order-id)` | Cancel order |
| `(flush!)` | Clear all order books |
| `(show)` | Show recent messages |
| `(scenario N)` | Run test scenario |
| `(help)` | Show all commands |

## Scenarios at a Glance

```clojure
(scenario)      ; List all scenarios

;; Basic
(scenario 1)    ; Simple orders
(scenario 2)    ; Matching trade
(scenario 3)    ; Cancel order

;; Stress Tests
(scenario 20)   ; 1K trades
(scenario 21)   ; 10K trades
(scenario 22)   ; 100K trades
(scenario 24)   ; 500K trades
```

## Troubleshooting

**Connection refused?**
- Make sure the matching engine is running
- Check the port number

**Wrong protocol?**
```clojure
(start! :binary)   ; Force binary
(start! :csv)      ; Force CSV
```

**Using UDP?**
```clojure
(start! {:transport :udp})
```

**Check status:**
```clojure
(status)
;; === Matching Engine Client ===
;;   Connected: yes (localhost:1234 via tcp)
;;   Protocol:  binary
;;   User ID:   1
;;   Next Order: 3
;;   History:   5 messages
```
