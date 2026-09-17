package com.engine.model;

/**
 * Which direction of the book an order sits on.
 * Kept deliberately tiny - the matching logic in OrderBook is where the
 * real behavioural difference between BUY and SELL actually lives.
 */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
