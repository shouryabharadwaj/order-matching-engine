# CLI Stock Market Order Matching Engine

Terminal-based multithreaded limit order matching engine implemented in Java.
Implements strict price-time priority matching, persists executed
trades to an embedded H2 database, and maintains an auditable text
log of all operations. No GUI required.

## Highlights

- Strict price-time priority matching with TreeMap + LinkedList
- Thread-local order books with ReentrantReadWriteLock
- Fixed thread pool for concurrent order processing
- Embedded H2 database (no server required)
- Character-based audit logging
- REPL, batch CSV import, and simulation modes included

## Requirements

- JDK 17+
- Maven 3.8+

## Project Structure

```
order-matching-engine/
├── pom.xml
├── README.md
├── sample_orders.csv
└── src/main/java/com/engine/
    ├── Main.java
    ├── model/
    │   ├── Order.java
    │   ├── Trade.java
    │   ├── Side.java
    │   └── OrderType.java
    ├── core/
    │   ├── OrderBook.java
    │   └── MatchingEngine.java
    ├── persistence/
    │   ├── DatabaseManager.java
    │   └── AuditLogger.java
    └── simulation/
        └── MarketSimulator.java
```

## Building

From the project root:

```
mvn clean package
```

This will produce an executable JAR in

```
target/order-matching-engine.jar
```

The H2 JDBC driver is included in the package via Maven Shade plugin.

## Usage

### 1. Interactive mode

```
java -jar target/order-matching-engine.jar
```

You will be presented with a `>` prompt. Example session:

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
BIDS           | ASKS
------------------------------------
189.50 x40    |

> stats
Orders processed : 2
Trades executed  : 1
Trades in DB   : 1

> exit
Shutting down...
```

Type `help` at the `>` prompt to see available commands.

### 2. Batch mode

```
java -jar target/order-matching-engine.jar --batch sample_orders.csv
```

The CSV file should have the following format:

```
symbol,side,type,price,quantity,clientId
```

Leave price column empty for MARKET orders. See `sample_orders.csv` for reference.

### 3. Simulation mode

```
java -jar target/order-matching-engine.jar --simulate --threads 8 --orders 5000
```

This will start 8 worker threads that send 5,000 random orders each (total of 40,000)
and print throughput statistics and top-of-book for each traded symbol.
This is the same mode used to produce the results in `Project_Report.pdf`

## Logs and Persistence

- Database: `./data/matching_engine.mv.db`
- Audit log: `./logs/audit.log`

All required directories are created automatically on first run.

## Design Details

See `Project_Report.pdf` for implementation details and rationale behind:

- Order book data structure selection (TreeMap + LinkedList)
- Locking strategy
- Concurrency benchmarks (1/2/4/8 worker threads)
- Trade persistence mechanism
- Audit logging implementation
- REPL interface design
