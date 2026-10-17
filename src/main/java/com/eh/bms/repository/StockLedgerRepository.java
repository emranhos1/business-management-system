package com.eh.bms.repository;

import com.eh.bms.model.StockLedger;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Database access for {@code stock_ledger} (product stock ledgers). */
public class StockLedgerRepository {

    /** Stock in and stock out of one product (both positive). */
    public record Totals(BigDecimal in, BigDecimal out) {
        public BigDecimal net() {
            return in.subtract(out);
        }
    }

    public Totals totals(Session session, long productId) {
        Object[] row = session.createSelectionQuery(
                        "select coalesce(sum(case when s.quantity > 0 then s.quantity else 0 end), 0),"
                                + " coalesce(sum(case when s.quantity < 0 then -s.quantity else 0 end), 0)"
                                + " from StockLedger s where s.product.id = :id", Object[].class)
                .setParameter("id", productId)
                .getSingleResult();
        return new Totals((BigDecimal) row[0], (BigDecimal) row[1]);
    }

    /** Stock (in - out) per product; products with no entries are missing from the map. */
    public Map<Long, BigDecimal> netByProduct(Session session, Collection<Long> productIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        List<Object[]> rows = session.createSelectionQuery(
                        "select s.product.id, sum(s.quantity) from StockLedger s"
                                + " where s.product.id in :ids group by s.product.id", Object[].class)
                .setParameter("ids", productIds)
                .getResultList();
        for (Object[] row : rows) {
            result.put((Long) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    /** The newest entry (highest id) of a product: its snapshot is the current stock and average cost. */
    public Optional<StockLedger> latest(Session session, long productId) {
        return session.createSelectionQuery(
                        "from StockLedger s where s.product.id = :id order by s.id desc", StockLedger.class)
                .setParameter("id", productId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /** Newest entry of each product; products with no entries are missing from the map. */
    public Map<Long, StockLedger> latestByProduct(Session session, Collection<Long> productIds) {
        Map<Long, StockLedger> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        session.createSelectionQuery(
                        "from StockLedger s where s.id in (select max(s2.id) from StockLedger s2"
                                + " where s2.product.id in :ids group by s2.product.id)", StockLedger.class)
                .setParameter("ids", productIds)
                .getResultList()
                .forEach(s -> result.put(s.getProduct().getId(), s));
        return result;
    }

    /** One page of a product's entries, newest first (by date, then entry order). */
    public List<StockLedger> page(Session session, long productId, int offset, int limit) {
        return session.createSelectionQuery(
                        "from StockLedger s where s.product.id = :id order by s.date desc, s.id desc",
                        StockLedger.class)
                .setParameter("id", productId)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public long count(Session session, long productId) {
        return session.createSelectionQuery(
                        "select count(s) from StockLedger s where s.product.id = :id", Long.class)
                .setParameter("id", productId)
                .getSingleResult();
    }

    /** Net quantity of the entries that come after this one in (date, id) order. */
    public BigDecimal netAfter(Session session, long productId, LocalDate date, long entryId) {
        BigDecimal net = session.createSelectionQuery(
                        "select sum(s.quantity) from StockLedger s where s.product.id = :id"
                                + " and (s.date > :date or (s.date = :date and s.id > :entryId))", BigDecimal.class)
                .setParameter("id", productId)
                .setParameter("date", date)
                .setParameter("entryId", entryId)
                .getSingleResult();
        return net == null ? BigDecimal.ZERO : net;
    }

    /** Stock from the entries dated on or before this date. */
    public BigDecimal netUpTo(Session session, long productId, LocalDate date) {
        BigDecimal net = session.createSelectionQuery(
                        "select coalesce(sum(s.quantity), 0) from StockLedger s where s.product.id = :id and s.date <= :date",
                        BigDecimal.class)
                .setParameter("id", productId)
                .setParameter("date", date)
                .getSingleResult();
        return net == null ? BigDecimal.ZERO : net;
    }

    /** Each entry's signed quantity dated after this date, in (date, id) order. */
    public List<BigDecimal> quantitiesAfter(Session session, long productId, LocalDate date) {
        return session.createSelectionQuery(
                        "select s.quantity from StockLedger s where s.product.id = :id and s.date > :date"
                                + " order by s.date, s.id", BigDecimal.class)
                .setParameter("id", productId)
                .setParameter("date", date)
                .getResultList();
    }

    public void save(Session session, StockLedger entry) {
        session.persist(entry);
    }
}
