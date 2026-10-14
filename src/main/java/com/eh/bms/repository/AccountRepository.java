package com.eh.bms.repository;

import com.eh.bms.model.Account;
import org.hibernate.Session;

import java.util.List;
import java.util.Optional;

/** Database access for {@code accounts}. */
public class AccountRepository {

    /** One page, sorted by name; the type is loaded too (the table shows it after the session closes). */
    public List<Account> page(Session session, int offset, int limit) {
        return session.createSelectionQuery("from Account a left join fetch a.type order by a.name", Account.class)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public long count(Session session) {
        return session.createSelectionQuery("select count(a) from Account a", Long.class).getSingleResult();
    }

    public Optional<Account> findById(Session session, long id) {
        return Optional.ofNullable(session.find(Account.class, id));
    }

    /** True if another account (not {@code excludeId}) has this name. Case is ignored by the DB collation. */
    public boolean existsByName(Session session, String name, Long excludeId) {
        return existsBy(session, "name", name, excludeId);
    }

    /** Same as {@link #existsByName} for the English name. */
    public boolean existsByNameEn(Session session, String name, Long excludeId) {
        return existsBy(session, "nameEn", name, excludeId);
    }

    private static boolean existsBy(Session session, String field, String value, Long excludeId) {
        String hql = "select count(a) from Account a where a." + field + " = :value"
                + (excludeId == null ? "" : " and a.id <> :id");
        var query = session.createSelectionQuery(hql, Long.class).setParameter("value", value);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }

    /** True if any ledger entry, expense or salary payment uses the account. */
    public boolean isUsed(Session session, long id) {
        return count(session, "select count(t) from AccountTransaction t where t.account.id = :id", id)
                + count(session, "select count(e) from Expense e where e.account.id = :id", id)
                + count(session, "select count(s) from SalaryProcessing s where s.account.id = :id", id) > 0;
    }

    /** True if the account has ledger entries (its opening balance is then fixed). */
    public boolean hasTransactions(Session session, long id) {
        return count(session, "select count(t) from AccountTransaction t where t.account.id = :id", id) > 0;
    }

    public void save(Session session, Account account) {
        session.persist(account);
    }

    public void delete(Session session, Account account) {
        session.remove(account);
    }

    private static long count(Session session, String hql, long id) {
        return session.createSelectionQuery(hql, Long.class).setParameter("id", id).getSingleResult();
    }
}
