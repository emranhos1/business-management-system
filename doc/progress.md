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

## Stage 3 — Entities & Schema
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
| 3.5 | Entities: `accounts`, `account_transactions`, `expense_categories`, `expenses` | Tables created with FKs and user-approved indexes | [T] |
| 3.6 | Entities: `employees`, `attendance`, `salary_processing` | Tables created with FKs and user-approved indexes | [ ] |
| 3.7 | Entities: `users`, `permissions`, `audit_log` | Tables created with FKs and user-approved indexes | [ ] |
| 3.8 | Auto schema creation on startup (IF NOT EXISTS, FK order) | Empty DB → all tables; second run → no error | [ ] |

## Stage 4 — Startup Seeding
Depends on: Stage 3

| ID | Task | Test | Status |
|----|------|------|--------|
| 4.1 | Seed default `app_config` rows if missing | 6 default rows exist, no duplicates on rerun | [ ] |
| 4.2 | Seed default `business_profile` row if missing | 1 row exists, no duplicate on rerun | [ ] |
| 4.3 | Create admin from active profile yml if missing (BCrypt) | Admin row exists, password hashed | [ ] |
| 4.4 | Seed default role permissions (cashier limited) | Permission rows exist per role | [ ] |

## Stage 5 — Login & Security
Depends on: Stage 4

| ID | Task | Test | Status |
|----|------|------|--------|
| 5.1 | Login screen (FXML + controller) | Screen shows on launch | [ ] |
| 5.2 | Auth service: BCrypt check, block inactive users | Right/wrong/inactive login behave correctly | [ ] |
| 5.3 | Session object (user_id, role, permissions map) | Session holds data after login | [ ] |
| 5.4 | Main layout with sidebar; each button loads its own FXML | Each button opens its view | [ ] |
| 5.5 | Sidebar by role (cashier: no HR, reports, settings) | Cashier login shows limited sidebar | [ ] |
| 5.6 | Permission util hides create/edit/delete buttons | Button hidden without permission | [ ] |
| 5.7 | Audit log service for data changes | Change creates audit row | [ ] |
| 5.8 | Reusable paginated table (uses `items_per_page`) | Table pages correctly | [ ] |

## Stage 6 — Settings & Business Profile
Depends on: Stage 5

| ID | Task | Test | Status |
|----|------|------|--------|
| 6.1 | Business profile edit (name, address, phone, email, logo) | Saved values reload correctly | [ ] |
| 6.2 | App config edit (date format, pagination, auto backup, low stock default) | Saved values apply | [ ] |
| 6.3 | Language toggle saved to `app_config`, applies instantly | UI switches bn/en without restart | [ ] |
| 6.4 | Date format util used across app | Dates follow selected format | [ ] |

## Stage 7 — Accounts
Depends on: Stage 5

| ID | Task | Test | Status |
|----|------|------|--------|
| 7.1 | Account CRUD (cash/bank/mobile banking) | Create/edit/delete works | [ ] |
| 7.2 | Account transaction ledger view | Entries list correctly | [ ] |
| 7.3 | Current balance per account | Balance matches ledger | [ ] |
| 7.4 | Transfer between accounts (atomic) | Both balances update; failure rolls back | [ ] |

## Stage 8 — Products
Depends on: Stage 5

| ID | Task | Test | Status |
|----|------|------|--------|
| 8.1 | Product category CRUD | Create/edit/delete works | [ ] |
| 8.2 | Product CRUD (name, sku, unit, secondary unit, conversion, min stock) | Create/edit/delete works | [ ] |
| 8.3 | Unit conversion display (e.g. ১ বস্তা = ৫০ kg) | Shows correct conversion | [ ] |

## Stage 9 — Stock
Depends on: Stage 8

| ID | Task | Test | Status |
|----|------|------|--------|
| 9.1 | Stock ledger service (entry + running balance) | Balance correct after entries | [ ] |
| 9.2 | Weighted average cost calculation | Matches manual calculation | [ ] |
| 9.3 | Current stock per product view | Matches ledger | [ ] |
| 9.4 | Stock ledger view per product | Full history shows | [ ] |
| 9.5 | Stock adjustment with reason (damage/loss/correction) | Stock and ledger update | [ ] |
| 9.6 | Low stock alert list | Products below threshold listed | [ ] |
| 9.7 | Stock valuation view (stock × average cost) | Values match calculation | [ ] |

## Stage 10 — Parties
Depends on: Stage 5 (10.5 also needs Stage 7)

| ID | Task | Test | Status |
|----|------|------|--------|
| 10.1 | Party CRUD with opening balance; phone required (red error if empty or duplicate) | Create/edit/delete works; empty/duplicate phone shows red error | [ ] |
| 10.2 | Party ledger service (debit/credit/balance) | Balance correct after entries | [ ] |
| 10.3 | Party ledger view in plain words: columns দিলাম / পেলাম, balance as "৳X পাবো" (green) or "৳X দেবো" (red); never show debit, credit or minus sign | Full history shows; a non-accountant understands it | [ ] |
| 10.4 | Party list with current balance as "পাবো" (green) / "দেবো" (red) | Balances match ledger | [ ] |
| 10.5 | Payment/receipt against party (atomic, account linked) | Party and account both update | [ ] |

## Stage 11 — Sales
Depends on: Stages 7, 9, 10

| ID | Task | Test | Status |
|----|------|------|--------|
| 11.1 | Sale screen: customer + item rows (qty, unit, price, discount) | Rows add/remove correctly | [ ] |
| 11.2 | Totals: subtotal, VAT, grand total, due | Matches manual calculation | [ ] |
| 11.3 | Mixed payment entries (e.g. cash + mobile) | Multiple payments saved | [ ] |
| 11.4 | Confirm sale (atomic): stock, party, account ledgers | All update; failure rolls back | [ ] |
| 11.5 | Installment payment on existing sale | Due reduces correctly | [ ] |
| 11.6 | Sales history with filters (date/party/product) | Filters return right rows | [ ] |
| 11.7 | Sales return (restores stock, adjusts party ledger) | Stock and balance correct | [ ] |

## Stage 12 — Purchases
Depends on: Stages 7, 9, 10

| ID | Task | Test | Status |
|----|------|------|--------|
| 12.1 | Purchase screen: supplier + item rows | Rows add/remove correctly | [ ] |
| 12.2 | Confirm purchase (atomic): stock, avg cost, party, account | All update; failure rolls back | [ ] |
| 12.3 | Purchase history with filters (date/supplier/product) | Filters return right rows | [ ] |
| 12.4 | Purchase return (deducts stock, adjusts supplier ledger) | Stock and balance correct | [ ] |

## Stage 13 — Expenses
Depends on: Stage 7

| ID | Task | Test | Status |
|----|------|------|--------|
| 13.1 | Expense category CRUD | Create/edit/delete works | [ ] |
| 13.2 | Add expense (atomic account debit) | Expense saved, account reduced | [ ] |
| 13.3 | Expense history with date filter | Filter returns right rows | [ ] |
| 13.4 | Monthly expense summary by category | Totals match | [ ] |

## Stage 14 — Employees & Payroll
Depends on: Stage 5 (14.4 also needs Stage 7)

| ID | Task | Test | Status |
|----|------|------|--------|
| 14.1 | Employee CRUD | Create/edit/delete works | [ ] |
| 14.2 | Daily attendance with in/out time | Attendance saved per day | [ ] |
| 14.3 | Monthly salary calculation (attendance + deductions + bonus) | Matches manual calculation | [ ] |
| 14.4 | Salary payment linked to account (atomic) | Salary saved, account reduced | [ ] |
| 14.5 | Salary history per employee | History shows correctly | [ ] |

## Stage 15 — Dashboard
Depends on: Stages 7–14

| ID | Task | Test | Status |
|----|------|------|--------|
| 15.1 | Today's sales, purchases, expenses, net cash | Matches data | [ ] |
| 15.2 | Total receivable and payable | Matches party balances | [ ] |
| 15.3 | Stock summary by category | Matches stock | [ ] |
| 15.4 | Net profit this month | Matches P&L | [ ] |
| 15.5 | Low stock alerts | Matches alert list | [ ] |
| 15.6 | Account balances | Matches accounts | [ ] |

## Stage 16 — Reports (Excel .xlsx export)
Depends on: 16.1 first; each report depends on its module

| ID | Task | Test | Status |
|----|------|------|--------|
| 16.1 | Excel export util (Apache POI) | Sample table exports to .xlsx | [ ] |
| 16.2 | দৈনিক হিসাব (daily cash summary) | Data and export correct | [ ] |
| 16.3 | Stock position report | Data and export correct | [ ] |
| 16.4 | Party ledger report (one or all) | Data and export correct | [ ] |
| 16.5 | Purchase summary (daily/monthly/yearly) | Data and export correct | [ ] |
| 16.6 | Sales summary (daily/monthly/yearly) | Data and export correct | [ ] |
| 16.7 | Expense summary (daily/monthly/yearly) | Data and export correct | [ ] |
| 16.8 | Due collection report (customers) | Data and export correct | [ ] |
| 16.9 | Payable report (suppliers) | Data and export correct | [ ] |
| 16.10 | Salary report (monthly) | Data and export correct | [ ] |
| 16.11 | Stock valuation report | Data and export correct | [ ] |
| 16.12 | Profit & Loss (monthly/yearly) | Data and export correct | [ ] |

## Stage 17 — User Management (Admin only)
Depends on: Stage 5

| ID | Task | Test | Status |
|----|------|------|--------|
| 17.1 | User CRUD with role | Create/edit/delete works | [ ] |
| 17.2 | Activate/deactivate user | Inactive user cannot log in | [ ] |
| 17.3 | Permission editor per role and module | Changes apply after re-login | [ ] |
| 17.4 | Audit log viewer | Shows who did what and when | [ ] |

## Stage 18 — Auto-Update: GitHub Actions
Depends on: Stage 0

| ID | Task | Test | Status |
|----|------|------|--------|
| 18.1 | `release.yml`: trigger on `v*` tag, build JAR | Tag push builds JAR | [ ] |
| 18.2 | `jpackage` → `BMS-{version}-setup.exe` with bundled JRE | .exe installs on PC without Java | [ ] |
| 18.3 | Generate `version.json` | File has correct fields | [ ] |
| 18.4 | Generate `RELEASE_NOTES.md` from commits since last tag | Notes list right commits | [ ] |
| 18.5 | Create GitHub Release with .exe and `version.json` | Release shows both files | [ ] |

## Stage 19 — Auto-Update: In-App
Depends on: Stage 5, Stage 18

| ID | Task | Test | Status |
|----|------|------|--------|
| 19.1 | Background update check (no block, silent fail offline) | Login not delayed; offline no error | [ ] |
| 19.2 | Update banner on login (version, notes, update, dismiss) | Banner shows for newer version | [ ] |
| 19.3 | Download to `%TEMP%\BMS_update\` with progress bar | Progress shows, file saved | [ ] |
| 19.4 | File size check (Content-Length) | Wrong size is rejected | [ ] |
| 19.5 | `updater.bat`: wait, backup, silent install, relaunch, restore on fail | Update and rollback both work | [ ] |

## Stage 20 — Phase 2 (Nice to Have)
Depends on: Phase 1 done

| ID | Task | Test | Status |
|----|------|------|--------|
| 20.1 | VAT report (monthly) | Totals match | [ ] |
| 20.2 | bKash/Nagad/Rocket account types | Types selectable and saved | [ ] |
| 20.3 | Mobile payment summary report | Totals match | [ ] |
| 20.4 | Barcode/QR generate and print per product (SKU) | Printed code scans correctly | [ ] |
| 20.5 | Barcode scanner input on sale screen | Scan adds product row | [ ] |
| 20.6 | Google Drive OAuth2 (`credentials.json` in Settings) | Login to Drive works | [ ] |
| 20.7 | One-click MySQL dump to Google Drive | Backup file appears in Drive | [ ] |
| 20.8 | One-click restore from Google Drive | Data restored correctly | [ ] |
| 20.9 | Auto-backup on app close (if enabled) | Backup runs on close | [ ] |

---

## New Ideas
Add ideas here in order (N1, N2, ...). Split each into small tasks.

### N1 — User Registration
Depends on: Stages 5, 17

| ID | Task | Test | Status |
|----|------|------|--------|
| N1.1 | Decide rules: who can register, needs admin approval or not, default role | User confirms rules | [ ] |
| N1.2 | Registration screen (FXML + controller), opened from login screen | Screen opens and validates input | [ ] |
| N1.3 | Registration service: validate, unique username, BCrypt, save per N1.1 rules | New user saved, password hashed | [ ] |
| N1.4 | Admin approve/reject for registered users (only if N1.1 needs it) | Approved user can log in; rejected cannot | [ ] |

### N2 — Staff Transactions with Admin Approval
Staff can buy/sell when the owner is absent. Entries are saved in `transactions` with status `pending` until admin approves.
Depends on: Stages 11, 12, 17

| ID | Task | Test | Status |
|----|------|------|--------|
| N2.1 | Decide which actions staff can do (to be given by user) | User confirms list | [ ] |
| N2.2 | Decide stock rule while pending (block overselling or not) | User confirms rule | [ ] |
| N2.3 | Add `pending` and `rejected` to transaction status | Status values saved and read correctly | [ ] |
| N2.4 | Staff sale/purchase saves with status `pending` (no ledger change) | Entry in pending; stock/party/account unchanged | [ ] |
| N2.5 | Admin pending list view | All pending entries listed | [ ] |
| N2.6 | Admin approve (atomic): status → `confirmed`, update stock/party/account | Status confirmed; ledgers correct; failure rolls back | [ ] |
| N2.7 | Admin reject with reason: status → `rejected` | Entry marked rejected; no ledger change | [ ] |
| N2.8 | Staff view of own entries (pending/approved/rejected) | Status shows correctly | [ ] |
| N2.9 | Audit log for approve/reject | Audit row with admin and time | [ ] |

### N3 — Keep Passwords Out of the Public Repo
`application-{dev,stage,prod}.yml` hold DB and admin passwords; the GitHub repo is public.
Depends on: before first release (Stage 18)

| ID | Task | Test | Status |
|----|------|------|--------|
| N3.1 | Decide how passwords reach the app and CI (env vars, GitHub Secrets, or a local untracked file) | User confirms approach | [ ] |
| N3.2 | Remove real passwords from tracked yml files; load them per N3.1 | Repo has no real password; app still connects | [ ] |
| N3.3 | GitHub Actions build works without passwords in the repo | Tag push builds release | [ ] |
