package com.eh.bms.repository;

import com.eh.bms.model.Product;
import org.hibernate.Session;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Database access for {@code products}. */
public class ProductRepository {

    /** One page, sorted by name; category and units are loaded too (the table shows them after the session closes). */
    public List<Product> page(Session session, int offset, int limit) {
        return search(session, null, offset, limit);
    }

    public long count(Session session) {
        return countSearch(session, null);
    }

    /** Like {@link #page}, only products whose Bangla name, English name or SKU contains {@code text} (all when blank). */
    public List<Product> search(Session session, String text, int offset, int limit) {
        String like = likePattern(text);
        var query = session.createSelectionQuery("from Product p join fetch p.category left join fetch p.unit"
                + " left join fetch p.secondaryUnit" + searchWhere(like) + " order by p.name", Product.class);
        if (like != null) {
            query.setParameter("term", like);
        }
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    public long countSearch(Session session, String text) {
        String like = likePattern(text);
        var query = session.createSelectionQuery("select count(p) from Product p" + searchWhere(like), Long.class);
        if (like != null) {
            query.setParameter("term", like);
        }
        return query.getSingleResult();
    }

    private static String searchWhere(String like) {
        return like == null ? ""
                : " where p.name like :term escape '!' or p.nameEn like :term escape '!' or p.sku like :term escape '!'";
    }

    /** "%text%" with LIKE wildcards in the text escaped; null when the text is blank. */
    private static String likePattern(String text) {
        String term = text == null ? "" : text.trim();
        return term.isEmpty() ? null
                : "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    public Optional<Product> findById(Session session, long id) {
        return Optional.ofNullable(session.find(Product.class, id));
    }

    /**
     * True if another product (not {@code excludeId}) has this Bangla name and the same pack: unit,
     * secondary unit and conversion. The same name with another pack size is allowed. Case is ignored by the DB collation.
     */
    public boolean existsSamePack(Session session, String name, long unitId, Long secondaryUnitId,
                                  BigDecimal factor, Long excludeId) {
        return existsSamePack(session, "name", name, unitId, secondaryUnitId, factor, excludeId);
    }

    /** Same as {@link #existsSamePack} for the English name. */
    public boolean existsSamePackEn(Session session, String name, long unitId, Long secondaryUnitId,
                                    BigDecimal factor, Long excludeId) {
        return existsSamePack(session, "nameEn", name, unitId, secondaryUnitId, factor, excludeId);
    }

    /** Drops the old unique indexes on the product names (names may repeat with another pack size). Safe every startup. */
    public boolean dropOldNameIndexes(Session session) {
        boolean dropped = false;
        for (String index : List.of("uk_products_name", "uk_products_name_en")) {
            Long found = session.createNativeQuery(
                            "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()"
                                    + " AND table_name = 'products' AND index_name = :index", Long.class)
                    .setParameter("index", index)
                    .getSingleResult();
            if (found > 0) {
                session.createNativeMutationQuery("ALTER TABLE products DROP INDEX " + index).executeUpdate();
                dropped = true;
            }
        }
        return dropped;
    }

    private static boolean existsSamePack(Session session, String field, String name, long unitId,
                                          Long secondaryUnitId, BigDecimal factor, Long excludeId) {
        String hql = "select count(p) from Product p where p." + field + " = :name and p.unit.id = :unitId"
                + (secondaryUnitId == null ? " and p.secondaryUnit is null"
                        : " and p.secondaryUnit.id = :secondaryId and p.conversionFactor = :factor")
                + (excludeId == null ? "" : " and p.id <> :id");
        var query = session.createSelectionQuery(hql, Long.class)
                .setParameter("name", name)
                .setParameter("unitId", unitId);
        if (secondaryUnitId != null) {
            query.setParameter("secondaryId", secondaryUnitId).setParameter("factor", factor);
        }
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }

    /** Same as {@link #existsByName} for the SKU. */
    public boolean existsBySku(Session session, String sku, Long excludeId) {
        return existsBy(session, "sku", sku, excludeId);
    }

    /** True if any stock ledger entry or transaction item uses the product. */
    public boolean isUsed(Session session, long id) {
        return count(session, "select count(s) from StockLedger s where s.product.id = :id", id)
                + count(session, "select count(i) from TransactionItem i where i.product.id = :id", id) > 0;
    }

    public void save(Session session, Product product) {
        session.persist(product);
    }

    public void delete(Session session, Product product) {
        session.remove(product);
    }

    private static boolean existsBy(Session session, String field, String value, Long excludeId) {
        String hql = "select count(p) from Product p where p." + field + " = :value"
                + (excludeId == null ? "" : " and p.id <> :id");
        var query = session.createSelectionQuery(hql, Long.class).setParameter("value", value);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }

    private static long count(Session session, String hql, long id) {
        return session.createSelectionQuery(hql, Long.class).setParameter("id", id).getSingleResult();
    }
}
