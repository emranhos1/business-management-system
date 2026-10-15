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
        List<Object[]> checks = session.createNativeQuery(
                        "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc"
                                + " JOIN information_schema.check_constraints cc"
                                + " ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name"
                                + " WHERE tc.table_schema = DATABASE() AND tc.table_name = :table"
                                + " AND tc.constraint_type = 'CHECK'", Object[].class)
                .setParameter("table", table)
                .getResultList();
        Pattern onColumn = Pattern.compile("\\b" + Pattern.quote(column.toLowerCase(Locale.ROOT)) + "\\b");
        boolean dropped = false;
        for (Object[] check : checks) {
            String name = String.valueOf(check[0]);
            String clause = String.valueOf(check[1]);
            if (!onColumn.matcher(clause.toLowerCase(Locale.ROOT)).find()) {
                continue;
            }
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
}
