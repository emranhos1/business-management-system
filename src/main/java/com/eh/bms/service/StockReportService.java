package com.eh.bms.service;

import com.eh.bms.model.Product;
import com.eh.bms.util.TransactionHelper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Read-only stock reports: low stock list (9.6) and stock value (9.7).
 * One query for the products (category and units fetched) plus the stock ledger's stock / average cost maps.
 */
public class StockReportService {

    /** Money is shown with 2 decimals; each row's value is rounded so the total matches the rows. */
    private static final int MONEY_DECIMALS = 2;

    /** A product below its minimum stock; shortfall = minimum - current stock. */
    public record LowStockRow(Product product, BigDecimal stock, BigDecimal minStock, BigDecimal shortfall) {
    }

    /** A product in stock with its value = stock x average cost. */
    public record ValueRow(Product product, BigDecimal stock, BigDecimal averageCost, BigDecimal value) {
    }

    /** The rows and their grand total value. */
    public record Valuation(List<ValueRow> rows, BigDecimal total) {
    }

    private final StockLedgerService ledgerService = new StockLedgerService();

    /**
     * Products with a minimum stock above 0 whose current stock is below it, biggest shortfall first
     * (then by name). Min stock 0 or empty = no alert for that product.
     */
    public List<LowStockRow> lowStock() {
        List<Product> products = products("where p.minStockAlert > 0", null);
        Map<Long, BigDecimal> stocks = ledgerService.currentStocks(ids(products));
        List<LowStockRow> rows = new ArrayList<>();
        for (Product p : products) {
            BigDecimal min = p.getMinStockAlert();
            BigDecimal stock = stocks.getOrDefault(p.getId(), BigDecimal.ZERO);
            if (min != null && min.signum() > 0 && stock.compareTo(min) < 0) {
                rows.add(new LowStockRow(p, stock, min, min.subtract(stock)));
            }
        }
        rows.sort(Comparator.comparing(LowStockRow::shortfall).reversed());
        return rows;
    }

    /**
     * Value of every product with stock above 0 (stock x weighted average cost), sorted by name.
     * {@code categoryId} null = all categories.
     */
    public Valuation valuation(Long categoryId) {
        List<Product> products = categoryId == null
                ? products("", null)
                : products("where p.category.id = :categoryId", categoryId);
        List<Long> ids = ids(products);
        Map<Long, BigDecimal> stocks = ledgerService.currentStocks(ids);
        Map<Long, BigDecimal> costs = ledgerService.averageCosts(ids);
        List<ValueRow> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Product p : products) {
            BigDecimal stock = stocks.getOrDefault(p.getId(), BigDecimal.ZERO);
            if (stock.signum() <= 0) {
                continue;
            }
            BigDecimal cost = costs.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal value = stock.multiply(cost).setScale(MONEY_DECIMALS, RoundingMode.HALF_UP);
            rows.add(new ValueRow(p, stock, cost, value));
            total = total.add(value);
        }
        return new Valuation(rows, total);
    }

    /** Products sorted by name, with category and units loaded (the tables show them after the session closes). */
    private static List<Product> products(String where, Long categoryId) {
        String hql = "from Product p join fetch p.category left join fetch p.unit left join fetch p.secondaryUnit "
                + where + " order by p.name";
        return TransactionHelper.inTransaction(session -> {
            var query = session.createSelectionQuery(hql, Product.class);
            if (categoryId != null) {
                query.setParameter("categoryId", categoryId);
            }
            return query.getResultList();
        });
    }

    private static List<Long> ids(List<Product> products) {
        return products.stream().map(Product::getId).toList();
    }
}
