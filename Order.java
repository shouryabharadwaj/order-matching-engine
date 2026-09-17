package com.engine.model;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single resting or incoming order.
 *
 * Note on mutability: quantity is intentionally mutable because partial
 * fills happen constantly during matching - re-creating an immutable Order
 * on every fill would mean rewriting the TreeMap/LinkedList bucket it sits
 * in, which defeats the point of using a LinkedList for O(1) removal at
 * the front. Everything else about an order (price, side, symbol, id) is
 * fixed for its lifetime.
 */
public class Order {

    private static final AtomicLong SEQUENCE = new AtomicLong(1);

    private final long orderId;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final BigDecimal price;      // null / ignored for MARKET orders
    private long quantity;               // remaining, unfilled quantity
    private final long originalQuantity;
    private final long timestampNanos;   // used to break ties at the same price
    private final String clientId;

    public Order(String symbol, Side side, OrderType type, BigDecimal price,
                 long quantity, String clientId) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Order quantity must be positive, got " + quantity);
        }
        if (type == OrderType.LIMIT && (price == null || price.signum() <= 0)) {
            throw new IllegalArgumentException("Limit orders require a positive price");
        }

        this.orderId = SEQUENCE.getAndIncrement();
        this.symbol = symbol.toUpperCase();
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.originalQuantity = quantity;
        this.timestampNanos = System.nanoTime();
        this.clientId = clientId == null ? "ANON" : clientId;
    }

    public long getOrderId() { return orderId; }
    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public OrderType getType() { return type; }
    public BigDecimal getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public long getOriginalQuantity() { return originalQuantity; }
    public long getTimestampNanos() { return timestampNanos; }
    public String getClientId() { return clientId; }

    public boolean isFullyFilled() {
        return quantity <= 0;
    }

    /**
     * Reduces the remaining quantity by the amount just matched.
     * Called exclusively from inside the write-locked section of
     * OrderBook, so it does not need its own synchronization.
     */
    public void reduceQuantity(long filledAmount) {
        if (filledAmount > quantity) {
            throw new IllegalStateException(
                "Attempted to fill " + filledAmount + " against order " + orderId +
                " which only has " + quantity + " remaining");
        }
        this.quantity -= filledAmount;
    }

    @Override
    public String toString() {
        return String.format(
            "Order#%d[%s %s %s qty=%d/%d price=%s client=%s]",
            orderId, symbol, side, type, quantity, originalQuantity,
            type == OrderType.MARKET ? "MKT" : price, clientId);
    }
}
