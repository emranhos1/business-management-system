package com.eh.bms.repository;

import com.eh.bms.model.Expense;
import org.hibernate.Session;
import org.hibernate.query.SelectionQuery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Database access for {@code expenses}. */
public class ExpenseRepository {

    /** Filters of the expenses list; any may be null. {@code text}: the note. */
    public record Filter(String text, LocalDate from, LocalDate to, Long categoryId) {
    }

    /** One page, newest first, with category and account loaded. */
    public List<Expense> page(Session session, Filter filter, int offset, int limit) {
        return where(session.createSelectionQuery("from Expense e join fetch e.category join fetch e.account"
                        + where(filter) + " order by e.date desc, e.id desc", Expense.class), filter)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public long count(Session session, Filter filter) {
        return where(session.createSelectionQuery("select count(e) from Expense e" + where(filter), Long.class), filter)
                .getSingleResult();
    }

    /** Sum of the amounts the filter finds. */
    public BigDecimal total(Session session, Filter filter) {
        return where(session.createSelectionQuery("select coalesce(sum(e.amount), 0) from Expense e" + where(filter),
                BigDecimal.class), filter).getSingleResult();
    }

    public Optional<Expense> findById(Session session, long id) {
        return Optional.ofNullable(session.find(Expense.class, id));
    }

    /** Expenses by id with their category loaded (names the expense in the account ledger). */
    public Map<Long, Expense> findByIds(Session session, Collection<Long> ids) {
        Map<Long, Expense> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        session.createSelectionQuery("from Expense e join fetch e.category where e.id in :ids", Expense.class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(e -> result.put(e.getId(), e));
        return result;
    }

    /** Total per category id for the dates {@code from}..{@code to}: rows of [categoryId, sum]. */
    public List<Object[]> totalsByCategory(Session session, LocalDate from, LocalDate to) {
        return session.createSelectionQuery("select e.category.id, sum(e.amount) from Expense e"
                        + " where e.date >= :from and e.date <= :to group by e.category.id", Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** Date of the oldest expense, or null if there is none. */
    public LocalDate firstDate(Session session) {
        return session.createSelectionQuery("select min(e.date) from Expense e", LocalDate.class).getSingleResult();
    }

    public void save(Session session, Expense expense) {
        session.persist(expense);
    }

    public void delete(Session session, Expense expense) {
        session.remove(expense);
    }

    private static String where(Filter f) {
        StringBuilder w = new StringBuilder(" where 1 = 1");
        if (hasText(f)) {
            w.append(" and e.note like :text");
        }
        if (f.from() != null) {
            w.append(" and e.date >= :from");
        }
        if (f.to() != null) {
            w.append(" and e.date <= :to");
        }
        if (f.categoryId() != null) {
            w.append(" and e.category.id = :category");
        }
        return w.toString();
    }

    private static <R> SelectionQuery<R> where(SelectionQuery<R> q, Filter f) {
        if (hasText(f)) {
            q.setParameter("text", "%" + f.text().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.from() != null) {
            q.setParameter("from", f.from());
        }
        if (f.to() != null) {
            q.setParameter("to", f.to());
        }
        if (f.categoryId() != null) {
            q.setParameter("category", f.categoryId());
        }
        return q;
    }

    private static boolean hasText(Filter f) {
        return f.text() != null && !f.text().isBlank();
    }
}
