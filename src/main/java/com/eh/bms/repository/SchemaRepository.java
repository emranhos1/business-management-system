package com.eh.bms.repository;

import org.hibernate.Session;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Schema fixes that hbm2ddl update cannot make. */
public class SchemaRepository {

    /**
     * Hibernate puts a CHECK constraint on an enum column listing the enum's values when it creates the
     * table, and hbm2ddl update never changes it. A value added to the enum later is then rejected by
     * MySQL. Drops such a CHECK on {@code table.column} when it lacks one of the enum's current values.
     * The Java enum still limits the values. Safe every startup.
     */
    public boolean dropOutdatedEnumCheck(Session session, String table, String column, Class<? extends Enum<?>> type) {
        boolean dropped = false;
        for (Object[] check : checksOn(session, table, column)) {
            String name = String.valueOf(check[0]);
            String clause = String.valueOf(check[1]);
            boolean outdated = false;
            for (Enum<?> value : type.getEnumConstants()) {
                // Whole word: SALE must not match inside SALE_RETURN
                if (!Pattern.compile("\\b" + Pattern.quote(value.name()) + "\\b").matcher(clause).find()) {
                    outdated = true;
                    break;
                }
            }
            if (outdated) {
                session.createNativeMutationQuery("ALTER TABLE `" + table + "` DROP CHECK `" + name + "`").executeUpdate();
                dropped = true;
            }
        }
        return dropped;
    }

    /** Drops every CHECK on {@code table.column} (for a column no entity uses any more). Safe every startup. */
    public boolean dropChecks(Session session, String table, String column) {
        boolean dropped = false;
        for (Object[] check : checksOn(session, table, column)) {
            session.createNativeMutationQuery("ALTER TABLE `" + table + "` DROP CHECK `" + check[0] + "`").executeUpdate();
            dropped = true;
        }
        return dropped;
    }

    /** CHECK constraints of {@code table} whose clause names {@code column}: name, clause. */
    private List<Object[]> checksOn(Session session, String table, String column) {
        List<Object[]> checks = session.createNativeQuery(
                        "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc"
                                + " JOIN information_schema.check_constraints cc"
                                + " ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name"
                                + " WHERE tc.table_schema = DATABASE() AND tc.table_name = :table"
                                + " AND tc.constraint_type = 'CHECK'", Object[].class)
                .setParameter("table", table)
                .getResultList();
        Pattern onColumn = Pattern.compile("\\b" + Pattern.quote(column.toLowerCase(Locale.ROOT)) + "\\b");
        return checks.stream()
                .filter(c -> onColumn.matcher(String.valueOf(c[1]).toLowerCase(Locale.ROOT)).find())
                .toList();
    }

    /**
     * hbm2ddl update never makes a NOT NULL column nullable. Changes {@code table.column} to
     * {@code columnDefinition} (e.g. {@code VARCHAR(20) NULL}) when it is still NOT NULL, so rows can leave a
     * column no entity uses any more empty. Values are kept. Safe every startup.
     */
    public boolean makeNullable(Session session, String table, String column, String columnDefinition) {
        List<String> found = session.createNativeQuery(
                        "SELECT is_nullable FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE() AND table_name = :table AND column_name = :column",
                        String.class)
                .setParameter("table", table)
                .setParameter("column", column)
                .getResultList();
        if (found.isEmpty() || "YES".equalsIgnoreCase(found.get(0))) {
            return false;
        }
        session.createNativeMutationQuery("ALTER TABLE `" + table + "` MODIFY `" + column + "` " + columnDefinition)
                .executeUpdate();
        return true;
    }

    /** hbm2ddl update never drops an index the entities no longer name. Drops it if it is there; safe every startup. */
    public boolean dropIndex(Session session, String table, String index) {
        Long found = session.createNativeQuery(
                        "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()"
                                + " AND table_name = :table AND index_name = :index", Long.class)
                .setParameter("table", table)
                .setParameter("index", index)
                .getSingleResult();
        if (found == 0) {
            return false;
        }
        session.createNativeMutationQuery("ALTER TABLE `" + table + "` DROP INDEX `" + index + "`").executeUpdate();
        return true;
    }

    /**
     * hbm2ddl update never changes an existing column's size. Widens a NOT NULL DECIMAL column to
     * {@code DECIMAL(precision, scale)} when it has fewer decimal places. Values are kept. Safe every startup.
     */
    public boolean widenDecimal(Session session, String table, String column, int precision, int scale) {
        // CAST: numeric_scale is BIGINT UNSIGNED; Hibernate needs a concrete result type (Long), not Number
        List<Long> found = session.createNativeQuery(
                        "SELECT CAST(numeric_scale AS SIGNED) FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE() AND table_name = :table AND column_name = :column",
                        Long.class)
                .setParameter("table", table)
                .setParameter("column", column)
                .getResultList();
        if (found.isEmpty() || found.get(0) == null || found.get(0).intValue() >= scale) {
            return false;
        }
        session.createNativeMutationQuery("ALTER TABLE `" + table + "` MODIFY `" + column + "` DECIMAL("
                + precision + "," + scale + ") NOT NULL").executeUpdate();
        return true;
    }
}
