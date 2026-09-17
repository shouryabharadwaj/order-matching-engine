package com.engine.core;

import com.engine.model.Order;
import com.engine.model.Trade;
import com.engine.persistence.AuditLogger;
import com.engine.persistence.DatabaseManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Top-level entry point for order submission. Owns one OrderBook per
 * ticker symbol and fans work out to a fixed thread pool so multiple
 * clients (or the MarketSimulator) can submit orders concurrently.
 *
 * Concurrency model:
 *  Each symbol gets its own OrderBook, and each OrderBook has its own
 *  ReentrantReadWriteLock. That means an order for AAPL and an order for
 *  TSLA can be matched on two different threads at the same instant with
 *  zero contention between them - there's no single global lock on "the
 *  book" the way a naive implementation might have. Contention only
 *  happens when two threads are hammering the *same* symbol, which is
 *  exactly when contention is actually necessary for correctness.
 *
 *  The books map itself is a ConcurrentHashMap so that getOrCreateBook()
 *  is safe to call from many threads without needing a lock of its own;
 *  computeIfAbsent gives us atomic "create if missing" semantics.
 */
public class MatchingEngine {

    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final ExecutorService workerPool;
    private final DatabaseManager db;
    private final AuditLogger auditLogger;

    private final AtomicLong ordersProcessed = new AtomicLong(0);
    private final AtomicLong tradesExecuted = new AtomicLong(0);

    public MatchingEngine(DatabaseManager db, AuditLogger auditLogger, int workerThreads) {
        this.db = db;
        this.auditLogger = auditLogger;
        this.workerPool = Executors.newFixedThreadPool(workerThreads, r -> {
            Thread t = new Thread(r, "matching-worker");
            t.setDaemon(true);
            return t;
        });
    }

    private OrderBook getOrCreateBook(String symbol) {
        return books.computeIfAbsent(symbol.toUpperCase(), OrderBook::new);
    }

    /**
     * Submits synchronously on the calling thread. Useful for the
     * interactive CLI where we want the trade result back immediately.
     */
    public List<Trade> submitOrder(Order order) {
        OrderBook book = getOrCreateBook(order.getSymbol());
        auditLogger.logOrderReceived(order);

        List<Trade> trades = book.submit(order);
        ordersProcessed.incrementAndGet();

        if (!trades.isEmpty()) {
            tradesExecuted.addAndGet(trades.size());
            db.persistTradesBatch(trades);
            for (Trade t : trades) {
                auditLogger.logTrade(t);
            }
        }
        return trades;
    }

    /**
     * Submits on the shared worker pool and returns a Future so the caller
     * (typically MarketSimulator, or a batch file loader) can fire off many
     * orders without blocking on each one individually.
     */
    public Future<List<Trade>> submitOrderAsync(Order order) {
        return workerPool.submit(() -> submitOrder(order));
    }

    public boolean cancelOrder(String symbol, long orderId) {
        return getOrCreateBook(symbol).cancel(orderId);
    }

    public String renderBook(String symbol, int depth) {
        return getOrCreateBook(symbol).renderSnapshot(depth);
    }

    public long getOrdersProcessed() { return ordersProcessed.get(); }
    public long getTradesExecuted() { return tradesExecuted.get(); }

    public void shutdown() {
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
