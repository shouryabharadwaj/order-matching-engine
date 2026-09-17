package com.engine;

import com.engine.core.MatchingEngine;
import com.engine.model.Order;
import com.engine.model.OrderType;
import com.engine.model.Side;
import com.engine.model.Trade;
import com.engine.persistence.AuditLogger;
import com.engine.persistence.DatabaseManager;
import com.engine.simulation.MarketSimulator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Scanner;

/**
 * CLI entry point. Everything runs in a terminal - no GUI toolkit is
 * imported or required anywhere in this project.
 *
 * Supported invocations:
 *   java -jar order-matching-engine.jar                      -> interactive REPL
 *   java -jar order-matching-engine.jar --simulate --threads 8 --orders 5000
 *   java -jar order-matching-engine.jar --batch sample_orders.csv
 */
public class Main {

    public static void main(String[] args) {
        CliArgs cliArgs = CliArgs.parse(args);

        System.out.println("==================================================");
        System.out.println(" CLI Stock Market Order Matching Engine");
        System.out.println(" Strict Price-Time Priority | H2 Persistence");
        System.out.println("==================================================");

        try (DatabaseManager db = new DatabaseManager();
             AuditLogger auditLogger = new AuditLogger("logs/audit.log")) {

            MatchingEngine engine = new MatchingEngine(db, auditLogger, cliArgs.threads);
            auditLogger.logSystemEvent("Engine started with " + cliArgs.threads + " worker threads");

            if (cliArgs.simulate) {
                runSimulation(engine, cliArgs);
            } else if (cliArgs.batchFile != null) {
                runBatchIngest(engine, cliArgs.batchFile);
            } else {
                runInteractive(engine, db);
            }

            engine.shutdown();
            System.out.println("\nFinal trade count in database: " + db.countTrades());

        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // Simulation mode
    // ------------------------------------------------------------------

    private static void runSimulation(MatchingEngine engine, CliArgs args) throws InterruptedException {
        String[] symbols = {"AAPL", "TSLA", "MSFT", "GOOG", "AMZN"};
        MarketSimulator simulator = new MarketSimulator(engine, symbols);

        System.out.printf("Starting simulation: %d threads x %d orders each (%d total)%n",
            args.threads, args.ordersPerThread, args.threads * args.ordersPerThread);

        long elapsedMs = simulator.run(args.threads, args.ordersPerThread);
        long totalOrders = simulator.getOrdersSubmitted();
        double throughput = totalOrders / Math.max(elapsedMs / 1000.0, 0.001);

        System.out.println("\n--- Simulation Results ---");
        System.out.printf("Orders submitted : %d%n", totalOrders);
        System.out.printf("Trades executed  : %d%n", engine.getTradesExecuted());
        System.out.printf("Elapsed time     : %d ms%n", elapsedMs);
        System.out.printf("Throughput       : %.2f orders/sec%n", throughput);

        for (String symbol : symbols) {
            System.out.println();
            System.out.print(engine.renderBook(symbol, 5));
        }
    }

    // ------------------------------------------------------------------
    // Batch CSV ingest
    // ------------------------------------------------------------------

    private static void runBatchIngest(MatchingEngine engine, String filePath) throws IOException {
        System.out.println("Loading batch orders from " + filePath);
        int lineNumber = 0;
        int loaded = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                if (!headerSkipped) {
                    headerSkipped = true;
                    if (line.toLowerCase().startsWith("symbol")) {
                        continue; // skip header row
                    }
                }

                try {
                    Order order = parseCsvLine(line);
                    List<Trade> trades = engine.submitOrder(order);
                    loaded++;
                    if (!trades.isEmpty()) {
                        System.out.printf("  line %d -> %d trade(s) generated%n", lineNumber, trades.size());
                    }
                } catch (Exception e) {
                    System.err.printf("  Skipping malformed line %d: %s (%s)%n", lineNumber, line, e.getMessage());
                }
            }
        }
        System.out.println("Batch ingest complete. Orders loaded: " + loaded);
    }

    private static Order parseCsvLine(String line) {
        // Expected columns: symbol,side,type,price,quantity,clientId
        String[] cols = line.split(",");
        String symbol = cols[0].trim();
        Side side = Side.valueOf(cols[1].trim().toUpperCase());
        OrderType type = OrderType.valueOf(cols[2].trim().toUpperCase());
        String priceStr = cols[3].trim();
        BigDecimal price = (type == OrderType.MARKET || priceStr.isEmpty())
            ? null : new BigDecimal(priceStr);
        long quantity = Long.parseLong(cols[4].trim());
        String clientId = cols.length > 5 ? cols[5].trim() : "BATCH";

        return new Order(symbol, side, type, price, quantity, clientId);
    }

    // ------------------------------------------------------------------
    // Interactive REPL
    // ------------------------------------------------------------------

    private static void runInteractive(MatchingEngine engine, DatabaseManager db) {
        System.out.println("\nInteractive mode. Type 'help' for a list of commands.");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\n> ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] tokens = line.split("\\s+");
            String command = tokens[0].toLowerCase();

            try {
                switch (command) {
                    case "help" -> printHelp();
                    case "exit", "quit" -> {
                        System.out.println("Shutting down...");
                        return;
                    }
                    case "order" -> handleOrderCommand(engine, tokens);
                    case "book" -> handleBookCommand(engine, tokens);
                    case "stats" -> handleStatsCommand(engine, db);
                    default -> System.out.println("Unknown command '" + command + "'. Type 'help'.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    private static void printHelp() {
        System.out.println("""
            Commands:
              order <symbol> <buy|sell> <limit|market> <price|-> <qty> [clientId]
                  e.g. order AAPL buy limit 189.50 100 alice
                  e.g. order AAPL sell market - 50 bob
              book <symbol> [depth]
                  e.g. book AAPL 10
              stats
                  Prints engine-wide order/trade counters and DB row count.
              exit
                  Quits the CLI.
            """);
    }

    private static void handleOrderCommand(MatchingEngine engine, String[] tokens) {
        if (tokens.length < 6) {
            System.out.println("Usage: order <symbol> <buy|sell> <limit|market> <price|-> <qty> [clientId]");
            return;
        }
        String symbol = tokens[1];
        Side side = Side.valueOf(tokens[2].toUpperCase());
        OrderType type = OrderType.valueOf(tokens[3].toUpperCase());
        BigDecimal price = tokens[4].equals("-") ? null : new BigDecimal(tokens[4]);
        long qty = Long.parseLong(tokens[5]);
        String clientId = tokens.length > 6 ? tokens[6] : "CLI";

        Order order = new Order(symbol, side, type, price, qty, clientId);
        List<Trade> trades = engine.submitOrder(order);

        System.out.println("Submitted: " + order);
        if (trades.isEmpty()) {
            System.out.println("No immediate match; order resting on book (or fully consumed if market).");
        } else {
            System.out.println("Matched " + trades.size() + " trade(s):");
            trades.forEach(t -> System.out.println("  " + t));
        }
    }

    private static void handleBookCommand(MatchingEngine engine, String[] tokens) {
        if (tokens.length < 2) {
            System.out.println("Usage: book <symbol> [depth]");
            return;
        }
        String symbol = tokens[1];
        int depth = tokens.length > 2 ? Integer.parseInt(tokens[2]) : 5;
        System.out.print(engine.renderBook(symbol, depth));
    }

    private static void handleStatsCommand(MatchingEngine engine, DatabaseManager db) {
        System.out.println("Orders processed : " + engine.getOrdersProcessed());
        System.out.println("Trades executed  : " + engine.getTradesExecuted());
        System.out.println("Trades in DB     : " + db.countTrades());
    }

    // ------------------------------------------------------------------
    // Argument parsing
    // ------------------------------------------------------------------

    private static class CliArgs {
        boolean simulate = false;
        int threads = 4;
        int ordersPerThread = 1000;
        String batchFile = null;

        static CliArgs parse(String[] args) {
            CliArgs result = new CliArgs();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--simulate" -> result.simulate = true;
                    case "--threads" -> result.threads = Integer.parseInt(args[++i]);
                    case "--orders" -> result.ordersPerThread = Integer.parseInt(args[++i]);
                    case "--batch" -> result.batchFile = args[++i];
                    default -> System.out.println("Ignoring unrecognized argument: " + args[i]);
                }
            }
            return result;
        }
    }
}
