# BMS Project Rules

## Start of every session
- Read `doc/progress.md` (task list and status) and `doc/bms-prompt.md` (full spec).

## Decisions that override `doc/bms-prompt.md`
- Java: JDK 21 (installed). JavaFX 21.x (JavaFX 25+ needs JDK 23).
- Config: YAML, not `application.properties`.
  - `application.yml`: active profile (`app.profile`), app name, version, GitHub API.
  - `application-{dev,stage,prod}.yml`: DB, pool, admin.
  - All profiles use the same DB name; the user manages profiles.
- Status/type columns: Java enums stored as VARCHAR. No status lookup tables.
  - Every enum column is listed in `DataSeeder.ENUM_COLUMNS`: Hibernate's CHECK on it never updates, so a new enum value fails on insert until startup drops the outdated CHECK.
  - hbm2ddl never resizes a column: a size change needs a guarded step in `DataSeeder.migrateColumnSizes` (`SchemaRepository.widenDecimal`). Stock cost (`stock_ledger.unit_price`, `avg_cost_after`) is DECIMAL(16,4); money shown is rounded to 2.
  - Exception (user decision): account types are a user-managed table `account_types` (Bangla/English name + number rule), seeded with Cash, Bank, Mobile banking.
- Accounts module: only admin and manager (user decision). Cashier, and any staff/employee login or new role added later (e.g. N2), gets no Accounts permission.
- One-time fixes to existing data go in `DataSeeder.migrateData()`, numbered by `data_version` in app_config (never re-run, so admin edits stay).
- Pack sizes (user decision): the same goods in different pack sizes (e.g. rice in 50 / 25 / 20 kg bags) are separate products, each with its own stock, price and conversion. No product variants / multiple packs per product. Product names may repeat; only the same name + same unit, secondary unit and conversion is a duplicate. Wherever a product is picked or listed (stock, sales, purchases), show its conversion next to the name so same-name products can be told apart.
- Ledgers: party balance = opening (+ receivable / - payable) + debit - credit; debit = what we gave (goods sold, money paid), credit = what we got. Screens say দিলাম / পেলাম and "৳X পাবো" / "৳X দেবো" (`PartyText`). Money moves write through `AccountLedgerService.post` / `PartyLedgerService.post`; lock order: party row, then the sale/transaction row, then product rows (by id), then account rows (by id).
- Sales (`SaleService`): memo no. per type from `MAX(memo_no) FOR UPDATE` + unique index; items keep `main_quantity` (stock unit) so later conversion edits never change past sales; a walk-in customer (no party) cannot owe; returns credit the party, refunds optional. Wholesale / retail price is chosen per sale ("দামের ধরন", starts at retail); parties have no wholesale/retail field (user decision; `parties.party_type` is left unused). A party may pay more than the bill: the sale gets only the bill, the extra is a plain party receipt (source PARTY); a walk-in cannot overpay. One payment row per account.
- Purchases (`PurchaseService`, user decisions): no VAT, bill discount only, shared over the lines so each line's stock cost is what was really paid; a cash purchase without a supplier must be paid in full; optional supplier memo no. (`transactions.supplier_memo_no`, searchable); each purchase row has a "pay the due" button. A purchase return leaves stock at its purchase cost and the average cost is worked out again (`StockLedgerService.post`, PURCHASE_RETURN with a price). Money out never goes above what the account holds on that date. Sales and purchases share `TransactionRepository` (one per type); purchase lines in the party and account ledgers say "ক্রয় মেমো #n" so they are not taken for a sale's memo.
- Expenses (`ExpenseService`, user decisions): categories are called "খাত" on screen (Bangla + English name, type চলতি / স্থায়ী / অন্যান্য); five common ones are seeded when the table is empty. Each expense is one account DEBIT with source EXPENSE (`reference_id` = the expense). An expense may be edited or deleted (users with that permission); its ledger entry is then replaced or removed in the same transaction, the only ledger entries ever changed. A category with expenses cannot be deleted.
- Date range filters use `DateRangePicker` (one field, first and last day on one calendar), never two DatePickers. Every filter bar has a clear button.
- No IService interfaces (user decision, overrides bms-prompt.md rules 5/6): controllers use the concrete services.
- Names the user enters for master data (accounts, account types, ...) have a Bangla and an English name, shown by language (`I18n.pick`).

## Dev login skip
- `dev.auto-login: <username>` in `application-dev.yml` opens the app logged in as that user (`AuthService.devAutoLogin`). Works only when `app.profile` is `dev`; blank = login screen. Releases must use `prod` (18.6).

## Build
- Before every build, show a big warning with the current `app.profile` value. Never change the profile.

## Forms and messages (every page)
- Number fields: `InputFilters.digits` / `InputFilters.decimal` (no letters, Bangla digits → 0-9). Text: `InputFilters.maxLength`.
- Validation: `FormValidator`. Check all fields, show every error (red box + message under the field), focus the first.
- A hint/error label whose text is set directly takes no space while empty (`FormValidator.hideWhenEmpty`); no blank gaps under fields.
- Every save/delete: `Dialogs.confirmSave()` / `Dialogs.confirm(...)` first. Deletes use `Dialogs.confirmDelete(...)` (red Yes button).
- Result and general errors: `Notifier.success` / `Notifier.error` (top bar, 5 s). No status labels on the page.
- Layout: label column 200 px, labels top-aligned (`form-label`, all bold; add `required` for a must-fill field, which shows a red asterisk — never mark it in the text); every input fills the second column, same width everywhere. Exception: form cards side by side on one page (Settings) size the label column to their longest label.
- Buttons: one class per action — `save-button`, `add-button`, `edit-button`, `delete-button`, `cancel-button` (red), `view-button` (opens a read-only window, e.g. a ledger) (also `login-button`, `page-button`, `no-button` = grey No in confirm dialogs, `icon-button` = action on a table row via `TableHelper.actionsColumn`, exempt from 140 x 36). All are 36 px high and at least 140 px wide; a longer label makes the button wider, never cut (Login: full card width). Keep labels short. Their look lives only in the "Buttons" block of `main.css`; change it there to change every such button. Never leave a button unstyled.
- Popup forms: 540 px wide, one field per row (`form-row` + `form-row-label`), Bangla and English names on separate rows. Optional rows hide with no gap.
- Every popup (forms, ledgers, Yes/No) opens through `WindowManager.dialog`: no window title bar (user decision); the full title stands turned 90° on a dark strip on the left (drag it to move). Every popup needs its own close/cancel button; Esc also closes. Yes/No questions go through `Dialogs`, never `Alert`.
- Pages with a main part and a side part (e.g. Accounts + Account types, Settings: business + app cards): root `TwoColumnLayout` (62/38, or `mainPercent="50"` for two equal parts; stacks the side part under the main part on a narrow window or when a part would get less than its own minimum width, the page scrolls). A page title bar (title left, search + buttons right, user decision) is an `HBox` with a growing spacer and a search that can shrink (`minWidth`); a filter bar (several filters) is a `FlowPane` so it wraps.
- Per-row actions (ledger, money in/out, inactive, ...): icon buttons in one column via `TableHelper.actionsColumn` (`icon-button` + colour class, SVG icons from `Icons`, tooltip says what it does), edit first. The toolbar keeps only "New ..." and page-wide actions (e.g. Transfer). Delete sits in the edit form, shown only when the record can be deleted (e.g. a party with no history and earlier balance 0).
- Parties are never deleted once used; they are made inactive (`parties.status`) and listed in a second table below. Pickers (sale, purchase, ...) show active parties only. A party with money due cannot be made inactive.
- Table column widths fit their text (`TableHelper.fitColumns`, run by `PaginatedTable`); never set column widths by hand.
- Double-click on a row opens its view (ledger / details, same as the "দেখুন" button), never the edit form; a table with no view button has no double-click.
- Every table row shows its full data in a tooltip while hovered (`PaginatedTable` → `TableHelper.installRows`; default "heading: value" per column, or `TableHelper.rowTooltip` for more, e.g. products). So lists show only the main columns. Row colours via `TableHelper.rowStyle`, never a custom row factory.
- List popups (ledgers, sale details) get their size from `WindowManager.sizeListWindow` (capped to the screen; the content fills that height so the buttons under the table stay in sight, and scrolls only if it cannot shrink enough); popups cannot be resized.
- Money or stock going out on a past date is checked against the lowest balance from that date on (`AccountLedgerService.availableOn`, `StockLedgerService.availableOn`), never today’s balance only.
- Popups taller than the screen scroll (`WindowManager.dialog`); forms are never squeezed. A form's `dialog-buttons` row is moved under the scroll, so it is always in sight; a round down-arrow shows in its own strip under the scrolling part (never over the content) while more is hidden below.
- A page whose root is tabs uses `PageTabs`, not `TabPane` (a TabPane hides its tab's min height, so the page is cut off instead of scrolling).
- Mobile number: exactly 11 digits, any start (no prefix rule), required where asked.
- Dates and times: `DateUtil` only (`date_format`, `time_format` 12/24-hour; Bangla digits in Bangla mode via `I18n.digits`). Files the user adds: under `AppPaths.storageDir()`, path stored relative (`AppPaths.toStored`).
- Every new text key goes into both `messages_bn` and `messages_en`.
- Texts are one short, meaningful sentence; no long explanations (user decision). English texts use a curly ’, never a straight apostrophe (MessageFormat drops it).
- A page with input a reload would lose registers with `UnsavedWork`; the language switch then asks Save / Don’t save / Cancel (`Dialogs.askSave`).

## Database indexes
- Index only columns important for reports.
- Before creating each table, list proposed index columns and get user approval.

## Task workflow
- Work in small tasks from `doc/progress.md`.
- After a task: test it, mark `[T]`. Mark `✅` only after the user approves. When all tasks in a stage are `✅`, add ✅ after the stage title.
- New ideas go to the "New Ideas" section, numbered, split into small tasks.
