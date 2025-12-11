# Clojure Matching Engine Client

A REPL-friendly client for the matching engine with protocol auto-detection, TCP/UDP support, and stress test scenarios.

## Features

- **Protocol Auto-Detection**: Automatically detects binary vs CSV protocol
- **Dual Transport**: TCP (default) and UDP with bidirectional communication
- **Stress Test Scenarios**: 15 scenarios from basic tests to 250M trade stress tests
- **Wire Compatible**: Works with C, Rust, Zig, and Java matching engines
- **REPL-Driven**: Interactive trading from the Clojure REPL

## Quick Start

```bash
# Start the matching engine (any language version)
cd ~/java-workspace/matching-engine-java
java -jar target/java-matching-engine-0.1.0-SNAPSHOT.jar

# In another terminal, start the Clojure REPL
cd clojure-me-client
clj -M:repl
```

```clojure
;; Connect with auto protocol detection
(start!)
;; => Connected to localhost:1234 via tcp
;; => Detecting protocol... binary

;; Place orders
(buy "IBM" 100.50 100)    ; Buy 100 IBM @ $100.50
(sell "IBM" 100.50 100)   ; Sell - creates trade!

;; Run stress test
(scenario 20)             ; 1K matching trades

;; Disconnect
(stop!)
```

## Installation

Requires [Clojure CLI tools](https://clojure.org/guides/install_clojure).

```bash
cd clojure-me-client
clj -M:repl
```

## API Reference

### Connection

```clojure
;; Basic connection (TCP, auto-detect protocol)
(start!)                          ; localhost:1234
(start! 9000)                     ; localhost:9000
(start! "host" 1234)              ; custom host:port

;; Force protocol
(start! :binary)                  ; Force binary protocol
(start! :csv)                     ; Force CSV protocol

;; UDP transport
(start! {:transport :udp})        ; UDP to localhost:1234
(start! "host" 1234 {:transport :udp :protocol :binary})

;; Connection management
(stop!)                           ; Disconnect
(connected?)                      ; Check connection status
(protocol)                        ; Show current protocol (:binary or :csv)
```

### Trading

```clojure
(buy symbol price qty)            ; Place buy order
(buy "IBM" 100.50 100)
(buy "AAPL" 150 50 1001)          ; With explicit order ID

(sell symbol price qty)           ; Place sell order
(sell "IBM" 100.50 100)

(cancel symbol order-id)          ; Cancel order
(cancel "IBM" 1)

(flush!)                          ; Clear all order books
```

### Reading

```clojure
(recv)                            ; Get pending messages
(show)                            ; Show last 10 messages
(show 20)                         ; Show last 20 messages
```

### Scenarios

```clojure
(scenario)                        ; List all scenarios
(scenario 1)                      ; Simple orders
(scenario 2)                      ; Matching trade
(scenario 3)                      ; Cancel order
(scenario 20)                     ; 1K matching stress
(scenario 22)                     ; 100K matching stress
(scenario 25)                     ; 250M trades (LEGENDARY)
(scenario 30)                     ; Dual-processor 500K
```

**Available Scenarios:**

| # | Name | Description |
|---|------|-------------|
| 1 | Simple Orders | Two non-matching orders |
| 2 | Matching Trade | Buy and sell at same price |
| 3 | Cancel Order | Place and cancel |
| 10-12 | Unmatched Stress | 1K, 10K, 100K unmatched orders |
| 20-25 | Matching Stress | 1K to 250M matching trades |
| 30-32 | Dual Processor | 500K to 100M trades on IBM+NVDA |

### Utilities

```clojure
(user! 42)                        ; Set user ID

(match! "IBM" 100 50)             ; Create matching trade (buy + sell)

(ladder! "IBM" :buy 100 10 5)     ; 5 buy orders from $100 down
(ladder! "IBM" :sell 101 10 5)    ; 5 sell orders from $101 up

(status)                          ; Show connection status
(help)                            ; Show all commands
```

## Protocol Details

The client auto-detects the server's protocol by:
1. Sending a binary probe order
2. If binary response received → use binary
3. Otherwise try CSV probe → use CSV
4. Default to binary if no response

### Binary Protocol

- Magic byte: `0x4D` ('M')
- Big-endian integers
- 8-byte null-padded symbols

| Message | Size |
|---------|------|
| NewOrder | 27 bytes |
| Cancel | 18 bytes |
| Flush | 2 bytes |
| Ack | 18 bytes |
| Trade | 34 bytes |
| TopOfBook | 20 bytes |

### CSV Protocol

```
N,<user_id>,<symbol>,<price>,<qty>,<side>,<order_id>
C,<user_id>,<symbol>,<order_id>
F
A,<symbol>,<user_id>,<order_id>
T,<symbol>,<buy_uid>,<buy_oid>,<sell_uid>,<sell_oid>,<price>,<qty>
B,<symbol>,<side>,<price>,<qty>
```

## Example Session

```clojure
user=> (start!)
Connected to localhost:1234 via tcp
Detecting protocol... binary
:connected

user=> (buy "IBM" 100 50)
→ BUY IBM $100.00 qty=50 (order 1)
  ACK: IBM user=1 order=1
  TOB: IBM buy $100.00 qty=50
1

user=> (sell "IBM" 100 50)
→ SELL IBM $100.00 qty=50 (order 2)
  ACK: IBM user=1 order=2
  TRADE: IBM $100.00 qty=50 buy=1/1 sell=1/2
  TOB: IBM buy ELIMINATED
2

user=> (scenario 20)
=== Matching Stress Test: 1K Trades ===

Target: 1K trades (2K orders)
Throttling: 50 pairs/batch, 10ms delay (interleaved recv)
  10% | 100 pairs | 123 ms | 813 trades/sec | recv'd: 487
  20% | 200 pairs | 246 ms | 813 trades/sec | recv'd: 974
  ...

=== Send Results ===
Trade pairs:     1000
Orders sent:     2000
Send errors:     0
Total time:      1.234 sec

=== Throughput ===
Orders/sec:      1.62K/sec
Trades/sec:      810/sec

=== Server Response Summary ===
ACKs:            2000
Trades:          1000
Top of Book:     2000
Total messages:  5000

=== Validation ===
ACKs:            2000/2000 ✓ PASS
Trades:          1000/1000 ✓ PASS

*** TEST PASSED ***

user=> (stop!)
Disconnected
:disconnected
```

## Wire Compatibility

This client is compatible with:

| Engine | Binary | CSV |
|--------|--------|-----|
| C Matching Engine | ✅ | ✅ |
| Rust Matching Engine | ✅ | ✅ |
| Zig Matching Engine | ✅ | ✅ |
| Java Matching Engine | ✅ | ✅ |

## Development

```bash
# Run tests
clj -M:test

# Start dev REPL
clj -M:dev
```

