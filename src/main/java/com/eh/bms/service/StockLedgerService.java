package com.eh.bms.service;

import com.eh.bms.model.Product;
import com.eh.bms.model.StockAdjustmentReason;
import com.eh.bms.model.StockLedger;
import com.eh.bms.model.StockLedgerType;
import com.eh.bms.model.User;
import com.eh.bms.repository.StockLedgerRepository;
import com.eh.bms.repository.StockLedgerRepository.Totals;
import com.eh.bms.util.PageResult;
import com.eh.bms.util.TransactionHelper;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stock ledgers (9.1) and weighted average cost (9.2). Quantities are in the product's main unit.
 * <p>
 * Every module that moves stock writes its entries through {@link #post}, inside its own transaction,
 * and writes its own audit row (post does not, same as {@link AccountLedgerService#post}).
 * <p>
 * Average cost: stock in with a price gives new avg = (old qty x old avg + in qty x in price) / (old qty + in qty);
 * with no stock left this is simply the in price. Stock out keeps the average and is recorded at it; a purchase
 * return goes out at its purchase price instead: avg = (old qty x old avg - out qty x price) / qty left.
 * Each entry stores the stock and average after it (posting order), so the newest entry gives both.
 */
public class StockLedgerService {

    /** One ledger line. One of quantityIn / quantityOut is null. {@code balance} is by date order. */
    public record StockRow(long id, LocalDate date, StockLedgerType type, StockAdjustmentReason reason,
                           BigDecimal quantityIn, BigDecimal quantityOut, BigDecimal unitPrice,
                           BigDecimal balance, Long referenceId, String note) {
    }

    /** Figures shown above a product's ledger. */
    public record Summary(BigDecimal totalIn, BigDecimal totalOut, BigDecimal currentStock, BigDecimal averageCost) {
    }

    public enum Status {
        DONE, INSUFFICIENT_STOCK
    }

    /** {@code available} is the stock before the entry, set for INSUFFICIENT_STOCK; {@code entry} is set for DONE. */
    public record Result(Status status, BigDecimal available, StockLedger entry) {
    }

    /** Quantities are DECIMAL(14,3). */
    private static final int QTY_SCALE = 3;
    /** Cost is kept to 4 decimals so averages do not lose paisa; money shown is still rounded to 2. */
    private static final int COST_SCALE = 4;

    /** Types that only add stock / only take it out. RETURN and ADJUSTMENT may do either. */
    private static final Set<StockLedgerType> IN_ONLY =
            EnumSet.of(StockLedgerType.OPENING, StockLedgerType.PURCHASE, StockLedgerType.SALE_RETURN);
    private static final Set<StockLedgerType> OUT_ONLY =
            EnumSet.of(StockLedgerType.SALE, StockLedgerType.PURCHASE_RETURN);

    private final StockLedgerRepository repository = new StockLedgerRepository();

    public BigDecimal currentStock(long productId) {
        return TransactionHelper.inTransaction(session -> currentStock(session, productId));
    }

    public BigDecimal currentStock(Session session, long productId) {
        return repository.totals(session, productId).net();
    }

    /** Most that can go out on {@code date}: the lowest stock from the end of that day on (main unit). */
    public BigDecimal availableOn(Session session, long productId, LocalDate date) {
        BigDecimal running = repository.netUpTo(session, productId, date);
        BigDecimal lowest = running;
        for (BigDecimal qty : repository.quantitiesAfter(session, productId, date)) {
            running = running.add(qty);
            lowest = lowest.min(running);
        }
        return lowest;
    }

    /** Current stock of each product, in one query; a product with no entries gets 0. */
    public Map<Long, BigDecimal> currentStocks(Collection<Long> productIds) {
        Map<Long, BigDecimal> net = TransactionHelper.inTransaction(session ->
                repository.netByProduct(session, productIds));
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Long id : productIds) {
            result.put(id, net.getOrDefault(id, BigDecimal.ZERO));
        }
        return result;
    }

    /** Weighted average cost of one main unit; 0 for a product with no entries. */
    public BigDecimal averageCost(long productId) {
        return TransactionHelper.inTransaction(session -> averageCost(session, productId));
    }

    public BigDecimal averageCost(Session session, long productId) {
        return repository.latest(session, productId).map(StockLedger::getAvgCostAfter).orElse(BigDecimal.ZERO);
    }

    /** Average cost of each product, in one query; a product with no entries gets 0. */
    public Map<Long, BigDecimal> averageCosts(Collection<Long> productIds) {
        Map<Long, StockLedger> latest = TransactionHelper.inTransaction(session ->
                repository.latestByProduct(session, productIds));
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Long id : productIds) {
            StockLedger s = latest.get(id);
            result.put(id, s == null ? BigDecimal.ZERO : s.getAvgCostAfter());
        }
        return result;
    }

    public Summary summary(long productId) {
        return TransactionHelper.inTransaction(session -> {
            Totals totals = repository.totals(session, productId);
            return new Summary(totals.in(), totals.out(), totals.net(), averageCost(session, productId));
        });
    }

    /**
     * One page of the ledger, newest first. The balance column is worked out by date order
     * (current stock minus everything after the row), so back-dated entries still show right.
     */
    public PageResult<StockRow> page(long productId, int offset, int limit) {
        return TransactionHelper.inTransaction(session -> {
            List<StockLedger> entries = repository.page(session, productId, offset, limit);
            long total = repository.count(session, productId);
            if (entries.isEmpty()) {
                return new PageResult<>(List.of(), total);
            }
            StockLedger first = entries.get(0);
            BigDecimal balance = currentStock(session, productId)
                    .subtract(repository.netAfter(session, productId, first.getDate(), first.getId()));

            List<StockRow> rows = new ArrayList<>(entries.size());
            for (StockLedger s : entries) {
                BigDecimal qty = s.getQuantity();
                rows.add(new StockRow(s.getId(), s.getDate(), s.getType(), s.getReason(),
                        qty.signum() > 0 ? qty : null, qty.signum() < 0 ? qty.negate() : null,
                        s.getUnitPrice(), balance, s.getReferenceId(), s.getNote()));
                balance = balance.subtract(qty);
            }
            return new PageResult<>(rows, total);
        });
    }

    /**
     * Writes one ledger entry in the caller's transaction. The product row is locked first, so two entries
     * posted at the same time cannot both read the same old stock.
     *
     * @param signedQuantity + stock in, - stock out, in the main unit; not zero
     * @param unitPrice      cost of one main unit for stock in; null = current average cost (average unchanged).
     *                       Stock out is recorded at the current average cost, except a purchase return with a
     *                       price: it leaves at that price and the average of the stock left is worked out again.
     * @return INSUFFICIENT_STOCK (nothing written) when stock out is more than the current stock
     */
    public Result post(Session session, long productId, LocalDate date, StockLedgerType type,
                       BigDecimal signedQuantity, BigDecimal unitPrice, StockAdjustmentReason reason,
                       Long referenceId, String note) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(type, "type");
        if (signedQuantity == null) {
            throw new IllegalArgumentException("Quantity is required");
        }
        BigDecimal qty = signedQuantity.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (qty.signum() == 0) {
            throw new IllegalArgumentException("Quantity must not be 0");
        }
        if (qty.signum() < 0 && IN_ONLY.contains(type) || qty.signum() > 0 && OUT_ONLY.contains(type)) {
            throw new IllegalArgumentException(type + " cannot have quantity " + qty.toPlainString());
        }
        if (unitPrice != null && unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Unit price must not be below 0");
        }

        Product product = session.find(Product.class, productId, LockModeType.PESSIMISTIC_WRITE);
        if (product == null) {
            throw new IllegalStateException("Product not found: " + productId);
        }
        BigDecimal before = currentStock(session, productId);
        BigDecimal oldAvg = averageCost(session, productId);

        BigDecimal price;
        BigDecimal newAvg;
        if (qty.signum() < 0) {
            // A back-dated stock out must not make the stock negative on that day or any later day
            BigDecimal available = availableOn(session, productId, date);
            if (available.compareTo(qty.negate()) < 0) {
                return new Result(Status.INSUFFICIENT_STOCK, available.max(BigDecimal.ZERO), null);
            }
            if (type == StockLedgerType.PURCHASE_RETURN && unitPrice != null) {
                // Goods sent back leave at what they cost; the rest of the stock carries the remaining value
                price = unitPrice.setScale(COST_SCALE, RoundingMode.HALF_UP);
                BigDecimal after = before.add(qty);
                newAvg = after.signum() <= 0 ? oldAvg
                        : before.multiply(oldAvg).add(qty.multiply(price)).max(BigDecimal.ZERO)
                                .divide(after, COST_SCALE, RoundingMode.HALF_UP);
            } else {
                price = oldAvg;
                newAvg = oldAvg;
            }
        } else {
            price = unitPrice == null ? oldAvg : unitPrice.setScale(COST_SCALE, RoundingMode.HALF_UP);
            newAvg = before.signum() <= 0 ? price
                    : before.multiply(oldAvg).add(qty.multiply(price))
                            .divide(before.add(qty), COST_SCALE, RoundingMode.HALF_UP);
        }

        StockLedger s = new StockLedger();
        s.setProduct(product);
        s.setDate(date);
        s.setType(type);
        s.setQuantity(qty);
        s.setUnitPrice(price);
        s.setRunningBalance(before.add(qty));
        s.setAvgCostAfter(newAvg);
        s.setReason(reason);
        s.setReferenceId(referenceId);
        s.setNote(note == null || note.isBlank() ? null : note.trim());
        if (UserSession.isLoggedIn()) {
            s.setCreatedBy(session.getReference(User.class, UserSession.get().getUserId()));
        }
        repository.save(session, s);
        return new Result(Status.DONE, null, s);
    }
}
