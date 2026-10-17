package com.eh.bms.repository;

import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.TransactionItem;
import com.eh.bms.model.TransactionPayment;
import com.eh.bms.model.TransactionType;
import org.hibernate.Session;
import org.hibernate.query.SelectionQuery;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Database access for one type of {@code transactions} (sales or purchases) with their items and payments.
 */
public class TransactionRepository {

    /**
     * Filters of the sales / purchases list; any may be null. {@code text}: memo no., party name/mobile, note or
     * the supplier's memo no.
     */
    public record Filter(String text, LocalDate from, LocalDate to, Long productId) {
    }

    private final TransactionType type;

    public TransactionRepository(TransactionType type) {
        this.type = type;
    }

    /**
     * The next memo number for this type: one more than the highest so far. The row with the highest number is
     * locked (FOR UPDATE) until the transaction ends, so two sales saved at the same time cannot get the same
     * number; the unique index uk_transactions_type_memo backs this up.
     */
    public long nextMemoNo(Session session) {
        Number max = session.createNativeQuery(
                        "SELECT COALESCE(MAX(memo_no), 0) FROM transactions WHERE type = :type FOR UPDATE", Long.class)
                .setParameter("type", type.name())
                .getSingleResult();
        return max.longValue() + 1;
    }

    public void save(Session session, Object entity) {
        session.persist(entity);
    }

    /** A sale / purchase of this type with its party loaded. */
    public Optional<BusinessTransaction> find(Session session, long id) {
        return session.createSelectionQuery("from BusinessTransaction t left join fetch t.party"
                        + " where t.id = :id and t.type = :type", BusinessTransaction.class)
                .setParameter("id", id)
                .setParameter("type", type)
                .uniqueResultOptional();
    }

    /** Lines of a sale with product and units loaded, in entry order. */
    public List<TransactionItem> items(Session session, long saleId) {
        return session.createSelectionQuery("from TransactionItem i join fetch i.product p left join fetch p.unit"
                        + " left join fetch p.secondaryUnit left join fetch i.unitRef"
                        + " where i.transaction.id = :id order by i.id", TransactionItem.class)
                .setParameter("id", saleId)
                .getResultList();
    }

    /** The newest line of this type for a product (e.g. the last time it was bought). */
    public Optional<TransactionItem> lastItem(Session session, long productId) {
        return session.createSelectionQuery("from TransactionItem i where i.product.id = :product"
                        + " and i.transaction.type = :type order by i.id desc", TransactionItem.class)
                .setParameter("product", productId)
                .setParameter("type", type)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /** Payments of a sale with their account loaded, oldest first. */
    public List<TransactionPayment> payments(Session session, long saleId) {
        return session.createSelectionQuery("from TransactionPayment p left join fetch p.account"
                        + " where p.transaction.id = :id order by p.date, p.id", TransactionPayment.class)
                .setParameter("id", saleId)
                .getResultList();
    }

    /** One page of sales, newest first, with their party loaded. */
    public List<BusinessTransaction> page(Session session, Filter filter, int offset, int limit) {
        return where(session.createSelectionQuery("from BusinessTransaction t left join fetch t.party pa"
                        + where(filter) + " order by t.date desc, t.memoNo desc", BusinessTransaction.class), filter)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public long count(Session session, Filter filter) {
        return where(session.createSelectionQuery("select count(t) from BusinessTransaction t left join t.party pa"
                + where(filter), Long.class), filter).getSingleResult();
    }

    /** Memo numbers by sale id (e.g. to name the sale in a ledger line). */
    public Map<Long, Long> memoNos(Session session, Collection<Long> saleIds) {
        Map<Long, Long> result = new HashMap<>();
        if (saleIds.isEmpty()) {
            return result;
        }
        session.createSelectionQuery("select t.id, t.memoNo from BusinessTransaction t where t.id in :ids", Object[].class)
                .setParameter("ids", saleIds)
                .getResultList()
                .forEach(row -> result.put((Long) row[0], (Long) row[1]));
        return result;
    }

    private static String where(Filter f) {
        StringBuilder w = new StringBuilder(" where t.type = :type");
        if (hasText(f)) {
            w.append(" and (pa.name like :text or pa.nameEn like :text or pa.phone like :text or t.note like :text"
                    + " or t.supplierMemoNo like :text");
            if (memoNumber(f) != null) {
                w.append(" or t.memoNo = :memo");
            }
            w.append(")");
        }
        if (f.from() != null) {
            w.append(" and t.date >= :from");
        }
        if (f.to() != null) {
            w.append(" and t.date <= :to");
        }
        if (f.productId() != null) {
            w.append(" and exists (select 1 from TransactionItem i where i.transaction = t and i.product.id = :product)");
        }
        return w.toString();
    }

    private <R> SelectionQuery<R> where(SelectionQuery<R> q, Filter f) {
        q.setParameter("type", type);
        if (hasText(f)) {
            q.setParameter("text", "%" + f.text().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            Long memo = memoNumber(f);
            if (memo != null) {
                q.setParameter("memo", memo);
            }
        }
        if (f.from() != null) {
            q.setParameter("from", f.from());
        }
        if (f.to() != null) {
            q.setParameter("to", f.to());
        }
        if (f.productId() != null) {
            q.setParameter("product", f.productId());
        }
        return q;
    }

    private static boolean hasText(Filter f) {
        return f.text() != null && !f.text().isBlank();
    }

    /** The search text as a memo number, when it is only digits (e.g. "12" or "#12"). */
    private static Long memoNumber(Filter f) {
        String t = f.text().trim().replace("#", "");
        if (t.isEmpty() || t.length() > 18 || !t.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseLong(t);
    }
}
