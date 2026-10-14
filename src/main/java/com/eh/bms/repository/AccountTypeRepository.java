package com.eh.bms.repository;

import com.eh.bms.model.AccountType;
import org.hibernate.Session;

import java.util.List;
import java.util.Optional;

/** Database access for {@code account_types}. */
public class AccountTypeRepository {

    /** All types, in the order they were added (seeded ones first). */
    public List<AccountType> findAll(Session session) {
        return session.createSelectionQuery("from AccountType t order by t.id", AccountType.class).getResultList();
    }

    public List<AccountType> page(Session session, int offset, int limit) {
        return session.createSelectionQuery("from AccountType t order by t.id", AccountType.class)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public long count(Session session) {
        return session.createSelectionQuery("select count(t) from AccountType t", Long.class).getSingleResult();
    }

    public Optional<AccountType> findById(Session session, long id) {
        return Optional.ofNullable(session.find(AccountType.class, id));
    }

    /** True if another type (not {@code excludeId}) has this Bangla name. */
    public boolean existsByNameBn(Session session, String name, Long excludeId) {
        return existsBy(session, "nameBn", name, excludeId);
    }

    /** True if another type (not {@code excludeId}) has this English name. */
    public boolean existsByNameEn(Session session, String name, Long excludeId) {
        return existsBy(session, "nameEn", name, excludeId);
    }

    /** True if any account has this type. */
    public boolean isUsed(Session session, long id) {
        return session.createSelectionQuery("select count(a) from Account a where a.type.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult() > 0;
    }

    public void save(Session session, AccountType type) {
        session.persist(type);
    }

    public void delete(Session session, AccountType type) {
        session.remove(type);
    }

    /**
     * One-time move from the first version, where {@code accounts.type} was a text column
     * (CASH / BANK / MOBILE_BANKING): fills {@code type_id} from it, then drops the old column.
     * Does nothing once the old column is gone. Returns true if it ran.
     */
    public boolean migrateOldTypeColumn(Session session) {
        Long old = session.createNativeQuery(
                        "SELECT COUNT(*) FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = 'accounts' AND column_name = 'type'",
                        Long.class)
                .getSingleResult();
        if (old == 0) {
            return false;
        }
        session.createNativeMutationQuery(
                        "UPDATE accounts a SET a.type_id = (SELECT MIN(t.id) FROM account_types t WHERE t.number_rule = "
                                + "CASE a.type WHEN 'CASH' THEN 'NONE' WHEN 'BANK' THEN 'BANK' ELSE 'MOBILE' END) "
                                + "WHERE a.type_id IS NULL")
                .executeUpdate();
        session.createNativeMutationQuery("ALTER TABLE accounts DROP COLUMN type").executeUpdate();
        return true;
    }

    private static boolean existsBy(Session session, String field, String value, Long excludeId) {
        String hql = "select count(t) from AccountType t where t." + field + " = :value"
                + (excludeId == null ? "" : " and t.id <> :id");
        var query = session.createSelectionQuery(hql, Long.class).setParameter("value", value);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }
}
