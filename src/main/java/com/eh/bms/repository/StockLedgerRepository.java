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

    /**
     * Drops the CHECK constraint Hibernate made on {@code type} for the first enum version (it rejects the
     * newer types such as OPENING); hbm2ddl update never changes it. Safe every startup.
     */
    public boolean dropOldTypeCheck(Session session) {
        List<String> names = session.createNativeQuery(
                        "SELECT tc.constraint_name FROM information_schema.table_constraints tc"
                                + " JOIN information_schema.check_constraints cc"
                                + " ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name"
                                + " WHERE tc.table_schema = DATABASE() AND tc.table_name = 'stock_ledger'"
                                + " AND tc.constraint_type = 'CHECK' AND cc.check_clause LIKE '%`type`%'"
                                + " AND cc.check_clause NOT LIKE '%PURCHASE_RETURN%'", String.class)
                .getResultList();
        for (String name : names) {
            session.createNativeMutationQuery("ALTER TABLE stock_ledger DROP CHECK `" + name + "`").executeUpdate();
        }
        return !names.isEmpty();
    }

    public void save(Session session, StockLedger entry) {
        session.persist(entry);
    }
}
