# Generic Business Management System — Project Prompt

You are an expert Java desktop application developer. Build a minimal but production-ready Generic Business Management System as a JavaFX desktop application for Bangladeshi SME trading/wholesale businesses. Follow every constraint exactly.

## Business Context
- Generic system — works for any trading/wholesale/retail business (rice, egg, cloth, grocery, medicine, etc.)
- Any number of employees, clients, suppliers
- Operations: stock management, buying/selling, client/supplier due tracking, employee payroll, expense tracking

## Tech Stack
- Language: Java 24 (latest stable LTS)
- UI: JavaFX
- ORM: Hibernate (no Spring Boot — pure JavaFX + Hibernate + HikariCP)
- Database: MySQL (runs on HOST machine directly)
- Build Tool: Maven
- Containerization: None

## Package Structure
- Base package: `com.eh.bms`
- Controllers: `com.eh.bms.controller`
- Services: `com.eh.bms.service`
- Repositories: `com.eh.bms.repository`
- Models: `com.eh.bms.model`
- Updater: `com.eh.bms.updater`
- Utils: `com.eh.bms.util`
- Config: `com.eh.bms.config`

## Localization
- Full Bangla + English bilingual support
- Language toggle in Settings screen — switches all UI labels instantly without restart
- All labels, buttons, messages, reports must have both Bangla and English versions
- Default language: Bangla
- i18n files:
  - `src/main/resources/i18n/messages_bn.properties`
  - `src/main/resources/i18n/messages_en.properties`

## Architecture Rules (NON-NEGOTIABLE)
1. MySQL runs on HOST machine directly
2. All credentials in `application.properties` — zero hardcoding anywhere
3. Atomic DB transactions for every financial operation
4. Modular UI: each section loads separately via sidebar (separate FXML files)
5. Clean layered architecture: Controller → IService (interface) → Service → Repository → DB
6. Controllers call IService only — never the concrete Service class directly
7. No business logic in Controllers
8. No DB queries in Service layer — use Repository only
9. Profit calculation: Weighted Average Cost method

---

## Database Schema

### Business Profile
```sql
business_profile: id, name, address, phone, email,
                  currency_symbol, vat_percentage,
                  financial_year_start, logo_path
```

### App Configuration
```sql
app_config: id, config_key, config_value, description

-- Default rows:
-- default_language     | bn           | UI language (bn/en)
-- low_stock_default    | 10           | Default minimum stock alert
-- profit_method        | average      | Profit calculation method
-- date_format          | dd/MM/yyyy   | Date display format
-- items_per_page       | 20           | Table pagination count
-- auto_backup          | false        | Auto backup on app close
```

### Product & Inventory
```sql
product_categories: id, name, created_at

products: id, category_id (FK → product_categories),
          name, sku, unit, secondary_unit,
          conversion_factor, min_stock_alert, created_at
-- example: unit=বস্তা, secondary_unit=kg, conversion_factor=50

stock_ledger: id, product_id (FK → products), date,
              type (purchase/sale/return/adjustment),
              quantity, unit_price, running_balance,
              reference_id, note
```

### Party Management (Client + Supplier unified)
```sql
parties: id, name, phone, address,
         type (customer/supplier/both),
         party_type (পাইকারি/খুচরা),
         opening_balance, balance_type (receivable/payable),
         created_at

party_ledger: id, party_id (FK → parties), date,
              type (sale/purchase/payment/receipt/return),
              debit, credit, balance,
              reference_id, note
```

### Transactions
```sql
transactions: id, type (sale/purchase/expense/salary/transfer),
              party_id (FK → parties), date, subtotal,
              discount, tax_amount, grand_total,
              paid_amount, due_amount,
              payment_method (cash/bank/mobile),
              status (draft/confirmed/cancelled), note

transaction_items: id, transaction_id (FK → transactions),
                   product_id (FK → products),
                   quantity, unit, unit_price,
                   discount_per_unit, total_price

transaction_payments: id, transaction_id (FK → transactions),
                      date, amount, payment_method, note
-- supports installment and mixed payment methods
```

### Accounts & Finance
```sql
accounts: id, name, type (cash/bank/mobile_banking),
          account_number, opening_balance, created_at

account_transactions: id, account_id (FK → accounts),
                      date, type (debit/credit),
                      amount, balance_after,
                      reference_id, note

expense_categories: id, name, type (operational/overhead/other)

expenses: id, date, category_id (FK → expense_categories),
          amount, account_id (FK → accounts), note
```

### HR & Payroll
```sql
employees: id, name, phone, nid, address,
           designation, join_date,
           salary_type (monthly/daily/hourly),
           base_salary, status (active/inactive)

attendance: id, employee_id (FK → employees), date,
            in_time, out_time,
            status (present/absent/half_day/leave), note

salary_processing: id, employee_id (FK → employees),
                   month, year, working_days,
                   present_days, leave_days,
                   gross_salary, deductions, bonus,
                   net_salary, account_id (FK → accounts),
                   paid_date, note
```

### Security & Audit
```sql
users: id, name, username, password_hash,
       role (admin/manager/cashier),
       status (active/inactive)

permissions: id, role, module,
             can_view, can_create, can_edit, can_delete

audit_log: id, user_id (FK → users), action, module,
           reference_id, old_value, new_value, timestamp
```

---

## Core Features

### Phase 1 — Must Have

#### 1. Dashboard
- Today's total sales, purchases, expenses, net cash
- Total receivable (customer due) and total payable (supplier due)
- Current stock summary by category
- Net profit this month
- Low stock alerts (per product threshold)
- Account balances (cash/bank/mobile)

#### 2. Business Profile & Settings
- Edit business profile (name, address, phone, logo)
- App config management (language, date format, pagination, auto backup)
- Language toggle (Bangla/English) — applies instantly

#### 3. Product & Category Management
- CRUD for product categories
- CRUD for products (name, sku, unit, secondary unit, conversion factor, min stock alert)
- Unit conversion display (e.g. ১ বস্তা = ৫০ kg)

#### 4. Stock Management
- Full stock ledger per product
- Current stock position per product
- Stock adjustment (damage/loss/correction) with reason
- Low stock alert list
- Stock valuation report (current stock × average cost)

#### 5. Party Management (Client + Supplier unified)
- CRUD for parties (customer/supplier/both)
- Per-party ledger — full transaction history
- Per-party current balance (receivable/payable)
- Party list with balance shown
- Payment/receipt recording against party

#### 6. Sales Management
- New sale — select customer, add multiple products (qty, unit, price, discount per item)
- Auto-calculate subtotal, VAT, grand total, due
- Mixed payment support (cash + mobile in one sale)
- Installment payment recording
- Auto-update stock ledger and party ledger on confirm
- Sales history with filter by date/party/product
- Sales return — restores stock and adjusts party ledger

#### 7. Purchase Management
- New purchase — select supplier, add multiple products
- Auto-update stock ledger and party ledger on confirm
- Purchase history with filter by date/supplier/product
- Purchase return — deducts stock and adjusts supplier ledger

#### 8. Expense Management
- Add expenses with category and account
- Expense history with date filter
- Monthly expense summary by category

#### 9. Employee Management
- CRUD for employees
- Daily attendance (present/absent/half_day/leave) with in/out time
- Monthly salary calculation (attendance + deductions + bonus)
- Salary payment linked to account
- Salary history per employee

#### 10. Account Management
- CRUD for accounts (cash/bank/mobile banking)
- Per-account transaction ledger
- Current balance per account
- Transfer between accounts

#### 11. Reports (all exportable as Excel .xlsx)
- দৈনিক হিসাব (daily cash summary)
- Stock position report
- Party ledger report (per party or all)
- Purchase summary (daily/monthly/yearly)
- Sales summary (daily/monthly/yearly)
- Expense summary (daily/monthly/yearly)
- Due collection report (customers with outstanding balance)
- Payable report (suppliers with outstanding balance)
- Salary report (monthly)
- Stock valuation report
- Profit & Loss statement (monthly/yearly)

#### 12. User Management (Admin only)
- CRUD for users with role assignment (admin/manager/cashier)
- Module-level permissions per role (view/create/edit/delete)
- Activate/deactivate users
- Admin credentials from `application.properties` — no registration for admin
- Full audit log — who did what and when

---

### Phase 2 — Nice to Have

#### 13. VAT & Tax
- VAT percentage configurable from business profile
- VAT auto-calculated on sales and purchases
- VAT report (monthly)

#### 14. Mobile Banking Payment Tracking
- bKash/Nagad/Rocket account type support
- Payment method tracked per transaction
- Mobile payment summary report

#### 15. Barcode Support
- Generate and print barcode/QR per product (SKU based)
- Barcode scanner input on sale screen

#### 16. Google Drive Backup
- One-click full MySQL dump to Google Drive
- One-click restore from Google Drive
- OAuth2 (credentials.json in Settings)
- Auto-backup on app close (if enabled in app_config)

---

### Auto-Update System

#### Version Management
- `<version>` in `pom.xml` is single source of truth
- `application.properties` auto-reads at build time: `app.version=@project.version@`
- Maven resource filtering must be enabled
- Developer only bumps `<version>` in `pom.xml`

#### GitHub Actions (fully automatic)
Trigger: push git tag matching `v*` (e.g. `v1.0.1`)

Developer workflow:
1. Bump `<version>` in `pom.xml`
2. `git commit -m "release v1.0.1"`
3. `git tag v1.0.1`
4. `git push origin main --tags`

GitHub Actions automatically:
1. Checkout code
2. Set up JDK (latest stable LTS)
3. Build fat JAR: `mvn clean package`
4. Package `.exe` using `jpackage` (free, JDK built-in):
   - Bundle JRE — no Java needed on target PC
   - Output: `BMS-{version}-setup.exe`
5. Generate `version.json`:
```json
{
  "version": "1.0.1",
  "release_date": "2026-10-11",
  "download_url": "https://github.com/{owner}/{repo}/releases/download/v1.0.1/BMS-1.0.1-setup.exe",
  "release_notes": "- Fixed login bug\n- Added export feature"
}
```
6. Generate `RELEASE_NOTES.md` from git commits since last tag
7. Create GitHub Release — attach `BMS-{version}-setup.exe` and `version.json`

#### In-App Update Checker
- On every launch, before login screen: silently check GitHub latest release API
- Repo URL from `application.properties`: `app.github.api=https://api.github.com/repos/{owner}/{repo}`
- GitHub repo is public — no token needed
- Runs in background JavaFX Task — must NOT block login screen
- Silent failure if no internet

#### Update Banner (Login Screen)
- "নতুন version {x.x.x} পাওয়া গেছে!" + release notes + `Update করুন` button + dismiss
- User can ignore and login normally

#### Update Process
1. Download installer to `%TEMP%\BMS_update\`
2. Show download progress bar on login screen
3. Verify file size (Content-Length header check)
4. Write and launch `updater.bat` via ProcessBuilder (detached):
   - Wait for current app to exit
   - Backup current installation as `BMS_backup_{old_version}`
   - Run new installer silently (`/S` flag)
   - Launch new app
   - If fails: restore from backup automatically
5. App closes itself (`Platform.exit()`)

---

## Security Requirements
- Login screen on app launch
- Password hashed with BCrypt
- All screens protected except Login
- SQL injection prevention via Hibernate parameterized queries
- No secrets in code — all in `application.properties`
- Session object contains: user_id, role, permissions map
- Every screen checks permission before rendering create/edit/delete buttons
- Full audit log for all data changes

---

## UI Rules (JavaFX)
- Sidebar: Dashboard | পণ্য | স্টক | পার্টি | বিক্রয় | ক্রয় | খরচ | অ্যাকাউন্ট | কর্মচারী | রিপোর্ট | সেটিংস
- Cashier role sees limited sidebar (no HR, no reports, no settings)
- Each sidebar button loads its own FXML view
- Language toggle in Settings — switches all UI labels instantly
- Clean minimal design — functional over decorative
- Scene Builder-compatible FXML structure

---

## Deliverables
- Deliver the COMPLETE project as a single downloadable ZIP file
- ZIP must contain every file needed to build and run with zero manual additions
- If a bug fix or change is requested later, deliver only the affected files as separate downloadable files
- For each changed file, state its exact location path before the file content
- Never re-send unchanged files

---

## Constraints
- No Spring Boot — pure JavaFX + Hibernate + HikariCP
- No web-based UI
- No Docker
- No unnecessary features — if not asked for, don't build it
- Every file must be complete and runnable — no placeholder comments
- Minimum dependencies — only what is strictly necessary
- GitHub repo is public — no token needed

---

## One-Command Setup Rule (STRICT)
User should only ever do two things:
1. Fill in `application.properties`
2. Run: `mvn javafx:run`

On startup the app must automatically:
1. Check MySQL connection — if not reachable, show clear error dialog and exit
2. Auto-run schema creation (CREATE TABLE IF NOT EXISTS) for all tables in correct FK dependency order
3. Seed default `app_config` rows if not present
4. Seed default `business_profile` row if not present
5. Check if admin user exists — if NOT: create from `application.properties` credentials
6. Launch the JavaFX login window

No manual SQL. No manual setup. No manual commands.

---

## File Structure Rules (STRICT — NO EXCEPTIONS)
- .fxml → only UI structure, nothing else
- .css → only JavaFX CSS styles, nothing else
- .java → only Java logic, nothing else
- No inline style attributes in .fxml files
- No business logic in .fxml files
- No SQL in Controller or Service layer

## File paths when delivering changed files
- src/main/java/com/eh/bms/controller/
- src/main/java/com/eh/bms/service/
- src/main/java/com/eh/bms/repository/
- src/main/java/com/eh/bms/model/
- src/main/java/com/eh/bms/updater/
- src/main/java/com/eh/bms/util/
- src/main/java/com/eh/bms/config/
- src/main/resources/fxml/
- src/main/resources/css/main.css
- src/main/resources/i18n/messages_bn.properties
- src/main/resources/i18n/messages_en.properties
- src/main/resources/application.properties
- .github/workflows/release.yml
