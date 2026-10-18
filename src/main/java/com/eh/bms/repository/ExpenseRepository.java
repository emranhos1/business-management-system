package com.eh.bms.repository;

import com.eh.bms.model.Expense;
import org.hibernate.Session;
import org.hibernate.query.SelectionQuery;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Database access for {@code expenses}. */
public class ExpenseRepository {

    /** Filters of the expenses list; any may be null. {@code text}: the note. */
    public record Filter(String text, LocalDate from, LocalDate to) {
    }

    /** Every expense the filter finds, newest first, with category and account loaded. */
    public List<Expense> list(Session session, Filter filter) {
        return where(session.createSelectionQuery("from Expense e join fetch e.category join fetch e.account"
                + where(filter) + " order by e.date desc, e.id desc", Expense.class), filter).getResultList();
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
        return q;
    }

    private static boolean hasText(Filter f) {
        return f.text() != null && !f.text().isBlank();
    }
}
