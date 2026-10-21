package com.eh.bms.config;

import com.eh.bms.model.AccountNumberRule;
import com.eh.bms.model.AccountStatus;
import com.eh.bms.model.AccountTransactionSource;
import com.eh.bms.model.AccountTransactionType;
import com.eh.bms.model.AccountType;
import com.eh.bms.model.AppConfigEntry;
import com.eh.bms.model.AppModule;
import com.eh.bms.model.BalanceType;
import com.eh.bms.model.BusinessProfile;
import com.eh.bms.model.ExpenseCategory;
import com.eh.bms.model.PartyLedgerType;
import com.eh.bms.model.PartyStatus;
import com.eh.bms.model.PartyTradeType;
import com.eh.bms.model.PartyType;
import com.eh.bms.model.PaymentMethod;
import com.eh.bms.model.Permission;
import com.eh.bms.model.SalaryType;
import com.eh.bms.model.StockAdjustmentReason;
import com.eh.bms.model.StockLedgerType;
import com.eh.bms.model.TransactionStatus;
import com.eh.bms.model.TransactionType;
import com.eh.bms.model.Unit;
import com.eh.bms.model.User;
import com.eh.bms.model.UserRole;
import com.eh.bms.model.UserStatus;
import com.eh.bms.repository.AccountTypeRepository;
import com.eh.bms.repository.AppConfigRepository;
import com.eh.bms.repository.BusinessProfileRepository;
import com.eh.bms.repository.ExpenseCategoryRepository;
import com.eh.bms.repository.PermissionRepository;
import com.eh.bms.repository.ProductRepository;
import com.eh.bms.repository.SchemaRepository;
import com.eh.bms.repository.UnitRepository;
import com.eh.bms.repository.UserRepository;
import com.eh.bms.util.TransactionHelper;
import org.mindrot.jbcrypt.BCrypt;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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
            {"time_format", "hh:mm a", "Time display format (12/24-hour)"},
            {"items_per_page", "20", "Table pagination count"},
            {"dashboard_rows", "5", "Dashboard table rows per page"}
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

    /** Settings of the attendance removed in N12. */
    private static final List<String> ATTENDANCE_SETTINGS = List.of("weekly_off_days", "paid_leave",
            "work_start_time", "work_end_time", "attendance_auto_from");

    /** app_config key: number of one-time data fixes already applied (see {@link #migrateData()}). */
    private static final String DATA_VERSION = "data_version";

    private static final AppConfigRepository APP_CONFIG_REPO = new AppConfigRepository();
    private static final BusinessProfileRepository PROFILE_REPO = new BusinessProfileRepository();
    private static final UserRepository USER_REPO = new UserRepository();
    private static final PermissionRepository PERMISSION_REPO = new PermissionRepository();
    private static final AccountTypeRepository ACCOUNT_TYPE_REPO = new AccountTypeRepository();
    private static final ProductRepository PRODUCT_REPO = new ProductRepository();
    private static final UnitRepository UNIT_REPO = new UnitRepository();
    private static final SchemaRepository SCHEMA_REPO = new SchemaRepository();
    private static final ExpenseCategoryRepository EXPENSE_CATEGORY_REPO = new ExpenseCategoryRepository();

    /** Common expense categories: Bangla name, English name. */
    private static final String[][] DEFAULT_EXPENSE_CATEGORIES = {
            {"দোকান ভাড়া", "Shop rent"},
            {"বিদ্যুৎ বিল", "Electricity bill"},
            {"পরিবহন / গাড়ি ভাড়া", "Transport"},
            {"চা-নাস্তা", "Tea and snacks"},
            {"অন্যান্য", "Others"}
    };

    /** Bangla name, English name, number rule. */
    private static final Object[][] DEFAULT_ACCOUNT_TYPES = {
            {"ক্যাশ", "Cash", AccountNumberRule.NONE},
            {"ব্যাংক", "Bank", AccountNumberRule.BANK},
            {"বিকাশ", "bKash", AccountNumberRule.MOBILE},
            {"নগদ", "Nagad", AccountNumberRule.MOBILE},
            {"রকেট", "Rocket", AccountNumberRule.MOBILE}
    };

    /** Common units: Bangla name, English name. */
    private static final String[][] DEFAULT_UNITS = {
            {"পিস", "pcs"},
            {"কেজি", "kg"},
            {"গ্রাম", "g"},
            {"টন", "ton"},
            {"মণ", "maund"},
            {"লিটার", "L"},
            {"মিলিলিটার", "ml"},
            {"মিটার", "m"},
            {"সেন্টিমিটার", "cm"},
            {"ফুট", "ft"},
            {"ইঞ্চি", "inch"},
            {"গজ", "yard"},
            {"বর্গফুট", "sq ft"},
            {"ঘনফুট", "cft"},
            {"বস্তা", "bag"},
            {"কার্টন", "carton"},
            {"বক্স", "box"},
            {"ডজন", "dozen"},
            {"হালি", "hali"},
            {"প্যাকেট", "packet"},
            {"বোতল", "bottle"},
            {"ক্যান", "can"},
            {"রোল", "roll"},
            {"বান্ডেল", "bundle"},
            {"সেট", "set"},
            {"জোড়া", "pair"},
            {"গ্যালন", "gallon"},
            {"পাউন্ড", "lb"},
            {"কয়েল", "coil"},
            {"শিট", "sheet"}
    };

    private DataSeeder() {
    }

    public static void run() {
        // First: drop outdated enum CHECKs, so rows with a new enum value (new role, module, ...) can be inserted
        migrateEnumChecks();
        migrateColumnSizes();
        migrateUnusedColumns();
        seedAppConfig();
        seedBusinessProfile();
        seedAdmin();
        seedSupport();
        seedPermissions();
        seedAccountTypes();
        migrateAccountTypes();
        seedUnits();
        migrateProductUnits();
        seedExpenseCategories();
        migrateIndexes();
        migrateData();
    }

    /** Common expense categories, only when the table is empty (the user may delete or rename them later). */
    static void seedExpenseCategories() {
        TransactionHelper.runInTransaction(session -> {
            if (EXPENSE_CATEGORY_REPO.count(session) == 0) {
                for (String[] row : DEFAULT_EXPENSE_CATEGORIES) {
                    ExpenseCategory c = new ExpenseCategory();
                    c.setName(row[0]);
                    c.setNameEn(row[1]);
                    EXPENSE_CATEGORY_REPO.save(session, c);
                }
                LOG.info("Seeded expense_categories");
            }
        });
    }

    /** Indexes replaced by a newer one; idx_expenses_date_category also serves date-only searches. */
    static void migrateIndexes() {
        TransactionHelper.runInTransaction(session -> {
            if (SCHEMA_REPO.dropIndex(session, "expenses", "idx_expenses_date")) {
                LOG.info("Dropped idx_expenses_date (replaced by idx_expenses_date_category)");
            }
        });
    }

    /**
     * Every enum column: table, column, enum. A value added to an enum later would be rejected by the
     * CHECK Hibernate made when the table was created (see {@link SchemaRepository#dropOutdatedEnumCheck}).
     * Add new enum columns here.
     */
    private static final Object[][] ENUM_COLUMNS = {
            {"account_types", "number_rule", AccountNumberRule.class},
            {"account_transactions", "type", AccountTransactionType.class},
            {"account_transactions", "source", AccountTransactionSource.class},
            {"parties", "type", PartyType.class},
            {"parties", "party_type", PartyTradeType.class},
            {"parties", "balance_type", BalanceType.class},
            {"parties", "status", PartyStatus.class},
            {"accounts", "status", AccountStatus.class},
            {"party_ledger", "type", PartyLedgerType.class},
            {"permissions", "role", UserRole.class},
            {"permissions", "module", AppModule.class},
            {"stock_ledger", "type", StockLedgerType.class},
            {"stock_ledger", "reason", StockAdjustmentReason.class},
            {"transactions", "type", TransactionType.class},
            {"transactions", "payment_method", PaymentMethod.class},
            {"transactions", "status", TransactionStatus.class},
            {"transaction_payments", "payment_method", PaymentMethod.class},
            {"users", "role", UserRole.class},
            {"users", "status", UserStatus.class},
            {"users", "salary_type", SalaryType.class}
    };

    /** Drops enum CHECKs that miss a newer enum value (e.g. account_transactions.source PARTY). Safe every startup. */
    @SuppressWarnings("unchecked")
    static void migrateEnumChecks() {
        TransactionHelper.runInTransaction(session -> {
            for (Object[] c : ENUM_COLUMNS) {
                if (SCHEMA_REPO.dropOutdatedEnumCheck(session, (String) c[0], (String) c[1],
                        (Class<? extends Enum<?>>) c[2])) {
                    LOG.info("Dropped outdated CHECK on " + c[0] + "." + c[1]);
                }
            }
        });
    }

    /** Stock cost moved from 2 to 4 decimals (averages kept paisa-exact); widens existing columns once. */
    static void migrateColumnSizes() {
        TransactionHelper.runInTransaction(session -> {
            for (String column : new String[]{"unit_price", "avg_cost_after"}) {
                if (SCHEMA_REPO.widenDecimal(session, "stock_ledger", column, 16, 4)) {
                    LOG.info("Widened stock_ledger." + column + " to DECIMAL(16,4)");
                }
            }
        });
    }

    /**
     * Columns no entity uses any more are kept (old data stays) but must not block inserts: NOT NULL and enum
     * CHECKs go. expense_categories.type: categories have no type (user decision).
     */
    static void migrateUnusedColumns() {
        TransactionHelper.runInTransaction(session -> {
            if (SCHEMA_REPO.dropChecks(session, "expense_categories", "type")) {
                LOG.info("Dropped CHECK on expense_categories.type (unused)");
            }
            if (SCHEMA_REPO.makeNullable(session, "expense_categories", "type", "VARCHAR(20) NULL")) {
                LOG.info("Made expense_categories.type nullable (unused)");
            }
        });
    }

    /** Common units, only when the table is empty (the user may delete or rename them later). */
    static void seedUnits() {
        TransactionHelper.runInTransaction(session -> {
            if (UNIT_REPO.count(session) == 0) {
                for (String[] row : DEFAULT_UNITS) {
                    Unit u = new Unit();
                    u.setNameBn(row[0]);
                    u.setNameEn(row[1]);
                    UNIT_REPO.save(session, u);
                }
                LOG.info("Seeded units");
            }
        });
    }

    /** Moves products from the old text unit columns to units (first product version only). */
    static void migrateProductUnits() {
        TransactionHelper.runInTransaction(session -> {
            if (UNIT_REPO.migrateOldUnitColumns(session)) {
                LOG.info("Migrated products.unit / secondary_unit to unit_id / secondary_unit_id");
            }
            // Product names may repeat with another pack size, so the old unique name indexes go
            if (PRODUCT_REPO.dropOldNameIndexes(session)) {
                LOG.info("Dropped unique indexes on products.name / name_en");
            }
        });
    }

    /**
     * One-time fixes to rows seeded by older versions. Each runs once, tracked by {@code data_version}
     * in app_config, so a later change by the admin (e.g. the permission editor) is never undone.
     */
    static void migrateData() {
        TransactionHelper.runInTransaction(session -> {
            AppConfigEntry version = APP_CONFIG_REPO.findByKey(session, DATA_VERSION).orElse(null);
            int done;
            try {
                done = version == null ? 0 : Integer.parseInt(version.getConfigValue().trim());
            } catch (NumberFormatException e) {
                // A broken value must not stop the app, and re-running old fixes could undo the admin's changes
                LOG.severe("Bad data_version in app_config: " + version.getConfigValue() + "; data fixes skipped");
                return;
            }

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

            if (done < 2) {
                // v2: every product keeps its own minimum stock; older rows left blank get the app default
                BigDecimal lowStockDefault = APP_CONFIG_REPO.findValue(session, "low_stock_default")
                        .map(v -> {
                            try {
                                return new BigDecimal(v.trim());
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        })
                        .orElse(BigDecimal.TEN);
                int changed = session.createMutationQuery(
                                "update Product p set p.minStockAlert = :value where p.minStockAlert is null")
                        .setParameter("value", lowStockDefault == null ? BigDecimal.TEN : lowStockDefault)
                        .executeUpdate();
                LOG.info("Data fix v2: minimum stock filled for " + changed + " product(s)");
                done = 2;
            }

            if (done < 3) {
                // v3: parties.status added later; parties saved before it are active
                int changed = session.createMutationQuery("update Party p set p.status = :active where p.status is null")
                        .setParameter("active", PartyStatus.ACTIVE)
                        .executeUpdate();
                LOG.info("Data fix v3: " + changed + " party(ies) set active");
                done = 3;
            }

            if (done < 4) {
                // v4 (N12): attendance is gone, and with it its settings
                int changed = session.createMutationQuery("delete from AppConfigEntry e where e.configKey in :keys")
                        .setParameter("keys", ATTENDANCE_SETTINGS)
                        .executeUpdate();
                LOG.info("Data fix v4: " + changed + " attendance setting(s) removed");
                done = 4;
            }

            if (done < 5) {
                // v5 (20.2): bKash, Nagad and Rocket as account types (mobile rule); one the user already has is kept
                int added = 0;
                for (Object[] row : DEFAULT_ACCOUNT_TYPES) {
                    if (row[2] != AccountNumberRule.MOBILE
                            || ACCOUNT_TYPE_REPO.existsByNameBn(session, (String) row[0], null)
                            || ACCOUNT_TYPE_REPO.existsByNameEn(session, (String) row[1], null)) {
                        continue;
                    }
                    AccountType t = new AccountType();
                    t.setNameBn((String) row[0]);
                    t.setNameEn((String) row[1]);
                    t.setNumberRule(AccountNumberRule.MOBILE);
                    ACCOUNT_TYPE_REPO.save(session, t);
                    added++;
                }
                LOG.info("Data fix v5: " + added + " mobile wallet account type(s) added");
                done = 5;
            }

            if (done < 6) {
                // v6 (20.2): the generic "Mobile banking" type is replaced by bKash / Nagad / Rocket; removed unless
                // an account still uses it
                int removed = 0;
                for (AccountType t : ACCOUNT_TYPE_REPO.findAll(session)) {
                    if ("Mobile banking".equals(t.getNameEn()) && !ACCOUNT_TYPE_REPO.isUsed(session, t.getId())) {
                        ACCOUNT_TYPE_REPO.delete(session, t);
                        removed++;
                    }
                }
                LOG.info("Data fix v6: " + removed + " generic mobile banking type(s) removed");
                done = 6;
            }

            if (done < 7) {
                // v7 (20.4): every product has a code; an old product without one gets "P" and five digits
                java.util.Set<String> taken = new java.util.HashSet<>(session.createSelectionQuery(
                        "select p.sku from Product p where p.sku is not null and p.sku <> ''", String.class).getResultList());
                int filled = 0;
                for (com.eh.bms.model.Product p : session.createSelectionQuery(
                        "from Product p where p.sku is null or p.sku = '' order by p.id", com.eh.bms.model.Product.class)
                        .getResultList()) {
                    long n = p.getId();
                    String sku = String.format(java.util.Locale.ROOT, "P%05d", n);
                    while (taken.contains(sku)) {
                        n++;
                        sku = String.format(java.util.Locale.ROOT, "P%05d", n);
                    }
                    p.setSku(sku);
                    taken.add(sku);
                    filled++;
                }
                LOG.info("Data fix v7: " + filled + " product(s) given a code");
                done = 7;
            }

            if (done < 8) {
                // v8 (20.11): there is no "backup on app close" any more (the logout question replaced it)
                int removed = session.createMutationQuery("delete from AppConfigEntry e where e.configKey = :key")
                        .setParameter("key", "auto_backup")
                        .executeUpdate();
                LOG.info("Data fix v8: " + removed + " auto_backup setting removed");
                done = 8;
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

    /** Cash, Bank, bKash, Nagad and Rocket, only when the table is empty (the user may delete or rename them later). */
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

    /**
     * When no active admin exists (none yet, or all made inactive = locked out), makes the yml admin usable:
     * reactivates it if it exists as an inactive admin, otherwise creates it. Never takes over another
     * user's username and never creates an admin with a blank password (it could not log in).
     */
    static void seedAdmin() {
        AppConfig cfg = AppConfig.get();
        TransactionHelper.runInTransaction(session -> {
            if (USER_REPO.existsActiveByRole(session, UserRole.ADMIN)) {
                return;
            }
            String username = cfg.getString("admin.username").trim();
            String password = cfg.getString("admin.password", "");
            Optional<User> existing = USER_REPO.findByUsername(session, username);
            if (existing.isPresent()) {
                User u = existing.get();
                if (u.getRole() == UserRole.ADMIN) {
                    u.setStatus(UserStatus.ACTIVE);
                    LOG.warning("No active admin: reactivated admin user " + username);
                } else {
                    LOG.warning("No active admin, and the yml admin username '" + username
                            + "' belongs to a non-admin user; nothing changed");
                }
                return;
            }
            if (username.isEmpty() || password.isBlank()) {
                LOG.warning("No active admin, and admin.username / admin.password in the yml is blank; no admin created");
                return;
            }
            User u = new User();
            u.setName(cfg.getString("admin.name"));
            u.setUsername(username);
            u.setPasswordHash(BCrypt.hashpw(password, BCrypt.gensalt()));
            u.setRole(UserRole.ADMIN);
            u.setStatus(UserStatus.ACTIVE);
            // The config password is known to whoever reads the repo: the admin picks their own at the first login
            u.setMustChangePassword(true);
            USER_REPO.save(session, u);
            LOG.info("Seeded admin user: " + u.getUsername());
        });
    }

    /**
     * The support team's hidden login ({@link SupportAccount}): created if missing, and kept in step with the hash
     * in the code (so a release can change the support password). Not created while the hash is blank; never
     * takes over a normal user's username.
     */
    static void seedSupport() {
        if (SupportAccount.PASSWORD_HASH.isBlank()) {
            LOG.warning("SupportAccount.PASSWORD_HASH is blank: no support login is created");
            return;
        }
        TransactionHelper.runInTransaction(session -> {
            Optional<User> existing = USER_REPO.findByUsername(session, SupportAccount.USERNAME);
            if (existing.isPresent()) {
                User u = existing.get();
                if (u.getRole() != UserRole.SUPER_ADMIN) {
                    LOG.warning("The username '" + SupportAccount.USERNAME
                            + "' belongs to a normal user; no support login is created");
                    return;
                }
                u.setName(SupportAccount.NAME);
                u.setPasswordHash(SupportAccount.PASSWORD_HASH);
                u.setStatus(UserStatus.ACTIVE);
                u.setMustChangePassword(false);
                return;
            }
            User u = new User();
            u.setName(SupportAccount.NAME);
            u.setUsername(SupportAccount.USERNAME);
            u.setPasswordHash(SupportAccount.PASSWORD_HASH);
            u.setRole(UserRole.SUPER_ADMIN);
            u.setStatus(UserStatus.ACTIVE);
            USER_REPO.save(session, u);
            LOG.info("Seeded the support login");
        });
    }

    /** One row per role and module; missing rows only. The support login needs none (it is an admin). */
    static void seedPermissions() {
        TransactionHelper.runInTransaction(session -> {
            for (UserRole role : UserRole.values()) {
                if (role == UserRole.SUPER_ADMIN) {
                    continue;
                }
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
            case CASHIER -> {
                create = CASHIER_CREATE.contains(module);
                view = create || CASHIER_VIEW_ONLY.contains(module);
                editDelete = false;
            }
            // Employee (N12): nothing until the admin ticks it in the permission editor
            default -> {
                view = false;
                create = false;
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
