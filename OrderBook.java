package com.engine.core;

import com.engine.model.Order;
import com.engine.model.OrderType;
import com.engine.model.Side;
import com.engine.model.Trade;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A single-symbol limit order book, matched under strict price-time
 * priority ("FIFO at each price level").
 *
 * Data structure choices, and why:
 *
 *  - bids/asks are TreeMap<BigDecimal, LinkedList<Order>>. A TreeMap keeps
 *    price levels sorted for us automatically (O(log n) insert/remove of a
 *    level, O(1) access to the best level via firstKey/lastKey), so we never
 *    have to re-sort anything by hand when a new price level appears or the
 *    last order at a level disappears. Bids are sorted highest price first
 *    (Comparator.reverseOrder) because the highest bid is the most
 *    aggressive/best buy price; asks use natural ordering since the lowest
 *    ask is the best sell price.
 *
 *  - Each price level is a LinkedList<Order>, not an ArrayList. Within a
 *    level, orders are strictly FIFO: new orders are appended to the tail,
 *    and the matching loop always consumes from the head. LinkedList gives
 *    O(1) for both of those operations, whereas an ArrayList would need an
 *    O(n) shift every time the head element is removed.
 *
 * A single ReentrantReadWriteLock guards both maps for this symbol. Reads
 * (book snapshots, best-bid/ask queries) take the read lock and can run
 * concurrently with each other; anything that mutates the book (placing or
 * matching an order) takes the write lock, which is exclusive. Locking is
 * per-symbol rather than global, so two threads trading AAPL and TSLA never
 * block each other - see MatchingEngine for how that's wired up.
 */
public class OrderBook {

    private final String symbol;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Highest bid first
    private final TreeMap<BigDecimal, LinkedList<Order>> bids =
        new TreeMap<>(Comparator.reverseOrder());
    // Lowest ask first
    private final TreeMap<BigDecimal, LinkedList<Order>> asks = new TreeMap<>();

    // Lets us cancel/inspect an order by id in O(1) without scanning price levels
    private final Map<Long, Order> ordersById = new ConcurrentHashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * Submits an order to this book. Returns whatever trades were generated
     * immediately; if the order (or the remainder of it) doesn't fully
     * cross the book and it's a LIMIT order, the leftover quantity is
     * added as a new resting order. MARKET orders never rest - leftover
     * quantity on a market order with no more liquidity to hit is dropped.
     */
    public List<Trade> submit(Order incoming) {
        lock.writeLock().lock();
        try {
            List<Trade> trades = match(incoming);

            if (incoming.getType() == OrderType.LIMIT && !incoming.isFullyFilled()) {
                addToBook(incoming);
            }
            return trades;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Attempts to cancel a resting order. Returns true if it was found and
     * removed, false if it had already been filled or never existed.
     */
    public boolean cancel(long orderId) {
        lock.writeLock().lock();
        try {
            Order order = ordersById.remove(orderId);
            if (order == null) {
                return false;
            }
            TreeMap<BigDecimal, LinkedList<Order>> side =
                order.getSide() == Side.BUY ? bids : asks;
            LinkedList<Order> level = side.get(order.getPrice());
            if (level == null) {
                return false;
            }
            level.remove(order);
            if (level.isEmpty()) {
                side.remove(order.getPrice());
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public BigDecimal bestBid() {
        lock.readLock().lock();
        try {
            return bids.isEmpty() ? null : bids.firstKey();
        } finally {
            lock.readLock().unlock();
        }
    }

    public BigDecimal bestAsk() {
        lock.readLock().lock();
        try {
            return asks.isEmpty() ? null : asks.firstKey();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long depthAt(Side side, BigDecimal price) {
        lock.readLock().lock();
        try {
            LinkedList<Order> level = (side == Side.BUY ? bids : asks).get(price);
            if (level == null) return 0;
            long total = 0;
            for (Order o : level) total += o.getQuantity();
            return total;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Human-readable top-of-book snapshot, mostly used by the CLI "book" command. */
    public String renderSnapshot(int depth) {
        lock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Order Book: ").append(symbol).append('\n');
            sb.append(String.format("%-15s | %-15s%n", "BIDS", "ASKS"));
            sb.append("-".repeat(35)).append('\n');

            Iterator<Map.Entry<BigDecimal, LinkedList<Order>>> bidIt = bids.entrySet().iterator();
            Iterator<Map.Entry<BigDecimal, LinkedList<Order>>> askIt = asks.entrySet().iterator();

            for (int i = 0; i < depth && (bidIt.hasNext() || askIt.hasNext()); i++) {
                String bidStr = "";
                String askStr = "";
                if (bidIt.hasNext()) {
                    Map.Entry<BigDecimal, LinkedList<Order>> e = bidIt.next();
                    bidStr = e.getKey() + " x" + sumQty(e.getValue());
                }
                if (askIt.hasNext()) {
                    Map.Entry<BigDecimal, LinkedList<Order>> e = askIt.next();
                    askStr = e.getKey() + " x" + sumQty(e.getValue());
                }
                sb.append(String.format("%-15s | %-15s%n", bidStr, askStr));
            }
            return sb.toString();
        } finally {
            lock.readLock().unlock();
        }
    }

    private long sumQty(LinkedList<Order> level) {
        long total = 0;
        for (Order o : level) total += o.getQuantity();
        return total;
    }

    // --- internal matching logic, always called under the write lock ---

    private List<Trade> match(Order incoming) {
        List<Trade> trades = new LinkedList<>();
        TreeMap<BigDecimal, LinkedList<Order>> opposingBook =
            incoming.getSide() == Side.BUY ? asks : bids;

        while (!incoming.isFullyFilled() && !opposingBook.isEmpty()) {
            Map.Entry<BigDecimal, LinkedList<Order>> bestLevel = opposingBook.firstEntry();
            BigDecimal levelPrice = bestLevel.getKey();

            if (!crosses(incoming, levelPrice)) {
                break; // best available price no longer satisfies the incoming order's limit
            }

            LinkedList<Order> queue = bestLevel.getValue();
            while (!incoming.isFullyFilled() && !queue.isEmpty()) {
                Order resting = queue.peekFirst();
                long fillQty = Math.min(incoming.getQuantity(), resting.getQuantity());

                incoming.reduceQuantity(fillQty);
                resting.reduceQuantity(fillQty);

                long buyId = incoming.getSide() == Side.BUY ? incoming.getOrderId() : resting.getOrderId();
                long sellId = incoming.getSide() == Side.SELL ? incoming.getOrderId() : resting.getOrderId();
                trades.add(new Trade(symbol, buyId, sellId, levelPrice, fillQty));

                if (resting.isFullyFilled()) {
                    queue.pollFirst();
                    ordersById.remove(resting.getOrderId());
                }
            }
            if (queue.isEmpty()) {
                opposingBook.remove(levelPrice);
            }
        }
        return trades;
    }

    private boolean crosses(Order incoming, BigDecimal opposingPrice) {
        if (incoming.getType() == OrderType.MARKET) {
            return true; // market orders take whatever price is available
        }
        return incoming.getSide() == Side.BUY
            ? incoming.getPrice().compareTo(opposingPrice) >= 0
            : incoming.getPrice().compareTo(opposingPrice) <= 0;
    }

    private void addToBook(Order order) {
        TreeMap<BigDecimal, LinkedList<Order>> book = order.getSide() == Side.BUY ? bids : asks;
        book.computeIfAbsent(order.getPrice(), p -> new LinkedList<>()).addLast(order);
        ordersById.put(order.getOrderId(), order);
    }
}
