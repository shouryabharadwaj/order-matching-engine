package com.engine.model;

/**
 * LIMIT orders rest on the book if they cannot be filled immediately.
 * MARKET orders never rest - they sweep whatever liquidity is available
 * at the best price(s) and whatever remains unfilled is simply cancelled,
 * which mirrors how most real exchanges treat marketable orders.
 */
public enum OrderType {
    LIMIT,
    MARKET
}
