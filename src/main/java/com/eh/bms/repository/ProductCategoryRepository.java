package com.eh.bms.repository;

import com.eh.bms.model.ProductCategory;
import org.hibernate.Session;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Database access for {@code product_categories}. */
public class ProductCategoryRepository {

    /** One page, sorted by name. */
    public List<ProductCategory> page(Session session, int offset, int limit) {
        return session.createSelectionQuery("from ProductCategory c order by c.name", ProductCategory.class)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    /** Every category, sorted by name (for pick lists). */
    public List<ProductCategory> all(Session session) {
        return session.createSelectionQuery("from ProductCategory c order by c.name", ProductCategory.class)
                .getResultList();
    }

    public long count(Session session) {
        return session.createSelectionQuery("select count(c) from ProductCategory c", Long.class).getSingleResult();
    }

    public Optional<ProductCategory> findById(Session session, long id) {
        return Optional.ofNullable(session.find(ProductCategory.class, id));
    }

    /** True if another category (not {@code excludeId}) has this Bangla name. Case is ignored by the DB collation. */
    public boolean existsByName(Session session, String name, Long excludeId) {
        return existsBy(session, "name", name, excludeId);
    }

    /** Same as {@link #existsByName} for the English name. */
    public boolean existsByNameEn(Session session, String name, Long excludeId) {
        return existsBy(session, "nameEn", name, excludeId);
    }

    /** True if any product is in this category. */
    public boolean isUsed(Session session, long id) {
        return session.createSelectionQuery("select count(p) from Product p where p.category.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult() > 0;
    }

    /** Number of products per category id; categories with none are left out. */
    public Map<Long, Long> productCounts(Session session, Collection<Long> ids) {
        Map<Long, Long> counts = new HashMap<>();
        if (ids.isEmpty()) {
            return counts;
        }
        session.createSelectionQuery(
                        "select p.category.id, count(p) from Product p where p.category.id in :ids group by p.category.id",
                        Object[].class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(row -> counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }

    public void save(Session session, ProductCategory category) {
        session.persist(category);
    }

    public void delete(Session session, ProductCategory category) {
        session.remove(category);
    }

    private static boolean existsBy(Session session, String field, String value, Long excludeId) {
        String hql = "select count(c) from ProductCategory c where c." + field + " = :value"
                + (excludeId == null ? "" : " and c.id <> :id");
        var query = session.createSelectionQuery(hql, Long.class).setParameter("value", value);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }
}
