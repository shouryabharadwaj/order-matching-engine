package com.engine.persistence;

import com.engine.model.Order;
import com.engine.model.Trade;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * A plain-text, append-only audit trail. This is intentionally separate
 * from the H2 trade table: the database holds structured, queryable trade
 * records, while this log is the kind of flat, human-readable file you'd
 * hand to a compliance reviewer or grep through at 2am when something
 * looks off.
 *
 * Uses a BufferedWriter over a FileWriter (a character stream, not a raw
 * byte stream) so multi-byte characters in client IDs are handled
 * correctly and writes are batched in memory rather than hitting disk on
 * every single call. The writer is flushed after every log line, though -
 * an audit log that loses its last few entries because the process died
 * before a flush isn't much of an audit log.
 */
public class AuditLogger implements AutoCloseable {

    private static final DateTimeFormatter TS_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final BufferedWriter writer;
    private final Object writeLock = new Object();

    public AuditLogger(String logFilePath) throws IOException {
        Path path = Path.of(logFilePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        // append=true so restarting the CLI doesn't wipe out prior history
        this.writer = new BufferedWriter(new FileWriter(logFilePath, true));
        writeLine("=== Audit session started ===");
    }

    public void logOrderReceived(Order order) {
        writeLine(String.format(
            "ORDER_RECEIVED id=%d symbol=%s side=%s type=%s price=%s qty=%d client=%s",
            order.getOrderId(), order.getSymbol(), order.getSide(), order.getType(),
            order.getPrice() == null ? "MKT" : order.getPrice(),
            order.getOriginalQuantity(), order.getClientId()));
    }

    public void logTrade(Trade trade) {
        writeLine(String.format(
            "TRADE_EXECUTED id=%d symbol=%s buyOrder=%d sellOrder=%d qty=%d price=%s notional=%s",
            trade.getTradeId(), trade.getSymbol(), trade.getBuyOrderId(), trade.getSellOrderId(),
            trade.getQuantity(), trade.getExecutionPrice(), trade.notionalValue()));
    }

    public void logCancel(long orderId, boolean success) {
        writeLine(String.format("ORDER_CANCEL id=%d success=%b", orderId, success));
    }

    public void logSystemEvent(String message) {
        writeLine("SYSTEM " + message);
    }

    private void writeLine(String content) {
        String timestamped = "[" + java.time.LocalDateTime.now().format(TS_FORMAT) + "] " + content;
        synchronized (writeLock) {
            try {
                writer.write(timestamped);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // Deliberately not rethrowing - a logging failure shouldn't take
                // down order matching, but it does need to be visible somewhere.
                System.err.println("[AuditLogger] Failed to write audit entry: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() throws IOException {
        writeLine("=== Audit session ended ===");
        writer.close();
    }
}
