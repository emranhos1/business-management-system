# BMS — Progress Tracker

Source: `bms-prompt.md`
Stack: JDK 21, JavaFX 21.0.12, Maven 3.10.0

## Rules
- Tasks are small. Each task is done alone, then tested.
- A task is `Done` only after the user tests it and approves.
- Tasks in a stage are independent of each other where possible. Stage dependencies are listed under each stage.
- New ideas go to the **New Ideas** section at the end, numbered in order, and are split into small tasks.

## Status Legend
- `[ ]` To do
- `[~]` In progress
- `[T]` Waiting for user test
- ✅ Done (user approved)
- ✅ after a stage title: all tasks in that stage are done

---

## Stage 0 — Project Setup ✅
Depends on: none

| ID | Task | Test | Status |
|----|------|------|--------|
| 0.1 | Maven `pom.xml` with JavaFX, Hibernate, HikariCP, MySQL driver, BCrypt, Apache POI | `mvn compile` succeeds | ✅ |
| 0.2 | Package structure under `com.eh.bms` (controller, service, repository, model, updater, util, config) | Project compiles, folders exist | ✅ |
| 0.3 | `application.yml` (profile, app name, version, GitHub API) + `application-{dev,stage,prod}.yml` (DB, pool, admin); version filtered from pom | Built file shows real version | ✅ |
| 0.4 | Main JavaFX class opens an empty window | `mvn javafx:run` opens window | ✅ |
| 0.5 | Base `main.css` loaded by the app | Window loads with no CSS error | ✅ |

## Stage 1 — Config & Database Connection ✅
Depends on: Stage 0

| ID | Task | Test | Status |
|----|------|------|--------|
| 1.1 | Config loader reads `application.yml` + active profile file (SnakeYAML) | Values print correctly per profile | ✅ |
| 1.2 | HikariCP + Hibernate SessionFactory | App connects to MySQL | ✅ |
| 1.3 | MySQL unreachable → error dialog, then exit | Stop MySQL, run app, dialog shows | ✅ |
| 1.4 | Transaction helper for atomic operations | Forced error rolls back all changes | ✅ |

## Stage 2 — Localization (i18n) ✅
Depends on: Stage 0

| ID | Task | Test | Status |
|----|------|------|--------|
| 2.1 | `messages_bn.properties` and `messages_en.properties` with base keys | Files load without error | ✅ |
| 2.2 | I18n util that switches all labels instantly | Toggle changes labels without restart | ✅ |

## Stage 3 — Entities & Schema ✅
Depends on: Stage 1

Rules for every table:
- Indexes only on columns important for reports. Before each table, list proposed index columns and get user approval.
- Status/type columns use Java enums stored as VARCHAR (`@Enumerated(EnumType.STRING)`).

| ID | Task | Test | Status |
|----|------|------|--------|
| 3.1 | Entities: `business_profile`, `app_config` | Tables created with FKs and user-approved indexes | ✅ |
| 3.2 | Entities: `product_categories`, `products`, `stock_ledger` | Tables created with FKs and user-approved indexes | ✅ |
| 3.3 | Entities: `parties`, `party_ledger` | Tables created with FKs and user-approved indexes | ✅ |
| 3.4 | Entities: `transactions`, `transaction_items`, `transaction_payments` | Tables created with FKs and user-approved indexes | ✅ |
| 3.5 | Entities: `accounts`, `account_transactions`, `expense_categories`, `expenses` | Tables created with FKs and user-approved indexes | ✅ |
| 3.6 | Entities: `employees`, `attendance`, `salary_processing` | Tables created with FKs and user-approved indexes | ✅ |
| 3.7 | Entities: `users`, `permissions`, `audit_log` | Tables created with FKs and user-approved indexes | ✅ |
| 3.8 | Auto schema creation on startup (IF NOT EXISTS, FK order) | Empty DB → all tables; second run → no error | ✅ |

## Stage 4 — Startup Seeding ✅
Depends on: Stage 3

| ID | Task | Test | Status |
|----|------|------|--------|
| 4.1 | Seed default `app_config` rows if missing | 6 default rows exist, no duplicates on rerun | ✅ |
| 4.2 | Seed default `business_profile` row if missing | 1 row exists, no duplicate on rerun | ✅ |
| 4.3 | Create admin from active profile yml if missing (BCrypt) | Admin row exists, password hashed | ✅ |
| 4.4 | Seed default role permissions (cashier limited) | Permission rows exist per role | ✅ |

## Stage 5 — Login & Security ✅
Depends on: Stage 4

| ID | Task | Test | Status |
|----|------|------|--------|
| 5.1 | Login screen (FXML + controller) | Screen shows on launch | ✅ |
| 5.2 | Auth service: BCrypt check, block inactive users | Right/wrong/inactive login behave correctly | ✅ |
| 5.3 | Session object (user_id, role, permissions map) | Session holds data after login | ✅ |
| 5.4 | Main layout with sidebar; each button loads its own FXML | Each button opens its view | ✅ |
| 5.5 | Sidebar by role (cashier: no HR, reports, settings, accounts) | Cashier login shows limited sidebar | ✅ |
| 5.6 | Permission util hides create/edit/delete buttons | Button hidden without permission | ✅ |
| 5.7 | Audit log service for data changes | Change creates audit row | ✅ |
| 5.8 | Reusable paginated table (uses `items_per_page`) | Table pages correctly | ✅ |

## Stage 6 — Settings & Business Profile ✅
Depends on: Stage 5

| ID | Task | Test | Status |
|----|------|------|--------|
| 6.1 | Business profile edit (name, address, phone, email, logo) | Saved values reload correctly | ✅ |
| 6.2 | App config edit (date format, pagination, auto backup, low stock default) | Saved values apply | ✅ |
| 6.3 | Language toggle (BN/EN above logout) saved to `app_config`, applies instantly | UI switches bn/en without restart | ✅ |
| 6.4 | Date format util used across app | Dates follow selected format (sidebar clock) | ✅ |

## Stage 7 — Accounts ✅
Depends on: Stage 5

| ID | Task | Test | Status |
|----|------|------|--------|
| 7.1 | Account CRUD (cash/bank/mobile banking) | Create/edit/delete works | ✅ |
| 7.2 | Account transaction ledger view | Entries list correctly | ✅ |
| 7.3 | Current balance per account | Balance matches ledger | ✅ |
| 7.4 | Transfer between accounts (atomic) | Both balances update; failure rolls back | ✅ |

## Stage 8 — Products ✅
Depends on: Stage 5

| ID | Task | Test | Status |
|----|------|------|--------|
| 8.1 | Product category CRUD | Create/edit/delete works | ✅ |
| 8.2 | Product CRUD (name, sku, unit, secondary unit, conversion, min stock) | Create/edit/delete works | ✅ |
| 8.3 | Unit conversion display (e.g. ১ বস্তা = ৫০ kg) | Shows correct conversion | ✅ |
| 8.4 | Units list (`units` table, Bangla + English name, ~30 common units seeded) with add/edit/delete in a "একক" tab next to categories; product unit / secondary unit picked from it (FK) | Units CRUD works; product form uses the list; old text units migrated | ✅ |
| 8.5 | Copy button on each product row (opens add form filled in); min stock prefilled with the default (0 = no alert); same name allowed with another pack size | Copy works; same name + other conversion saves | ✅ |
| 8.6 | Sale prices on each product: retail and wholesale per main unit, optional per secondary unit (blank = main price ÷ conversion); sale screen fills the price by the customer's wholesale/retail type | Prices save and reload; list shows them | ✅ |

## Stage 9 — Stock ✅
Depends on: Stage 8

| ID | Task | Test | Status |
|----|------|------|--------|
| 9.1 | Stock ledger service (entry + running balance) | Balance correct after entries | ✅ |
| 9.2 | Weighted average cost calculation | Matches manual calculation | ✅ |
| 9.3 | Current stock per product view | Matches ledger | ✅ |
| 9.4 | Stock ledger view per product | Full history shows | ✅ |
| 9.5 | Stock adjustment with reason (damage/loss/correction) | Stock and ledger update | ✅ |
| 9.6 | Low stock alert list | Products below threshold listed | ✅ |
| 9.7 | Stock valuation view (stock × average cost) | Values match calculation | ✅ |
| 9.8 | Popups: no window title bar, full title turned 90° on a dark left strip (drag to move), shadow, open centered right of the sidebar; Yes/No uses the same popup; long ledger details show in a tooltip | Popups look and sit right; tooltip shows full note | ✅ |

## Stage 10 — Parties ✅
Depends on: Stage 5 (10.5 also needs Stage 7)

| ID | Task | Test | Status |
|----|------|------|--------|
| 10.1 | Party CRUD with opening balance; phone required (red error if empty or duplicate) | Create/edit/delete works; empty/duplicate phone shows red error | ✅ |
| 10.2 | Party ledger service (debit/credit/balance) | Balance correct after entries | ✅ |
| 10.3 | Party ledger view in plain words: columns দিলাম / পেলাম, balance as "৳X পাবো" (green) or "৳X দেবো" (red); never show debit, credit or minus sign | Full history shows; a non-accountant understands it | ✅ |
| 10.4 | Party list with current balance as "পাবো" (green) / "দেবো" (red) | Balances match ledger | ✅ |
| 10.5 | Payment/receipt against party (atomic, account linked) | Party and account both update | ✅ |

## Stage 11 — Sales ✅
Depends on: Stages 7, 9, 10

| ID | Task | Test | Status |
|----|------|------|--------|
| 11.1 | Sale screen: customer + item rows (qty, unit, price, discount) | Rows add/remove correctly | ✅ |
| 11.2 | Totals: subtotal, VAT, grand total, due | Matches manual calculation | ✅ |
| 11.3 | Mixed payment entries (e.g. cash + mobile) | Multiple payments saved | ✅ |
| 11.4 | Confirm sale (atomic): stock, party, account ledgers | All update; failure rolls back | ✅ |
| 11.5 | Installment payment on existing sale | Due reduces correctly | ✅ |
| 11.6 | Sales history with filters (date/party/product) | Filters return right rows | ✅ |
| 11.7 | Sales return (restores stock, adjusts party ledger) | Stock and balance correct | ✅ |
| 11.8 | Memo serial number saved with each sale (`transactions.memo_no`, never reused); sales list searchable by it | Each sale gets the next number; search by number finds it | ✅ |

## Stage 12 — Purchases ✅
Depends on: Stages 7, 9, 10

| ID | Task | Test | Status |
|----|------|------|--------|
| 12.1 | Purchase screen: supplier + item rows | Rows add/remove correctly | ✅ |
| 12.2 | Confirm purchase (atomic): stock, avg cost, party, account | All update; failure rolls back | ✅ |
| 12.3 | Purchase history with filters (date/supplier/product) | Filters return right rows | ✅ |
| 12.4 | Purchase return (deducts stock, adjusts supplier ledger) | Stock and balance correct | ✅ |

## Stage 13 — Expenses ✅
Depends on: Stage 7

| ID | Task | Test | Status |
|----|------|------|--------|
| 13.1 | Expense category CRUD | Create/edit/delete works | ✅ |
| 13.2 | Add expense (atomic account debit) | Expense saved, account reduced | ✅ |
| 13.3 | Expense history with date filter | Filter returns right rows | ✅ |
| 13.4 | Monthly expense summary by category | Totals match | ✅ |
| 13.5 | Expenses page as one page: list grouped by category (one table, share per category) beside the categories; month + year and date range filters; five tiles in one line; category type removed | Groups and totals match; arrow opens/closes once | ✅ |

## Stage 14 — Employees & Payroll ✅
Depends on: Stage 5 (14.4 also needs Stage 7)

| ID | Task | Test | Status |
|----|------|------|--------|
| 14.1 | Employee CRUD; no inactive while salary due or advance left | Create/edit/delete works; inactive blocked with money open | ✅ |
| 14.2 | Daily attendance with in/out time; present saved automatically each work day (work hours in Settings) | Attendance saved per day; auto rows skip off days, join date, existing rows | ✅ |
| 14.3 | Monthly salary calculation (attendance + deductions + bonus; paid leave setting) | Matches manual calculation | ✅ |
| 14.4 | Salary payment linked to account (atomic): advance before the month is worked out, or in parts after; never more than the due unless emergency advance, extra carries to next months (`salary_payments`) | Payment saved, account reduced; over-payment blocked; emergency extra carried forward | ✅ |
| 14.5 | Salary history per employee | History shows correctly | ✅ |
| 14.6 | Employees page tabs (list, attendance, salary); salary type column in attendance; times picked on a clock (`TimePicker`), never typed; weekly off days, paid leave and work hours in Settings | Clock picks in/out; settings apply | ✅ |

## Stage 15 — Dashboard ✅
Depends on: Stages 7–14

| ID | Task | Test | Status |
|----|------|------|--------|
| 15.1 | Today's sales, purchases, expenses, net cash | Matches data | ✅ |
| 15.2 | Total receivable and payable | Matches party balances | ✅ |
| 15.3 | Stock summary by category | Matches stock | ✅ |
| 15.4 | Net profit this month | Matches P&L | ✅ |
| 15.5 | Low stock alerts | Matches alert list | ✅ |
| 15.6 | Account balances | Matches accounts | ✅ |

## Stage 16 — Reports (Excel .xlsx export) ✅
Depends on: 16.1 first; each report depends on its module

| ID | Task | Test | Status |
|----|------|------|--------|
| 16.1 | Excel export util (Apache POI) | Sample table exports to .xlsx | ✅ |
| 16.2 | দৈনিক হিসাব (daily cash summary) | Data and export correct | ✅ |
| 16.3 | Stock position report | Data and export correct | ✅ |
| 16.4 | Party ledger report (one or all) | Data and export correct | ✅ |
| 16.5 | Purchase summary (daily/monthly/yearly) | Data and export correct | ✅ |
| 16.6 | Sales summary (daily/monthly/yearly) | Data and export correct | ✅ |
| 16.7 | Expense summary (daily/monthly/yearly) | Data and export correct | ✅ |
| 16.8 | Due collection report (customers) | Data and export correct | ✅ |
| 16.9 | Payable report (suppliers) | Data and export correct | ✅ |
| 16.10 | Salary report (monthly) | Data and export correct | ✅ |
| 16.11 | Stock valuation report | Data and export correct | ✅ |
| 16.12 | Profit & Loss (monthly/yearly) | Data and export correct | ✅ |

## Stage 17 — User Management (Admin only) ✅
Depends on: Stage 5

| ID | Task | Test | Status |
|----|------|------|--------|
| 17.1 | User CRUD with role | Create/edit/delete works | ✅ |
| 17.2 | Activate/deactivate user | Inactive user cannot log in | ✅ |
| 17.3 | Permission editor per role and module | Changes apply after re-login | ✅ |
| 17.4 | Audit log viewer | Shows who did what and when | ✅ |

## Stage 18 — Auto-Update: GitHub Actions ✅
Depends on: Stage 0
Done the finance-app way (user decision): push to `production` (after `dev-emon` → `development` → `stage`) releases only a pom version not yet tagged; Inno Setup installer; SHA-256 file instead of `version.json`; release notes and the version bump are written by Claude.

| ID | Task | Test | Status |
|----|------|------|--------|
| 18.1 | `release.yml`: on push to `production`, release only when `v<pom version>` tag does not exist; build `bms.jar` + `lib/` | Same version → nothing built; new version → JAR built | ✅ |
| 18.2 | `jpackage` app image (own Java runtime) + Inno Setup (`packaging/bms.iss`) → `BMS-Update-{version}.exe` | .exe installs on PC without Java; app opens | ✅ |
| 18.3 | `BMS-Update-{version}.exe.sha256` (the app reads the latest release from the GitHub API; no `version.json`) | Hash matches the .exe | ✅ |
| 18.4 | Hand-written `release-notes/v{version}.md` (English, `<!-- bn -->`, Bangla) as release text; missing → build fails | Release shows the notes; missing file stops it | ✅ |
| 18.5 | GitHub Release with .exe and .sha256; creates the `v{version}` tag | Release shows both files and the tag | ✅ |

## Stage 19 — Auto-Update: In-App ✅
Depends on: Stage 5, Stage 18
Done the finance-app way (user decision): the installer's own wizard replaces `updater.bat`.

| ID | Task | Test | Status |
|----|------|------|--------|
| 19.1 | Background update check on the login screen (`UpdateService.check`: `releases/latest` redirect → newest tag; no JSON) | Login not delayed; offline shows nothing | ✅ |
| 19.2 | Banner above the login card: new version and yours, "আপডেট করুন", "কী নতুন" (release text in the app's language) | Banner shows on every PC with an older version | ✅ |
| 19.3 | Ask the download folder (last one, else Downloads) and download with progress; only installers the app downloaded earlier are deleted | Progress shows, file saved in the picked folder | ✅ |
| 19.4 | Size (Content-Length) and SHA-256 check; a bad file is deleted and an error shows under the banner | Wrong file is rejected | ✅ |
| 19.5 | Open the installer's wizard and close the app; it replaces the app (data untouched) and can start BMS at the end | Update installs and BMS opens on the new version | ✅ |

## Stage 20 — Phase 2 (Nice to Have) ✅
Depends on: Phase 1 done

| ID | Task | Test | Status |
|----|------|------|--------|
| 20.1 | VAT report (monthly) | Totals match | ✅ |
| 20.2 | bKash/Nagad/Rocket account types | Types selectable and saved | ✅ |
| 20.3 | Mobile payment summary report | Totals match | ✅ |
| 20.4 | Barcode/QR generate and print per product (SKU) | Printed code scans correctly | ✅ |
| 20.5 | Barcode scanner input on sale screen | Scan adds product row | ✅ |
|  | **Backup (finance-app way)** |  |  |
| 20.6 | Local backup: `mysqldump` → `backup_data_<yyyyMMdd.HHmm>.sql` in a picked folder; finds `mysqldump`/`mysql` on the PC itself (PATH, registry, XAMPP/WAMP/Laragon folders) | File made; opens as valid SQL | ✅ |
| 20.7 | Local restore: pick a `.sql`, confirm, `mysql` imports it | Data restored correctly | ✅ |
| 20.8 | Google Drive connect / disconnect (OAuth2; `credentials.json` next to `BMS.exe`, never in git; token kept so later backups open no browser) | Connect once; next start needs no sign-in | ✅ |
| 20.9 | Backup to Drive: dump uploaded into a `BMS` folder on Drive (made the first time); last backup time read from Drive | File appears in Drive → BMS; time shows | ✅ |
| 20.10 | Restore from Drive: the newest backup in the `BMS` folder, confirm first | Data restored correctly | ✅ |
| 20.11 | Logout asks: Backup now / Skip & logout / Cancel; Cancel stops a running backup or sign-in and stays logged in. The old hidden `auto_backup` setting ("backup on app close") is removed | All three choices behave right; no auto backup option left | ✅ |

Backup is done exactly the finance-app way (user decision): no backup on app close, only the logout question. Backup and restore buttons are admin only, as in finance-app.

---

## New Ideas
Add ideas here in order (N1, N2, ...). Split each into small tasks.

### N1 — User Registration ✅
Depends on: Stages 5, 17

| ID | Task | Test | Status |
|----|------|------|--------|
| N1.1 | Rules (user decisions): anyone registers from the login screen; the user waits as `PENDING` until an admin approves; the admin picks the role when approving; reject deletes the registration | User confirms rules | ✅ |
| N1.2 | Login page has Login and রেজিস্ট্রেশন buttons; রেজিস্ট্রেশন opens a registration page in place of the login page (name, username, password twice; same rules as the user form) with a back-to-login button | Page opens, shows every error, back returns to login | ✅ |
| N1.3 | `RegistrationService`: unique username (support name kept), BCrypt, status `PENDING`, own password (no change at first login); login says "not approved yet" | New user saved, password hashed; login refused with that message | ✅ |
| N1.4 | Users page: three lists side by side (waiting for approval, only while any; active; inactive); approve (pick role) and reject (deletes) | Approved user logs in with the picked role; rejected one is gone and the username is free | ✅ |

### N3 — Keep Passwords Out of the Public Repo (dropped: replaced by N10; the DB password stays, it is `localhost`)
`application-{dev,stage,prod}.yml` hold DB and admin passwords; the GitHub repo is public.
Depends on: before first release (Stage 18)

| ID | Task | Test | Status |
|----|------|------|--------|
| N3.1 | Decide how passwords reach the app and CI (env vars, GitHub Secrets, or a local untracked file) | User confirms approach | [ ] |
| N3.2 | Remove real passwords from tracked yml files; load them per N3.1 | Repo has no real password; app still connects | [ ] |
| N3.3 | GitHub Actions build works without passwords in the repo | Tag push builds release | [ ] |

### N4 — App Icon ✅
Replace the default Java icon in the title bar and taskbar with a BMS icon.
Depends on: Stage 0 (N4.4 is used by 18.2)

| ID | Task | Test | Status |
|----|------|------|--------|
| N4.1 | Decide icon design: blue rounded square, 3 white bars + "BMS" (16/32 px: bars only) | User approves design | ✅ |
| N4.2 | Icon PNGs (16, 32, 48, 64, 128, 256) in `resources/icons/` | Files exist and look right at each size | ✅ |
| N4.3 | Set icon on every window (title bar + taskbar) via `AppIcons.apply` | Title bar and taskbar show BMS icon, not Java | ✅ |
| N4.4 | `packaging/bms.ico` (all sizes) for the installer | .ico opens with all sizes | ✅ |

### N5 — Splash Screen ✅
Show a splash screen before the login screen while the app starts.
Depends on: N4.2

| ID | Task | Test | Status |
|----|------|------|--------|
| N5.1 | Splash window: no title bar, centered, icon, app name, version, loading text | Splash shows on launch | ✅ |
| N5.2 | DB connect + seeding run in background while splash shows each step | Splash text changes per step; window not frozen | ✅ |
| N5.3 | On success close splash and open login; on failure close splash and show the error dialog | Both paths work (stop MySQL to test failure) | ✅ |

### N6 — Memo Print ✅
Print a memo (invoice) for a sale, showing its memo number.
Depends on: Stage 11 (memo number saved with each sale)

| ID | Task | Test | Status |
|----|------|------|--------|
| N6.1 | Decide memo layout and paper size: A4 / A5 / 80 mm roll picked in Settings (default A5); app's language; preview, then print; party's earlier and present balance, accounts paid into, thanks, signatures | User confirms layout | ✅ |
| N6.2 | Memo view: business name, logo, memo no., date, customer, items, totals, paid, due | Memo shows right data | ✅ |
| N6.3 | Print button after confirming a sale and on each row of the sales list | Printed memo matches the sale | ✅ |

### N11 — Warn When a Sale Has No Money ✅
Money is often forgotten before "বিক্রি করুন"; the sale then goes all on credit without a word.
Depends on: Stage 11

| ID | Task | Test | Status |
|----|------|------|--------|
| N11.1 | No money typed and total above 0: ask "No money was taken. Sell all ৳X on credit?" before the sale question; No = back to the money field | Sale with no money asks first; No saves nothing; Yes goes on as before | ✅ |

### N12 — Users and Employees Are One ✅
Every person is a user: admin, manager, cashier and employee all get a salary. No separate employee record anywhere.
Depends on: Stages 14, 17, N1

| ID | Task | Test | Status |
|----|------|------|--------|
| N12.1 | Rules (user decisions): one `users` table holds the employee details too; role (পদ) = admin / manager / cashier / employee, what each may do is set in Permissions; admin gets a salary too; attendance is removed; a salary cut needs a reason; registration unchanged (admin approves, then edits role and salary); old employee and salary data is test data and is dropped | User confirms rules | ✅ |
| N12.2 | DB: `users` gets mobile, NID, address, join date, salary type, salary; role EMPLOYEE added; salary rows point at the user; `employees` and `attendance` tables dropped at startup | Startup: tables changed, no error on second run | ✅ |
| N12.3 | Attendance removed: its tab, service, auto fill and its settings (weekly off, work time, paid leave); daily / hourly pay takes days / hours typed in | No attendance anywhere; daily pay works with typed days | ✅ |
| N12.4 | User form: name, username, password, mobile, NID, address, join date, role, salary type, salary; create and edit | User saves with salary; old rules still hold (last admin, own role) | ✅ |
| N12.5 | Users list: role and salary columns; row actions edit, active / inactive (blocked while salary is due or advance is left), salary, view (salary history) | Each action works | ✅ |
| N12.6 | Salary in one popup: work out the month and pay together (parts, advance, emergency advance as before); a cut shows a required reason | Pay works; cut without reason refused | ✅ |
| N12.7 | Users page tabs: users, salary, permissions, audit log; "কর্মচারী" leaves the sidebar; employee role in the permission editor | Tabs show; employee permissions save | ✅ |
| N12.8 | Salary report, profit and loss, dashboard and account ledger read salary from users | Figures match | ✅ |

### N7 — Sale Return Stock Rounding (known bug) ✅
Part returns of one sale line round the stock quantity on each return, so stock drifts (10 pcs sold as 0.833 dozen, returned 5 + 5, puts back 0.834). `SaleService.returnGoods` should use the cumulative rounding `PurchaseService` already uses (`mainShare(item, returned + qty) − mainShare(item, returned)`).
Depends on: Stage 11

| ID | Task | Test | Status |
|----|------|------|--------|
| N7.1 | Cumulative rounding in sale returns | Part returns of a line add up to its stock quantity exactly | ✅ |

### N9 — Coin Rounding on Sale Refunds ✅
A ৳0.70 piece cannot be paid back in coins: the shop pays ৳1 or nothing. The difference (under ৳1) is a real loss or gain, so it must count in cash and in profit. Done together with N7 (same function, same form).
Depends on: Stage 11

| ID | Task | Test | Status |
|----|------|------|--------|
| N9.1 | Refund may differ from the goods' value by under ৳1: a walk-in customer more or less, a party more (less stays in its ledger as credit); the difference is added to `returned_amount` and the party's return credit, so profit, cash and ledger all agree | ৳0.70 piece back as ৳1: cash −৳1, profit −৳1 on that sale, party ledger nets 0; back as ৳0 (walk-in): profit +৳0.70 kept | ✅ |
| N9.2 | Return form: walk-in refund field is editable; a line under it says the difference and that it is a loss (taken off profit) or gain; the confirm question says it too | Hint and confirm text show the difference; over ৳1 off is refused | ✅ |
| N9.3 | "Still worth" cap of the next return counted from the goods returned, not from `returned_amount` (which now holds roundings) | Second part return of a rounded-up first one keeps its full value | ✅ |

### N10 — First-Login Password Change and the Support Login ✅
Replaces N3's main need: the admin password in the public repo's yml is known to everyone, so every user picks their own password before getting in. The DB password stays in the yml (the DB is on the same PC: `localhost`). A forgotten password is solved by the support team's hidden login (the recovery-code idea was dropped by the user).
Depends on: Stages 5, 17 (N3 is dropped by user decision; this is its smaller replacement)

| ID | Task | Test | Status |
|----|------|------|--------|
| N10.1 | `users.must_change_password`; the admin made from the yml, and any user the admin creates or sets a password for, must pick their own at the next login (nothing opens before that; same or username-like password refused) | Fresh DB: admin / yml password → "choose your own password" popup; cancel = stays on login; save = in | ✅ |
| N10.2 | Support login (`SUPER_ADMIN` role, username `emranhos1`): password only as a BCrypt hash in `SupportAccount.PASSWORD_HASH` (made with `SupportHashTool`), created at startup, hidden from the users list and the audit log's user filter, cannot be edited, switched off or deleted; it can do everything an admin can, so it resets any admin's or user's password from the Users page | Support login works; not in the Users page; sets an admin's new password; that admin must pick their own at the next login | ✅ |
| N10.3 | Login screen says who to ask when a password is forgotten (no self-service reset) | Hint shows under the login button | ✅ |
| N10.4 | Dev auto-login (`dev.auto-login`, dev profile only) is kept blank (user decision): dev opens the login screen too; set a username there only to skip it | Dev run shows the login screen and the first-login popup | ✅ |

### N13 — Switch Off an Account With Zero Balance ✅
A money account that is empty and no longer used should leave the pick lists.
Depends on: Stage 7

| ID | Task | Test | Status |
|----|------|------|--------|
| N13.1 | `accounts.status` (active / inactive); the Accounts list gets a status column and a switch-off / switch-on button on each row; off only while the balance is exactly 0 ("It has a balance. Make it zero first."); an off account is hidden from every account pick list but keeps its ledger; audit row on each change | Account with ৳0: off works and it leaves the pick lists; with a balance: refused; switch on brings it back | ✅ |
| N13.2 | Parties and users: the separate inactive table is gone; one table each, inactive last with a status column, and only the switch-on button works on an inactive row (same as accounts) | Inactive party or user stays at the bottom of the one table; edit, view, money and double-click are off; switch on brings it back | ✅ |
| N13.3 | Salary tab removed: the users tab has the month and year pickers, the four totals and the month's net, paid and due columns; the details tooltip also shows the month's figures; clear-filter returns to this month | Month change updates totals and columns; pay from a row updates them; no salary tab | ✅ |

### N14 — Forgive a Party's Balance ✅
A customer who died or went away, or a supplier who forgave: the rest of the balance must be clearable.
Depends on: Stage 10

| ID | Task | Test | Status |
|----|------|------|--------|
| N14.1 | "মাফ" button on an active party row that has a balance (either way); confirm; one `WRITE_OFF` ledger entry brings the balance to exactly 0; audit row; ledger shows "বাকি মাফ" | Party with ৳0.56 পাবো: forgive makes it 0; ledger shows the entry; a party with no balance has no button | ✅ |
| N14.2 | Forgiven amounts count as a loss: dashboard profit card line "মাফ করা বাকি", a column in the profit and loss report, both reducing the net profit; a report "মাফ করা বাকির তালিকা" (date, party, which way, amount, total) | Forgive ৳0.56 owed to us: net profit falls by ৳0.56 in both; the list shows it | ✅ |
| N14.3 | Sale and purchase line entry on one row (product wide, unit, quantity with − / +, price, discount, add) | Fits one row; the product box is the widest | ✅ |
