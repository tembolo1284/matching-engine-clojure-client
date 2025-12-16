# Matching Engine Client & Relay

A Clojure toolkit for interacting with binary-protocol matching engines. Includes an interactive REPL client, a WebSocket relay server, and a ClojureScript position manager UI.

## Features

- **Binary Protocol**: Full encoder/decoder for the matching engine wire format
- **Multiple Transports**: TCP, UDP, and multicast support
- **REPL Client**: Interactive trading terminal with test scenarios
- **WebSocket Relay**: Broadcast market data to browser clients
- **Position Manager UI**: Real-time ClojureScript dashboard for orders and trades
- **Standalone JARs**: Deploy without Clojure installed

## Quick Start

See [QUICK_START.md](QUICK_START.md) for a complete walkthrough.
```bash
# Start relay with UI
clojure -M:relay -e localhost:1234 -w 8080

# Open browser
open http://localhost:8080

# Send orders via REPL
clojure -M:repl
(start!)
(buy "IBM" 100.00 50)
```

## Installation

### Prerequisites

- Java 11+
- Clojure CLI (clj)
- Node.js 16+ (for UI development only)

### Clone and Build
```bash
git clone <repo-url>
cd me-client

# Install ClojureScript dependencies (optional, for UI dev)
npm install

# Build production JARs
clojure -X:build-client
clojure -X:build-relay

# Build production JS
npm run release
```

## Components

### REPL Client

Interactive client for sending orders and viewing responses:
```bash
clojure -M:repl
```
```clojure
(start!)                      ; Connect to localhost:1234
(start! "host" 9000)          ; Connect to custom host/port
(start! {:transport :udp})    ; Use UDP transport

(buy "IBM" 100.00 50)         ; Buy 50 shares @ $100.00
(sell "IBM" 100.00 50)        ; Sell 50 shares @ $100.00
(cancel "IBM" 1)              ; Cancel order #1
(flush!)                      ; Clear all order books

(show)                        ; View last 10 messages
(show 50)                     ; View last 50 messages
(scenario)                    ; List test scenarios
(scenario 2)                  ; Run matching trade test

(status)                      ; Connection status
(stop!)                       ; Disconnect
(help)                        ; Show all commands
```

### Relay Server

WebSocket relay for browser clients:
```bash
# Basic usage
clojure -M:relay -e localhost:1234 -w 8080

# With multicast market data
clojure -M:relay -t multicast -m 239.255.1.1 -w 8080

# Verbose logging
clojure -M:relay -e localhost:1234 -v

# Custom message filter
clojure -M:relay -e localhost:1234 -f trade,order-ack

# See all options
clojure -M:relay --help
```

### Position Manager UI

Real-time dashboard showing orders, trades, and message log:
```bash
# Development mode (hot reload)
npm run dev
# Open http://localhost:3000

# Production build
npm run release
# JS compiled to resources/public/js/main.js
```

Features:
- Real-time order tracking
- Trade execution feed
- Message log with filtering
- Symbol filter
- Connection status indicator
- Statistics dashboard

## Building JARs

Build standalone JARs for deployment:
```bash
# Build REPL client JAR
clojure -X:build-client

# Build relay server JAR
clojure -X:build-relay

# JARs created in target/
ls target/
# me-client.jar
# me-relay.jar
```

### Running JARs
```bash
# Run client
java -jar target/me-client.jar localhost 1234

# Run relay server
java -jar target/me-relay.jar --engine localhost:1234 --ws-port 8080
```

## Architecture
```
┌─────────────────────────────────────────────────────────────────────┐
│                           me-client                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌────────────────────────┐      ┌────────────────────────┐        │
│  │     protocol.clj       │      │     transport.clj      │        │
│  │  ┌────────────────┐    │      │  ┌────────────────┐    │        │
│  │  │ Binary Codec   │    │      │  │ TCP            │    │        │
│  │  │ Message Framing│    │      │  │ UDP            │    │        │
│  │  │ Type Constants │    │      │  │ Multicast      │    │        │
│  │  └────────────────┘    │      │  └────────────────┘    │        │
│  └───────────┬────────────┘      └───────────┬────────────┘        │
│              │                               │                      │
│              └───────────┬───────────────────┘                      │
│                          │                                          │
│          ┌───────────────┼───────────────┐                         │
│          │               │               │                         │
│   ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐                  │
│   │   client/   │ │    relay/   │ │    cljs/    │                  │
│   │             │ │             │ │             │                  │
│   │  core.clj   │ │  config.clj │ │  config.cljs│                  │
│   │             │ │  engine.clj │ │  db.cljs    │                  │
│   │  REPL CLI   │ │  websocket  │ │  events.cljs│                  │
│   │  Orders     │ │  core.clj   │ │  subs.cljs  │                  │
│   │  Scenarios  │ │             │ │  views.cljs │                  │
│   │             │ │             │ │  core.cljs  │                  │
│   └─────────────┘ └──────┬──────┘ └──────┬──────┘                  │
│                          │               │                          │
│                          │    WebSocket  │                          │
│                          └───────┬───────┘                          │
│                                  │                                  │
│                          ┌───────▼───────┐                          │
│                          │   Browser     │                          │
│                          │   Position    │                          │
│                          │   Manager UI  │                          │
│                          └───────────────┘                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Configuration

### Relay Server Options

| Flag | Env Var | Default | Description |
|------|---------|---------|-------------|
| `-e, --engine HOST:PORT` | `ENGINE_HOST`, `ENGINE_PORT` | localhost:1234 | Engine address |
| `-t, --transport TYPE` | `ENGINE_TRANSPORT` | tcp | tcp, udp, or multicast |
| `-m, --multicast GROUP` | `MULTICAST_GROUP` | - | Multicast group address |
| `-i, --interface IFACE` | `MULTICAST_IFACE` | - | Network interface |
| `-w, --ws-port PORT` | `WS_PORT` | 8080 | WebSocket port |
| `-b, --bind HOST` | `WS_HOST` | 0.0.0.0 | Bind address |
| `-f, --filter TYPES` | `MESSAGE_FILTER` | trade,order-ack,... | Message types to relay |
| `-c, --config FILE` | - | relay.edn | Config file path |
| `--no-static` | `SERVE_STATIC` | true | Disable static file serving |
| `-v, --verbose` | `LOG_LEVEL` | info | Enable debug logging |

### Config File

Create `relay.edn` in the working directory:
```clojure
{:engine-host "192.168.1.100"
 :engine-port 1234
 :engine-transport :tcp
 :ws-port 8080
 :ws-host "0.0.0.0"
 :serve-static true
 :message-filter #{:trade :order-ack :order-reject :cancel-ack :cancel-reject}
 :log-level :info}
```

### Environment Variables
```bash
export ENGINE_HOST=192.168.1.100
export ENGINE_PORT=1234
export ENGINE_TRANSPORT=tcp
export WS_PORT=8080
export MESSAGE_FILTER=trade,order-ack,cancel-ack
export LOG_LEVEL=debug
```

## Protocol

Wire format (little-endian):
```
┌──────────┬──────────┬─────────────────┐
│ Magic    │ Length   │ Payload         │
│ 2 bytes  │ 2 bytes  │ N bytes         │
│ 0x4D45   │ uint16   │ (type+fields)   │
└──────────┴──────────┴─────────────────┘
```

### Message Types

| Type | Code | Direction | Description |
|------|------|-----------|-------------|
| NewOrder | 0x01 | Client→Engine | Submit new order |
| Cancel | 0x02 | Client→Engine | Cancel order |
| Flush | 0x03 | Client→Engine | Clear all books |
| OrderAck | 0x10 | Engine→Client | Order accepted |
| OrderReject | 0x11 | Engine→Client | Order rejected |
| CancelAck | 0x12 | Engine→Client | Cancel confirmed |
| CancelReject | 0x13 | Engine→Client | Cancel rejected |
| Trade | 0x20 | Engine→Client | Trade executed |
| BookUpdate | 0x21 | Engine→Client | Book changed |

### Field Sizes

| Field | Size | Type |
|-------|------|------|
| user-id | 4 bytes | uint32 |
| order-id | 4 bytes | uint32 |
| symbol | 8 bytes | ASCII, space-padded |
| price | 8 bytes | float64 |
| qty | 4 bytes | uint32 |
| side | 1 byte | 0x01=buy, 0x02=sell |
| reason | 1 byte | reject reason code |

## WebSocket API

Connect to `ws://host:port/ws` to receive JSON messages:
```javascript
const ws = new WebSocket('ws://localhost:8080/ws');

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  
  switch (msg.type) {
    case 'trade':
      console.log(`Trade: ${msg.symbol} ${msg.qty} @ ${msg.price}`);
      break;
    case 'order-ack':
      console.log(`Order ${msg['order-id']} acknowledged`);
      break;
    case 'cancel-ack':
      console.log(`Cancel ${msg['order-id']} confirmed`);
      break;
  }
};
```

### Message Formats

**Trade**
```json
{
  "type": "trade",
  "symbol": "IBM",
  "price": 100.50,
  "qty": 100,
  "buy-user-id": 1,
  "buy-order-id": 1,
  "sell-user-id": 2,
  "sell-order-id": 1
}
```

**Order Ack**
```json
{
  "type": "order-ack",
  "user-id": 1,
  "order-id": 1,
  "symbol": "IBM"
}
```

**Cancel Ack**
```json
{
  "type": "cancel-ack",
  "user-id": 1,
  "order-id": 1,
  "symbol": "IBM"
}
```

### HTTP Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /` | Position Manager UI |
| `GET /ws` | WebSocket upgrade |
| `GET /health` | Server health check |
| `GET /clients` | Connected client list |

## Project Structure
```
me-client/
├── deps.edn                    # Clojure dependencies
├── shadow-cljs.edn             # ClojureScript build config
├── package.json                # Node dependencies
├── README.md
├── QUICK_START.md
├── relay.edn                   # Optional config file
├── src/
│   ├── me_client/              # Clojure source
│   │   ├── protocol.clj        # Binary codec (shared)
│   │   ├── transport.clj       # TCP/UDP/multicast (shared)
│   │   ├── client/
│   │   │   └── core.clj        # REPL client
│   │   └── relay/
│   │       ├── config.clj      # CLI/env/file config
│   │       ├── engine.clj      # Engine connection
│   │       ├── websocket.clj   # WebSocket server
│   │       └── core.clj        # Main entry point
│   └── cljs/
│       └── position_manager/   # ClojureScript source
│           ├── config.cljs
│           ├── db.cljs
│           ├── events.cljs
│           ├── subs.cljs
│           ├── views.cljs
│           ├── websocket.cljs
│           └── core.cljs
├── test/
│   └── me_client/
│       ├── protocol_test.clj
│       ├── transport_test.clj
│       ├── config_test.clj
│       ├── websocket_test.clj
│       └── engine_test.clj
├── resources/
│   └── public/
│       ├── index.html
│       └── js/                 # Compiled ClojureScript
└── target/
    ├── me-client.jar
    └── me-relay.jar
```

## Development
```bash
# Start nREPL for editor integration
clojure -M:nrepl

# Run tests
clojure -X:test

# ClojureScript development
npm run dev

# Clean build artifacts
npm run clean
rm -rf target/
```

## Testing
```bash
# Run all tests
clojure -X:test

# Run specific namespace
clojure -M:test -n me-client.protocol-test

# Run with verbose output
clojure -M:test -v
```

## Compatibility

This client is compatible with matching engines using the standard binary protocol:

- [C Matching Engine](../matching-engine-c/)
- [Rust Matching Engine](../matching-engine-rust/)
- [Zig Matching Engine](../matching-engine-zig/)
- [Java Matching Engine](../matching-engine-java/)

## Troubleshooting

### Connection Refused
```
Error: Connection refused
```
Ensure the matching engine is running and the host/port are correct.

### Multicast Not Receiving
```bash
# Check interface
ip addr show

# Use specific interface
clojure -M:relay -t multicast -m 239.255.1.1 -i eth0
```

### JAR Build Fails
```bash
# Ensure AOT compilation
clojure -X:build-relay
```

### UI Not Loading
```bash
# Build JS first
npm run release

# Or run in dev mode
npm run dev
```
