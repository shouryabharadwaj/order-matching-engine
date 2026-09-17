package com.engine.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The result of two orders crossing. A Trade always executes at the resting
 * (maker) order's price - that's standard price-time priority behaviour and
 * is what keeps existing limit orders from being penalised just because a
 * more aggressive counter-order showed up later.
 */
public class Trade {

    private static final AtomicLong SEQUENCE = new AtomicLong(1);

    private final long tradeId;
    private final String symbol;
    private final long buyOrderId;
    private final long sellOrderId;
    private final BigDecimal executionPrice;
    private final long quantity;
    private final Instant executedAt;

    public Trade(String symbol, long buyOrderId, long sellOrderId,
                 BigDecimal executionPrice, long quantity) {
        this.tradeId = SEQUENCE.getAndIncrement();
        this.symbol = symbol;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.executionPrice = executionPrice;
        this.quantity = quantity;
        this.executedAt = Instant.now();
    }

    public long getTradeId() { return tradeId; }
    public String getSymbol() { return symbol; }
    public long getBuyOrderId() { return buyOrderId; }
    public long getSellOrderId() { return sellOrderId; }
    public BigDecimal getExecutionPrice() { return executionPrice; }
    public long getQuantity() { return quantity; }
    public Instant getExecutedAt() { return executedAt; }

    public BigDecimal notionalValue() {
        return executionPrice.multiply(BigDecimal.valueOf(quantity));
    }

    @Override
    public String toString() {
        return String.format("Trade#%d[%s buy=%d sell=%d qty=%d @ %s]",
            tradeId, symbol, buyOrderId, sellOrderId, quantity, executionPrice);
    }
}
