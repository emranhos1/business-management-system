package com.eh.bms.config;

import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AccountType;
import com.eh.bms.model.AppConfigEntry;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.BusinessProfile;
import com.eh.bms.model.Permission;
import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import com.eh.bms.model.UserStatus;
import com.eh.bms.repository.AccountTypeRepository;
import com.eh.bms.repository.AppConfigRepository;
import com.eh.bms.repository.BusinessProfileRepository;
import com.eh.bms.repository.PermissionRepository;
import com.eh.bms.repository.UserRepository;
import com.eh.bms.util.TransactionHelper;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Inserts default data on startup. Each step adds only what is missing,
 * so running it again never creates duplicates. Existing rows change only through the
 * one-time fixes in {@link #migrateData()}.
 * Call after {@link DatabaseConfig#init()}.
 */
public final class DataSeeder {

    private static final Logger LOG = Logger.getLogger(DataSeeder.class.getName());

    /** key, value, description. */
    private static final String[][] DEFAULT_APP_CONFIG = {
            {"default_language", "bn", "UI language (bn/en)"},
            {"low_stock_default", "10", "Default minimum stock alert"},
            {"profit_method", "average", "Profit calculation method"},
            {"date_format", "dd/MM/yyyy", "Date display format"},
            {"items_per_page", "20", "Table pagination count"},
            {"auto_backup", "false", "Auto backup on app close"}
    };

    private static final String DEFAULT_BUSINESS_NAME = "আমার ব্যবসা";
    private static final String DEFAULT_CURRENCY = "৳";
    /** English-mode symbol; also used when an older row has none. */
    public static final String DEFAULT_CURRENCY_EN = "Tk.";
    private static final int DEFAULT_FINANCIAL_YEAR_START = 7; // July

    /** Manager: everything except settings and user management. */
    private static final Set<AppModule> MANAGER_MODULES =
            EnumSet.complementOf(EnumSet.of(AppModule.SETTINGS, AppModule.USER));
    /** Cashier may view and add, never edit or delete. */
    private static final Set<AppModule> CASHIER_CREATE =
            EnumSet.of(AppModule.PARTY, AppModule.SALE, AppModule.PURCHASE, AppModule.EXPENSE);
    /** Cashier view only. No HR, reports, settings, users or accounts (user decision). */
    private static final Set<AppModule> CASHIER_VIEW_ONLY =
            EnumSet.of(AppModule.DASHBOARD, AppModule.PRODUCT, AppModule.STOCK);

    /** app_config key: number of one-time data fixes already applied (see {@link #migrateData()}). */
    private static final String DATA_VERSION = "data_version";

    private static final AppConfigRepository APP_CONFIG_REPO = new AppConfigRepository();
    private static final BusinessProfileRepository PROFILE_REPO = new BusinessProfileRepository();
    private static final UserRepository USER_REPO = new UserRepository();
    private static final PermissionRepository PERMISSION_REPO = new PermissionRepository();
    private static final AccountTypeRepository ACCOUNT_TYPE_REPO = new AccountTypeRepository();

    /** Bangla name, English name, number rule. */
    private static final Object[][] DEFAULT_ACCOUNT_TYPES = {
            {"ক্যাশ", "Cash", AccountNumberRule.NONE},
            {"ব্যাংক", "Bank", AccountNumberRule.BANK},
            {"মোবাইল ব্যাংকিং", "Mobile banking", AccountNumberRule.MOBILE}
    };

    private DataSeeder() {
    }

    public static void run() {
        seedAppConfig();
        seedBusinessProfile();
        seedAdmin();
        seedPermissions();
        seedAccountTypes();
        migrateAccountTypes();
        migrateData();
    }

    /**
     * One-time fixes to rows seeded by older versions. Each runs once, tracked by {@code data_version}
     * in app_config, so a later change by the admin (e.g. the permission editor) is never undone.
     */
    static void migrateData() {
        TransactionHelper.runInTransaction(session -> {
            AppConfigEntry version = APP_CONFIG_REPO.findByKey(session, DATA_VERSION).orElse(null);
            int done = version == null ? 0 : Integer.parseInt(version.getConfigValue().trim());

            if (done < 1) {
                // v1: cashier no longer sees the Accounts module
                int changed = session.createMutationQuery("update Permission p set p.canView = false,"
                                + " p.canCreate = false, p.canEdit = false, p.canDelete = false"
                                + " where p.role = :role and p.module = :module")
                        .setParameter("role", UserRole.CASHIER)
                        .setParameter("module", AppModule.ACCOUNT)
                        .executeUpdate();
                LOG.info("Data fix v1: cashier Accounts permission removed (" + changed + " row)");
                done = 1;
            }

            if (version == null) {
                version = new AppConfigEntry();
                version.setConfigKey(DATA_VERSION);
                version.setDescription("One-time data fixes applied");
                version.setConfigValue(String.valueOf(done));
                APP_CONFIG_REPO.save(session, version);
            } else {
                version.setConfigValue(String.valueOf(done));
            }
        });
    }

    /** Cash, Bank and Mobile banking, only when the table is empty (the user may delete or rename them later). */
    static void seedAccountTypes() {
        TransactionHelper.runInTransaction(session -> {
            if (ACCOUNT_TYPE_REPO.count(session) == 0) {
                for (Object[] row : DEFAULT_ACCOUNT_TYPES) {
                    AccountType t = new AccountType();
                    t.setNameBn((String) row[0]);
                    t.setNameEn((String) row[1]);
                    t.setNumberRule((AccountNumberRule) row[2]);
                    ACCOUNT_TYPE_REPO.save(session, t);
                }
                LOG.info("Seeded account_types");
            }
        });
    }

    /** Moves accounts from the old text type column to account_types (first version only). */
    static void migrateAccountTypes() {
        TransactionHelper.runInTransaction(session -> {
            if (ACCOUNT_TYPE_REPO.migrateOldTypeColumn(session)) {
                LOG.info("Migrated accounts.type to accounts.type_id");
            }
        });
    }

    static void seedAppConfig() {
        TransactionHelper.runInTransaction(session -> {
            for (String[] row : DEFAULT_APP_CONFIG) {
                if (!APP_CONFIG_REPO.existsByKey(session, row[0])) {
                    AppConfigEntry e = new AppConfigEntry();
                    e.setConfigKey(row[0]);
                    e.setConfigValue(row[1]);
                    e.setDescription(row[2]);
                    APP_CONFIG_REPO.save(session, e);
                    LOG.info("Seeded app_config: " + row[0]);
                }
            }
        });
    }

    static void seedBusinessProfile() {
        TransactionHelper.runInTransaction(session -> {
            if (PROFILE_REPO.count(session) == 0) {
                BusinessProfile p = new BusinessProfile();
                p.setName(DEFAULT_BUSINESS_NAME);
                p.setCurrencySymbol(DEFAULT_CURRENCY);
                p.setCurrencySymbolEn(DEFAULT_CURRENCY_EN);
                p.setVatPercentage(BigDecimal.ZERO);
                p.setFinancialYearStart(DEFAULT_FINANCIAL_YEAR_START);
                PROFILE_REPO.save(session, p);
                LOG.info("Seeded business_profile");
            }
        });
    }

    /** Creates the admin from the active profile yml when no admin user exists. */
    static void seedAdmin() {
        AppConfig cfg = AppConfig.get();
        TransactionHelper.runInTransaction(session -> {
            if (!USER_REPO.existsByRole(session, UserRole.ADMIN)) {
                User u = new User();
                u.setName(cfg.getString("admin.name"));
                u.setUsername(cfg.getString("admin.username"));
                u.setPasswordHash(BCrypt.hashpw(cfg.getString("admin.password"), BCrypt.gensalt()));
                u.setRole(UserRole.ADMIN);
                u.setStatus(UserStatus.ACTIVE);
                USER_REPO.save(session, u);
                LOG.info("Seeded admin user: " + u.getUsername());
            }
        });
    }

    /** One row per role and module; missing rows only. */
    static void seedPermissions() {
        TransactionHelper.runInTransaction(session -> {
            for (UserRole role : UserRole.values()) {
                for (AppModule module : AppModule.values()) {
                    if (!PERMISSION_REPO.exists(session, role, module)) {
                        PERMISSION_REPO.save(session, defaultPermission(role, module));
                    }
                }
            }
        });
    }

    private static Permission defaultPermission(UserRole role, AppModule module) {
        boolean view;
        boolean create;
        boolean editDelete;
        switch (role) {
            case ADMIN -> {
                view = true;
                create = true;
                editDelete = true;
            }
            case MANAGER -> {
                view = MANAGER_MODULES.contains(module);
                create = view;
                editDelete = view;
            }
            default -> {
                create = CASHIER_CREATE.contains(module);
                view = create || CASHIER_VIEW_ONLY.contains(module);
                editDelete = false;
            }
        }
        Permission p = new Permission();
        p.setRole(role);
        p.setModule(module);
        p.setCanView(view);
        p.setCanCreate(create);
        p.setCanEdit(editDelete);
        p.setCanDelete(editDelete);
        return p;
    }
}
