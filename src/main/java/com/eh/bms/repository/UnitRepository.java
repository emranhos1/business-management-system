package com.eh.bms.repository;

import com.eh.bms.model.Unit;
import org.hibernate.Session;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Database access for {@code units}. */
public class UnitRepository {

    /** One page, in the order they were added (seeded common units first). */
    public List<Unit> page(Session session, int offset, int limit) {
        return session.createSelectionQuery("from Unit u order by u.id", Unit.class)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    /** Every unit, in the order they were added (for pick lists, e.g. the product form). */
    public List<Unit> all(Session session) {
        return session.createSelectionQuery("from Unit u order by u.id", Unit.class).getResultList();
    }

    public long count(Session session) {
        return session.createSelectionQuery("select count(u) from Unit u", Long.class).getSingleResult();
    }

    public Optional<Unit> findById(Session session, long id) {
        return Optional.ofNullable(session.find(Unit.class, id));
    }

    /** True if another unit (not {@code excludeId}) has this Bangla name. Case is ignored by the DB collation. */
    public boolean existsByNameBn(Session session, String name, Long excludeId) {
        return existsBy(session, "nameBn", name, excludeId);
    }

    /** Same as {@link #existsByNameBn} for the English name. */
    public boolean existsByNameEn(Session session, String name, Long excludeId) {
        return existsBy(session, "nameEn", name, excludeId);
    }

    /** True if any product uses this unit (as its unit or secondary unit), or any past sale line was in it. */
    public boolean isUsed(Session session, long id) {
        long products = session.createSelectionQuery("select count(p) from Product p"
                        + " where p.unit.id = :id or p.secondaryUnit.id = :id", Long.class)
                .setParameter("id", id)
                .getSingleResult();
        long lines = session.createSelectionQuery("select count(i) from TransactionItem i where i.unitRef.id = :id",
                        Long.class)
                .setParameter("id", id)
                .getSingleResult();
        return products + lines > 0;
    }

    /** Number of products using each unit (as unit or secondary unit); units with none are left out. */
    public Map<Long, Long> productCounts(Session session, Collection<Long> ids) {
        Map<Long, Long> counts = new HashMap<>();
        if (ids.isEmpty()) {
            return counts;
        }
        for (String field : new String[] {"unit", "secondaryUnit"}) {
            session.createSelectionQuery("select p." + field + ".id, count(p) from Product p where p." + field
                            + ".id in :ids group by p." + field + ".id", Object[].class)
                    .setParameter("ids", ids)
                    .getResultList()
                    .forEach(row -> counts.merge((Long) row[0], (Long) row[1], Long::sum));
        }
        return counts;
    }

    public void save(Session session, Unit unit) {
        session.persist(unit);
    }

    public void delete(Session session, Unit unit) {
        session.remove(unit);
    }

    /**
     * One-time move from the first product version, where {@code products.unit} and {@code products.secondary_unit}
     * were text columns: each distinct text becomes the unit whose Bangla or English name matches it (case ignored),
     * or a new unit with that text as both names. Fills {@code unit_id} / {@code secondary_unit_id}, then drops
     * the old columns. A product with a blank old unit gets the first unit (পিস / pcs when seeded).
     * Does nothing once the old columns are gone. Returns true if it ran.
     */
    public boolean migrateOldUnitColumns(Session session) {
        boolean ran = false;
        if (columnExists(session, "unit")) {
            for (String text : distinctOldValues(session, "unit")) {
                fillFromOldColumn(session, "unit", "unit_id", text, findOrCreate(session, text));
            }
            session.createNativeMutationQuery("UPDATE products SET unit_id = (SELECT MIN(u.id) FROM units u) "
                    + "WHERE unit_id IS NULL").executeUpdate();
            session.createNativeMutationQuery("ALTER TABLE products DROP COLUMN unit").executeUpdate();
            ran = true;
        }
        if (columnExists(session, "secondary_unit")) {
            for (String text : distinctOldValues(session, "secondary_unit")) {
                fillFromOldColumn(session, "secondary_unit", "secondary_unit_id", text, findOrCreate(session, text));
            }
            session.createNativeMutationQuery("ALTER TABLE products DROP COLUMN secondary_unit").executeUpdate();
            ran = true;
        }
        return ran;
    }

    private static boolean columnExists(Session session, String column) {
        return session.createNativeQuery("SELECT COUNT(*) FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = 'products' AND column_name = :column",
                        Long.class)
                .setParameter("column", column)
                .getSingleResult() > 0;
    }

    /** Distinct non-blank trimmed texts of an old column. */
    private static List<String> distinctOldValues(Session session, String column) {
        return session.createNativeQuery("SELECT DISTINCT TRIM(" + column + ") FROM products WHERE "
                        + column + " IS NOT NULL AND TRIM(" + column + ") <> ''", String.class)
                .getResultList();
    }

    private static void fillFromOldColumn(Session session, String oldColumn, String idColumn, String text, Unit unit) {
        session.createNativeMutationQuery("UPDATE products SET " + idColumn + " = :unitId WHERE " + idColumn
                        + " IS NULL AND LOWER(TRIM(" + oldColumn + ")) = LOWER(:text)")
                .setParameter("unitId", unit.getId())
                .setParameter("text", text)
                .executeUpdate();
    }

    /** The unit whose Bangla or English name is {@code text} (case ignored), or a new one named {@code text}. */
    private Unit findOrCreate(Session session, String text) {
        // "=" follows the DB collation (what the unique keys use); lower() also covers a case-sensitive collation
        Unit found = session.createSelectionQuery("from Unit u where u.nameBn = :text or u.nameEn = :text"
                        + " or lower(u.nameBn) = lower(:text) or lower(u.nameEn) = lower(:text) order by u.id",
                        Unit.class)
                .setParameter("text", text)
                .setMaxResults(1)
                .uniqueResult();
        if (found != null) {
            return found;
        }
        Unit unit = new Unit();
        unit.setNameBn(text);
        unit.setNameEn(text);
        save(session, unit);
        session.flush();
        return unit;
    }

    private static boolean existsBy(Session session, String field, String value, Long excludeId) {
        String hql = "select count(u) from Unit u where u." + field + " = :value"
                + (excludeId == null ? "" : " and u.id <> :id");
        var query = session.createSelectionQuery(hql, Long.class).setParameter("value", value);
        if (excludeId != null) {
            query.setParameter("id", excludeId);
        }
        return query.getSingleResult() > 0;
    }
}
