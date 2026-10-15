package com.eh.bms.repository;

import com.eh.bms.model.AccountTransaction;
import com.eh.bms.model.AccountTransactionType;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Database access for {@code account_transactions} (account ledgers). */
public class AccountTransactionRepository {

    /** Money in (CREDIT) and money out (DEBIT) of one account. */
    public record Totals(BigDecimal in, BigDecimal out) {
        public BigDecimal net() {
            return in.subtract(out);
        }
    }

    /** Signed amount: + for money in, - for money out. */
    private static final String SIGNED =
            "sum(case when t.type = :credit then t.amount else -t.amount end)";

    public Totals totals(Session session, long accountId) {
        Object[] row = session.createSelectionQuery(
                        "select coalesce(sum(case when t.type = :credit then t.amount else 0 end), 0),"
                                + " coalesce(sum(case when t.type = :debit then t.amount else 0 end), 0)"
                                + " from AccountTransaction t where t.account.id = :id", Object[].class)
                .setParameter("credit", AccountTransactionType.CREDIT)
                .setParameter("debit", AccountTransactionType.DEBIT)
                .setParameter("id", accountId)
                .getSingleResult();
        return new Totals((BigDecimal) row[0], (BigDecimal) row[1]);
    }

    /** Net (in - out) per account; accounts with no entries are missing from the map. */
    public Map<Long, BigDecimal> netByAccount(Session session, Collection<Long> accountIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (accountIds.isEmpty()) {
            return result;
        }
        List<Object[]> rows = session.createSelectionQuery(
                        "select t.account.id, " + SIGNED + " from AccountTransaction t"
                                + " where t.account.id in :ids group by t.account.id", Object[].class)
                .setParameter("credit", AccountTransactionType.CREDIT)
                .setParameter("ids", accountIds)
                .getResultList();
        for (Object[] row : rows) {
            result.put((Long) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    /** One page of an account's entries, newest first (by date, then entry order). */
    public List<AccountTransaction> page(Session session, long accountId, int offset, int limit) {
        return session.createSelectionQuery(
                        "from AccountTransaction t where t.account.id = :id order by t.date desc, t.id desc",
                        AccountTransaction.class)
                .setParameter("id", accountId)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public long count(Session session, long accountId) {
        return session.createSelectionQuery(
                        "select count(t) from AccountTransaction t where t.account.id = :id", Long.class)
                .setParameter("id", accountId)
                .getSingleResult();
    }

    /** Net of the entries that come after this one in (date, id) order. */
    public BigDecimal netAfter(Session session, long accountId, LocalDate date, long entryId) {
        BigDecimal net = session.createSelectionQuery(
                        "select " + SIGNED + " from AccountTransaction t where t.account.id = :id"
                                + " and (t.date > :date or (t.date = :date and t.id > :entryId))", BigDecimal.class)
                .setParameter("credit", AccountTransactionType.CREDIT)
                .setParameter("id", accountId)
                .setParameter("date", date)
                .setParameter("entryId", entryId)
                .getSingleResult();
        return net == null ? BigDecimal.ZERO : net;
    }

    /** Entries by id, with their account loaded (e.g. the other side of transfers). */
    public Map<Long, AccountTransaction> findByIds(Session session, Collection<Long> ids) {
        Map<Long, AccountTransaction> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        session.createSelectionQuery("from AccountTransaction t join fetch t.account where t.id in :ids",
                        AccountTransaction.class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(t -> result.put(t.getId(), t));
        return result;
    }

    public void save(Session session, AccountTransaction entry) {
        session.persist(entry);
    }
}
