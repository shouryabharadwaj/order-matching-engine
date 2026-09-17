package com.engine.persistence;

import com.engine.model.Trade;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * Wraps an embedded, file-backed H2 database. "Embedded" here means H2 is
 * running in-process inside the JVM - there's no separate database server
 * to install, start, or configure, which is exactly what you want for a
 * CLI tool someone can clone and run in under a minute.
 *
 * Trades are inserted using JDBC batching rather than one INSERT per
 * trade. Under heavy order flow a single aggressive market order can
 * generate dozens of trades in one call; batching those into a single
 * round trip to the database noticeably cuts down on JDBC overhead
 * compared to committing each row individually.
 */
public class DatabaseManager implements AutoCloseable {

    private static final String JDBC_URL = "jdbc:h2:file:./data/matching_engine;AUTO_SERVER=TRUE";

    private final Connection connection;

    public DatabaseManager() throws SQLException {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("H2 driver not found on classpath", e);
        }
        this.connection = DriverManager.getConnection(JDBC_URL, "sa", "");
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS trades (
                    trade_id BIGINT PRIMARY KEY,
                    symbol VARCHAR(16) NOT NULL,
                    buy_order_id BIGINT NOT NULL,
                    sell_order_id BIGINT NOT NULL,
                    execution_price DECIMAL(18,4) NOT NULL,
                    quantity BIGINT NOT NULL,
                    notional DECIMAL(22,4) NOT NULL,
                    executed_at TIMESTAMP NOT NULL
                )
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_trades_symbol ON trades(symbol)
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS orders_ledger (
                    order_id BIGINT PRIMARY KEY,
                    symbol VARCHAR(16) NOT NULL,
                    side VARCHAR(4) NOT NULL,
                    order_type VARCHAR(6) NOT NULL,
                    price DECIMAL(18,4),
                    original_quantity BIGINT NOT NULL,
                    client_id VARCHAR(64),
                    received_at TIMESTAMP NOT NULL
                )
            """);
        }
    }

    /**
     * Inserts every trade from a single matching pass in one JDBC batch.
     * Commits once at the end rather than per-row.
     */
    public synchronized void persistTradesBatch(List<Trade> trades) {
        if (trades.isEmpty()) return;

        String sql = """
            INSERT INTO trades
                (trade_id, symbol, buy_order_id, sell_order_id, execution_price, quantity, notional, executed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (Trade t : trades) {
                    ps.setLong(1, t.getTradeId());
                    ps.setString(2, t.getSymbol());
                    ps.setLong(3, t.getBuyOrderId());
                    ps.setLong(4, t.getSellOrderId());
                    ps.setBigDecimal(5, t.getExecutionPrice());
                    ps.setLong(6, t.getQuantity());
                    ps.setBigDecimal(7, t.notionalValue());
                    ps.setTimestamp(8, Timestamp.from(t.getExecutedAt()));
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Failed to persist trade batch: " + e.getMessage());
        }
    }

    public synchronized void recordOrderReceived(long orderId, String symbol, String side,
                                                  String type, String price, long qty, String clientId) {
        String sql = """
            INSERT INTO orders_ledger
                (order_id, symbol, side, order_type, price, original_quantity, client_id, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setString(2, symbol);
            ps.setString(3, side);
            ps.setString(4, type);
            if (price == null) {
                ps.setNull(5, java.sql.Types.DECIMAL);
            } else {
                ps.setBigDecimal(5, new java.math.BigDecimal(price));
            }
            ps.setLong(6, qty);
            ps.setString(7, clientId);
            ps.setTimestamp(8, Timestamp.from(java.time.Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Failed to record order: " + e.getMessage());
        }
    }

    public synchronized long countTrades() {
        try (Statement stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM trades")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] Error closing connection: " + e.getMessage());
        }
    }
}
