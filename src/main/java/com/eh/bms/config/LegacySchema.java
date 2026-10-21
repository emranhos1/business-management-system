package com.eh.bms.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Database changes that must happen before Hibernate builds the tables (hbm2ddl), with plain JDBC. Each step checks
 * first, so it is safe on every startup.
 * <p>
 * N12 (user decision): users and employees are one; the old employee data was test data and is dropped. The
 * {@code employees} and {@code attendance} tables go, and the salary tables (they pointed at employees) are made
 * again pointing at users, so their old rows go too, with the salary money out in the account ledgers. The
 * permission rows of the removed EMPLOYEE section go, as no Java enum value can read them any more.
 */
final class LegacySchema {

    private static final Logger LOG = Logger.getLogger(LegacySchema.class.getName());

    private LegacySchema() {
    }

    static void run(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            if (tableExists(c, "employees")) {
                try (Statement s = c.createStatement()) {
                    if (tableExists(c, "account_transactions")) {
                        int rows = s.executeUpdate("DELETE FROM account_transactions WHERE source = 'SALARY'");
                        LOG.info("N12: removed " + rows + " salary entries from the account ledgers");
                    }
                    s.executeUpdate("DROP TABLE IF EXISTS attendance");
                    s.executeUpdate("DROP TABLE IF EXISTS salary_payments");
                    s.executeUpdate("DROP TABLE IF EXISTS salary_processing");
                    s.executeUpdate("DROP TABLE IF EXISTS employees");
                }
                LOG.info("N12: dropped employees, attendance and the old salary tables");
            }
            if (tableExists(c, "permissions")) {
                try (Statement s = c.createStatement()) {
                    int rows = s.executeUpdate("DELETE FROM permissions WHERE module = 'EMPLOYEE'");
                    if (rows > 0) {
                        LOG.info("N12: removed " + rows + " EMPLOYEE permission rows");
                    }
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update the old database tables", e);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet rs = meta.getTables(c.getCatalog(), null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}
