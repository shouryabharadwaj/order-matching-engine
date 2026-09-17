# CLI Stock Market Order Matching Engine

A terminal-only, multithreaded limit order matching engine written in Java.
It implements strict **price-time priority** matching, persists executed
trades to an **embedded H2 database**, and keeps a plain-text audit trail
of everything that happens. There is no GUI dependency anywhere in this
project — it is built to run entirely from a terminal.

## Features

- Strict price-time priority matching using `TreeMap` (sorted price levels)
  and `LinkedList` (FIFO queue within each level)
- Thread-safe, per-symbol order books guarded by `ReentrantReadWriteLock`
- A fixed worker thread pool (`ExecutorService`) for concurrent order
  submission
- Embedded H2 database — no server install, no external configuration
- Buffered character-stream audit logging (`logs/audit.log`)
- Three modes of operation: interactive REPL, batch CSV ingest, and a
  built-in multithreaded market simulator for benchmarking

## Requirements

- JDK 17 or later
- Maven 3.8+

## Project Layout

```
order-matching-engine/
├── pom.xml
├── README.md
├── sample_orders.csv
└── src/main/java/com/engine/
    ├── Main.java
    ├── model/        (Order, Trade, Side, OrderType)
    ├── core/          (OrderBook, MatchingEngine)
    ├── persistence/   (DatabaseManager, AuditLogger)
    └── simulation/    (MarketSimulator)
```

## Building

From the project root:

```bash
mvn clean package
```

This produces a runnable fat JAR at:

```
target/order-matching-engine.jar
```

(The Maven Shade plugin bundles the H2 JDBC driver into the jar, so this
single file is all you need to run the application anywhere a JVM is
available.)

## Running

### 1. Interactive mode

```bash
java -jar target/order-matching-engine.jar
```

You'll get a `>` prompt. Example session:

```
> order AAPL buy limit 189.50 100 alice
Submitted: Order#1[AAPL BUY LIMIT qty=100/100 price=189.50 client=alice]
No immediate match; order resting on book (or fully consumed if market).

> order AAPL sell limit 189.50 60 bob
Submitted: Order#2[AAPL SELL LIMIT qty=0/60 price=189.50 client=bob]
Matched 1 trade(s):
  Trade#1[AAPL buy=1 sell=2 qty=60 @ 189.50]

> book AAPL
Order Book: AAPL
BIDS            | ASKS
-----------------------------------
189.50 x40      |

> stats
Orders processed : 2
Trades executed  : 1
Trades in DB     : 1

> exit
Shutting down...
```

Type `help` inside the REPL for the full command reference.

### 2. Batch CSV ingest

```bash
java -jar target/order-matching-engine.jar --batch sample_orders.csv
```

CSV columns: `symbol,side,type,price,quantity,clientId`
(leave `price` empty for `MARKET` orders). See `sample_orders.csv` for a
working example.

### 3. Built-in simulation / benchmark mode

```bash
java -jar target/order-matching-engine.jar --simulate --threads 8 --orders 5000
```

This spins up 8 concurrent worker threads, each submitting 5,000 random
(but market-realistic) orders directly against the engine, then prints
throughput and a top-of-book snapshot for each traded symbol. This is the
mode used to produce the benchmark numbers in `Project_Report.pdf`.

## Data & Logs

- H2 database file: `./data/matching_engine.mv.db` (created automatically)
- Audit log: `./logs/audit.log` (created automatically, appended across runs)

Both directories are created on first run — no manual setup required.

## Design Notes

See `Project_Report.pdf` for the full architectural writeup, including the
justification for the `TreeMap` + `LinkedList` book structure, the locking
strategy, and concurrency benchmark results across 1/2/4/8 worker threads.
