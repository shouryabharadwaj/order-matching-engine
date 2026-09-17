package com.engine.simulation;

import com.engine.core.MatchingEngine;
import com.engine.model.Order;
import com.engine.model.OrderType;
import com.engine.model.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates synthetic order flow across several worker threads to stress
 * test the engine and produce the throughput/latency numbers referenced
 * in the project report. Each worker behaves like an independent, slightly
 * noisy trader: it walks a random price around a moving mid-point rather
 * than firing completely uniform random prices, which produces a book
 * shape that actually resembles a real market instead of pure noise.
 */
public class MarketSimulator {

    private final MatchingEngine engine;
    private final String[] symbols;
    private final AtomicLong ordersSubmitted = new AtomicLong(0);

    public MarketSimulator(MatchingEngine engine, String[] symbols) {
        this.engine = engine;
        this.symbols = symbols;
    }

    /**
     * Runs the simulation with the given number of concurrent worker
     * threads, each submitting ordersPerWorker orders, then blocks until
     * every worker has finished. Returns elapsed wall-clock time in
     * milliseconds so callers can compute throughput.
     */
    public long run(int workerThreads, int ordersPerWorker) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(workerThreads);
        Thread[] workers = new Thread[workerThreads];

        long startTime = System.currentTimeMillis();

        for (int w = 0; w < workerThreads; w++) {
            final int workerIndex = w;
            workers[w] = new Thread(() -> {
                try {
                    simulateWorker(workerIndex, ordersPerWorker);
                } finally {
                    latch.countDown();
                }
            }, "sim-worker-" + w);
            workers[w].start();
        }

        latch.await();
        return System.currentTimeMillis() - startTime;
    }

    private void simulateWorker(int workerIndex, int orderCount) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String symbol = symbols[workerIndex % symbols.length];

        // Each worker anchors around its own moving mid-price so the book
        // doesn't collapse into a single price level immediately.
        double midPrice = 100.0 + random.nextDouble(-5.0, 5.0);

        for (int i = 0; i < orderCount; i++) {
            // small random walk in the mid-price to mimic drifting markets
            midPrice += random.nextDouble(-0.15, 0.15);
            midPrice = Math.max(1.0, midPrice);

            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            boolean isMarketOrder = random.nextInt(10) == 0; // ~10% market orders

            double priceOffset = random.nextDouble(0.01, 0.75);
            double rawPrice = side == Side.BUY ? midPrice - priceOffset : midPrice + priceOffset;
            BigDecimal price = BigDecimal.valueOf(rawPrice).setScale(2, RoundingMode.HALF_UP);

            long qty = random.nextLong(1, 250);

            Order order = new Order(
                symbol,
                side,
                isMarketOrder ? OrderType.MARKET : OrderType.LIMIT,
                isMarketOrder ? null : price,
                qty,
                "SIM-" + workerIndex
            );

            List<?> trades = engine.submitOrder(order);
            ordersSubmitted.incrementAndGet();

            // trades result intentionally unused here beyond counting -
            // the engine already persists and logs them internally
            if (trades.isEmpty()) {
                // resting order, nothing further to do
            }
        }
    }

    public long getOrdersSubmitted() {
        return ordersSubmitted.get();
    }
}
