package com.eh.bms.repository;

import com.eh.bms.model.ExpenseCategory;
import org.hibernate.Session;

import java.util.List;
import java.util.Optional;

/** Database access for {@code expense_categories}. */
public class ExpenseCategoryRepository {

    /** All categories, in the order they were added (seeded ones first). */
    public List<ExpenseCategory> findAll(Session session) {
        return session.createSelectionQuery("from ExpenseCategory c order by c.id", ExpenseCategory.class)
                .getResultList();
    }

    public List<ExpenseCategory> page(Session session, int offset, int limit) {
        return session.createSelectionQuery("from ExpenseCategory c order by c.id", ExpenseCategory.class)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public long count(Session session) {
        return session.createSelectionQuery("select count(c) from ExpenseCategory c", Long.class).getSingleResult();
    }

    public Optional<ExpenseCategory> findById(Session session, long id) {
        return Optional.ofNullable(session.find(ExpenseCategory.class, id));
    }

    /** True if another category (not {@code excludeId}) has this Bangla name. */
    public boolean existsByName(Session session, String name, Long excludeId) {
        return existsBy(session, "name", name, excludeId);
    }

    /** True if another category (not {@code excludeId}) has this English name. */
    public boolean existsByNameEn(Session session, String name, Long excludeId) {
        return existsBy(session, "nameEn", name, excludeId);
    }

    /** True if any expense has this category. */
    public boolean isUsed(Session session, long id) {
        return session.createSelectionQuery("select count(e) from Expense e where e.category.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult() > 0;
    }

    public void save(Session session, ExpenseCategory category) {
        session.persist(category);
    }

    public void delete(Session session, ExpenseCategory category) {
        session.remove(category);
    }

    private static boolean existsBy(Session session, String field, String value, Long excludeId) {
        String hql = "select count(c) from ExpenseCategory c where c." + field + " = :value"
                + (excludeId == null ? "" : " and c.id <> :id");
        var query = session.createSelectionQuery(hql, Long.class).setParameter("value", value);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }
}
