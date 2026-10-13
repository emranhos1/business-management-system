package com.eh.bms.config;

import com.eh.bms.model.Account;
import com.eh.bms.model.AccountTransaction;
import com.eh.bms.model.AppConfigEntry;
import com.eh.bms.model.BusinessProfile;
import com.eh.bms.model.BusinessTransaction;
import com.eh.bms.model.Expense;
import com.eh.bms.model.ExpenseCategory;
import com.eh.bms.model.Party;
import com.eh.bms.model.PartyLedger;
import com.eh.bms.model.Product;
import com.eh.bms.model.ProductCategory;
import com.eh.bms.model.StockLedger;
import com.eh.bms.model.TransactionItem;
import com.eh.bms.model.TransactionPayment;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.JdbcSettings;

import java.util.List;
import java.util.logging.Logger;

/**
 * Owns the HikariCP pool and the Hibernate SessionFactory.
 * Call {@link #init()} once on startup and {@link #shutdown()} on exit.
 */
public final class DatabaseConfig {

    private static final Logger LOG = Logger.getLogger(DatabaseConfig.class.getName());
    private static final long CONNECTION_TIMEOUT_MS = 5_000;

    /** All JPA entities. Add each new entity here. */
    private static final List<Class<?>> ENTITIES = List.of(
            BusinessProfile.class,
            AppConfigEntry.class,
            ProductCategory.class,
            Product.class,
            StockLedger.class,
            Party.class,
            PartyLedger.class,
            BusinessTransaction.class,
            TransactionItem.class,
            TransactionPayment.class,
            Account.class,
            AccountTransaction.class,
            ExpenseCategory.class,
            Expense.class
    );

    private static HikariDataSource dataSource;
    private static SessionFactory sessionFactory;

    private DatabaseConfig() {
    }

    /**
     * Opens the pool and builds the SessionFactory.
     * Throws a RuntimeException if MySQL cannot be reached.
     */
    public static synchronized void init() {
        if (sessionFactory != null) {
            return;
        }
        AppConfig cfg = AppConfig.get();

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("bms-pool");
        hikari.setJdbcUrl(cfg.getString("db.url"));
        hikari.setUsername(cfg.getString("db.username"));
        hikari.setPassword(cfg.getString("db.password", ""));
        hikari.setMaximumPoolSize(cfg.getInt("db.pool.max-size", 10));
        hikari.setMinimumIdle(cfg.getInt("db.pool.min-idle", 2));
        hikari.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        hikari.setAutoCommit(false);

        HikariDataSource ds = new HikariDataSource(hikari);
        try {
            StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                    .applySetting(JdbcSettings.DATASOURCE, ds)
                    .build();
            MetadataSources sources = new MetadataSources(registry);
            ENTITIES.forEach(sources::addAnnotatedClass);
            sessionFactory = sources.buildMetadata().buildSessionFactory();
            dataSource = ds;
        } catch (RuntimeException e) {
            ds.close();
            throw e;
        }
        LOG.info("Database connected (profile: " + cfg.getProfile() + ")");
    }

    public static SessionFactory getSessionFactory() {
        if (sessionFactory == null) {
            throw new IllegalStateException("DatabaseConfig.init() has not been called");
        }
        return sessionFactory;
    }

    public static synchronized void shutdown() {
        if (sessionFactory != null) {
            sessionFactory.close();
            sessionFactory = null;
        }
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
