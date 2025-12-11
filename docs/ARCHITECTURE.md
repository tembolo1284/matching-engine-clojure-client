# Architecture

Technical design of the Clojure Matching Engine Client.

## Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      REPL Interface                         │
│                       (core.clj)                            │
│  (start!) (buy) (sell) (cancel) (scenario) (show) (help)   │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                    Scenario Runner                          │
│                    (scenarios.clj)                          │
│  Basic tests, stress tests, dual-processor tests            │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                    Client Layer                             │
│                    (client.clj)                             │
│  Connection management, protocol detection, send/receive    │
└──────────┬─────────────────────────────────┬────────────────┘
           │                                 │
┌──────────▼──────────┐           ┌──────────▼──────────┐
│   TCP Transport     │           │   UDP Transport     │
│  Length-prefixed    │           │  Datagram-based     │
│  framing (4 bytes)  │           │  bidirectional      │
└──────────┬──────────┘           └──────────┬──────────┘
           │                                 │
           └────────────────┬────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│                   Protocol Layer                            │
│                   (protocol.clj)                            │
│  Binary codec (27/18/2 byte messages)                       │
│  CSV codec (newline-terminated text)                        │
│  Auto-detection (magic byte 0x4D)                           │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
                   ┌─────────────────┐
                   │ Matching Engine │
                   │  (any language) │
                   └─────────────────┘
```

## Module Structure

```
src/me_client/
├── protocol.clj    ; Wire protocol encoding/decoding
├── client.clj      ; Transport and connection management
├── scenarios.clj   ; Test scenario runner
└── core.clj        ; REPL-friendly API
```

### protocol.clj

Low-level wire protocol implementation.

**Responsibilities:**
- Binary message encoding (NewOrder, Cancel, Flush)
- Binary message decoding (Ack, CancelAck, Trade, TopOfBook)
- CSV message encoding/decoding
- Protocol auto-detection via magic byte
- Human-readable message formatting

**Key Functions:**
```clojure
;; Binary encoding
(encode-new-order user-id symbol price qty side order-id) → ByteBuffer
(encode-cancel user-id symbol order-id) → ByteBuffer
(encode-flush) → ByteBuffer

;; Binary decoding
(decode-output ByteBuffer) → {:type :ack/:trade/... ...}
(decode-output-bytes byte-array) → map

;; CSV encoding
(csv-encode-new-order ...) → byte-array
(csv-encode-cancel ...) → byte-array
(csv-encode-flush) → byte-array

;; CSV decoding
(csv-decode-output string) → map

;; Auto-detection
(binary-message? byte-array) → boolean
(decode-auto byte-array) → map
```

### client.clj

Transport abstraction and connection management.

**Responsibilities:**
- TCP connection with length-prefix framing
- UDP socket with bidirectional communication
- Protocol auto-detection handshake
- Non-blocking receive with timeouts

**Connection Map Structure:**
```clojure
;; TCP connection
{:type :tcp
 :socket Socket
 :in DataInputStream
 :out DataOutputStream
 :host "localhost"
 :port 1234
 :protocol (atom :binary)}  ; Detected protocol

;; UDP connection
{:type :udp
 :socket DatagramSocket
 :server-addr InetAddress
 :server-port 1234
 :host "localhost"
 :port 1234
 :recv-buf byte-array
 :protocol (atom :binary)}
```

**Key Functions:**
```clojure
;; Connection
(connect host port opts) → conn
(disconnect conn)
(connected? conn) → boolean

;; Protocol
(detect-protocol! conn) → :binary/:csv
(get-protocol conn) → :binary/:csv
(set-protocol! conn proto)

;; Send/Receive
(send-message! conn :new-order user-id symbol price qty side order-id)
(send-message! conn :cancel user-id symbol order-id)
(send-message! conn :flush)
(recv-message conn timeout-ms) → map or nil
(recv-all conn timeout-ms) → [maps]
```

### scenarios.clj

Test scenario runner mirroring the Zig implementation.

**Responsibilities:**
- Basic functional tests (orders, trades, cancels)
- Unmatched stress tests (1K-100K orders)
- Matching stress tests (1K-250M trades)
- Dual-processor tests (IBM + NVDA)
- Response statistics and validation

**Statistics Structure:**
```clojure
{:acks (atom 0)
 :cancel-acks (atom 0)
 :trades (atom 0)
 :top-of-book (atom 0)
 :rejects (atom 0)
 :parse-errors (atom 0)}
```

**Scenario Registry:**
```clojure
{1  "Simple Orders"
 2  "Matching Trade"
 3  "Cancel Order"
 10 "Unmatched 1K"
 11 "Unmatched 10K"
 12 "Unmatched 100K"
 20 "1K trades"
 21 "10K trades"
 22 "100K trades"
 23 "250K trades"
 24 "500K trades"
 25 "250M trades (LEGENDARY)"
 30 "Dual 500K"
 31 "Dual 1M"
 32 "Dual 100M (ULTIMATE)"}
```

### core.clj

REPL-friendly API with state management.

**Responsibilities:**
- Global connection state
- Auto-incrementing order IDs
- Message history (last 100)
- User-friendly command wrappers
- Help and status display

**State Atom:**
```clojure
{:conn nil           ; Current connection
 :user-id 1          ; Default user ID
 :next-order-id 1    ; Auto-increment counter
 :history []}        ; Recent messages (max 100)
```

## Wire Protocol

### Binary Format

All binary messages start with magic byte `0x4D` ('M').
Integers are big-endian.

**Input Messages (Client → Server):**

```
NewOrder (27 bytes):
┌───────┬──────┬────────┬────────┬───────┬─────┬──────┬─────────┐
│ Magic │ Type │ UserID │ Symbol │ Price │ Qty │ Side │ OrderID │
│  1B   │  1B  │   4B   │   8B   │  4B   │ 4B  │  1B  │   4B    │
└───────┴──────┴────────┴────────┴───────┴─────┴──────┴─────────┘
  0x4D    'N'    BE u32   ASCII    cents   BE    B/S    BE u32

Cancel (18 bytes):
┌───────┬──────┬────────┬────────┬─────────┐
│ Magic │ Type │ UserID │ Symbol │ OrderID │
│  1B   │  1B  │   4B   │   8B   │   4B    │
└───────┴──────┴────────┴────────┴─────────┘
  0x4D    'C'    BE u32   ASCII    BE u32

Flush (2 bytes):
┌───────┬──────┐
│ Magic │ Type │
│  1B   │  1B  │
└───────┴──────┘
  0x4D    'F'
```

**Output Messages (Server → Client):**

```
Ack (18 bytes):
┌───────┬──────┬────────┬────────┬─────────┐
│ Magic │ Type │ Symbol │ UserID │ OrderID │
│  1B   │  1B  │   8B   │   4B   │   4B    │
└───────┴──────┴────────┴────────┴─────────┘
  0x4D    'A'    ASCII    BE u32   BE u32

CancelAck (18 bytes): Same as Ack with Type = 'X'

Trade (34 bytes):
┌───────┬──────┬────────┬────────┬────────┬─────────┬─────────┬───────┬─────┐
│ Magic │ Type │ Symbol │ BuyUID │ BuyOID │ SellUID │ SellOID │ Price │ Qty │
│  1B   │  1B  │   8B   │   4B   │   4B   │   4B    │   4B    │  4B   │ 4B  │
└───────┴──────┴────────┴────────┴────────┴─────────┴─────────┴───────┴─────┘
  0x4D    'T'    ASCII    BE u32   BE u32   BE u32    BE u32    cents  BE u32

TopOfBook (20 bytes):
┌───────┬──────┬────────┬──────┬───────┬─────┬─────┐
│ Magic │ Type │ Symbol │ Side │ Price │ Qty │ Pad │
│  1B   │  1B  │   8B   │  1B  │  4B   │ 4B  │ 1B  │
└───────┴──────┴────────┴──────┴───────┴─────┴─────┘
  0x4D    'B'    ASCII    B/S   cents   BE    0x00
```

### CSV Format

Newline-terminated, comma-separated values.

```
Input:
N,<user_id>,<symbol>,<price>,<qty>,<side>,<order_id>\n
C,<user_id>,<symbol>,<order_id>\n
F\n

Output:
A,<symbol>,<user_id>,<order_id>\n
X,<symbol>,<user_id>,<order_id>\n
T,<symbol>,<buy_uid>,<buy_oid>,<sell_uid>,<sell_oid>,<price>,<qty>\n
B,<symbol>,<side>,<price>,<qty>\n
```

### TCP Framing

All TCP messages are length-prefixed:

```
┌─────────────────┬──────────────────────────────┐
│ Length (4 bytes)│ Payload (Length bytes)       │
│ Big-endian u32  │ Binary or CSV message        │
└─────────────────┴──────────────────────────────┘
```

### Protocol Detection

The client detects the server's protocol by:

1. Send a binary probe order (user=999999, symbol="PROBE")
2. Wait 100ms for response
3. If response starts with `0x4D` → binary protocol
4. Otherwise, send CSV probe order
5. Check response format
6. Default to binary if no response

## Data Flow

### Order Submission

```
User: (buy "IBM" 100.50 100)
         │
         ▼
    core.clj: Convert $100.50 → 10050 cents
         │
         ▼
    client.clj: Check protocol atom
         │
         ├── :binary → protocol.clj: encode-new-order
         │                  │
         │                  ▼
         │             ByteBuffer (27 bytes)
         │
         └── :csv → protocol.clj: csv-encode-new-order
                          │
                          ▼
                     byte-array "N,1,IBM,10050,100,B,1\n"
         │
         ▼
    client.clj: send-raw!
         │
         ├── :tcp → tcp-write-frame (prepend 4-byte length)
         │
         └── :udp → udp-send (raw datagram)
         │
         ▼
    Network → Matching Engine
```

### Response Handling

```
    Matching Engine → Network
         │
         ▼
    client.clj: recv-raw
         │
         ├── :tcp → tcp-read-frame (read length, then payload)
         │
         └── :udp → udp-recv (read datagram)
         │
         ▼
    protocol.clj: decode-auto
         │
         ├── Starts with 0x4D? → decode-output-bytes (binary)
         │
         └── Otherwise → csv-decode-output-bytes
         │
         ▼
    {:type :ack :symbol "IBM" :user-id 1 :order-id 1}
         │
         ▼
    core.clj: print-msg, add-to-history!
```

## Stress Test Architecture

### Interleaved Send/Receive

For high-volume tests, we interleave sending and receiving to prevent buffer overflow:

```
┌─────────────────────────────────────────────────────────────┐
│                    Stress Test Loop                         │
├─────────────────────────────────────────────────────────────┤
│  for each batch (50-100 pairs):                             │
│    1. Send buy order                                        │
│    2. Send sell order (creates trade)                       │
│    3. After batch: drain responses (up to 10x batch size)   │
│    4. Sleep delay-ms (10-50ms)                              │
│    5. Update progress                                       │
├─────────────────────────────────────────────────────────────┤
│  After all sent:                                            │
│    1. Wait 3 seconds for TCP flush                          │
│    2. Drain remaining responses (with long timeout)         │
│    3. Validate counts                                       │
└─────────────────────────────────────────────────────────────┘
```

### Throttling Parameters

| Trade Count | Pairs/Batch | Delay (ms) | Drain Timeout |
|-------------|-------------|------------|---------------|
| < 10K       | 50          | 10         | 60 sec        |
| 10K-100K    | 50-100      | 20-30      | 120 sec       |
| 100K-1M     | 100         | 30-50      | 5-10 min      |
| 1M+         | 100         | 50         | 10-30 min     |

### Expected Message Counts

For N matching trades:
- **Orders sent:** 2N (N buys + N sells)
- **ACKs received:** 2N
- **Trades received:** N
- **TopOfBook updates:** ~2N (varies by implementation)
- **Total messages:** ~5N

## Thread Safety

The client is designed for single-threaded REPL use:

- **State atom:** Single global atom, only modified from REPL thread
- **Connection:** Not thread-safe, one connection at a time
- **Scenarios:** Run sequentially, not concurrently

For concurrent testing, create separate connections:

```clojure
;; Don't do this (shares state):
(future (scenario 20))
(future (scenario 21))

;; Do this instead (separate connections):
(let [c1 (client/connect "localhost" 1234 {})
      c2 (client/connect "localhost" 1234 {})]
  (future (scenarios/run! c1 20))
  (future (scenarios/run! c2 21)))
```

## Error Handling

| Error | Cause | Recovery |
|-------|-------|----------|
| Connection refused | Engine not running | Start engine, retry |
| Socket timeout | Slow server | Increase timeout |
| Invalid magic byte | Protocol mismatch | Force correct protocol |
| Parse error | Corrupted data | Log and skip message |

## Compatibility Matrix

| Engine | Binary | CSV | TCP | UDP |
|--------|--------|-----|-----|-----|
| C      | ✅     | ✅  | ✅  | ✅  |
| Rust   | ✅     | ✅  | ✅  | ✅  |
| Zig    | ✅     | ✅  | ✅  | ✅  |
| Java   | ✅     | ✅  | ✅  | ✅  |

All implementations share the same wire protocol, enabling cross-language testing.
