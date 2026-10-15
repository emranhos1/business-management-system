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
  - Exception (user decision): account types are a user-managed table `account_types` (Bangla/English name + number rule), seeded with Cash, Bank, Mobile banking.
- Accounts module: only admin and manager (user decision). Cashier, and any staff/employee login or new role added later (e.g. N2), gets no Accounts permission.
- One-time fixes to existing data go in `DataSeeder.migrateData()`, numbered by `data_version` in app_config (never re-run, so admin edits stay).
- Pack sizes (user decision): the same goods in different pack sizes (e.g. rice in 50 / 25 / 20 kg bags) are separate products, each with its own stock, price and conversion. No product variants / multiple packs per product. Product names may repeat; only the same name + same unit, secondary unit and conversion is a duplicate. Wherever a product is picked or listed (stock, sales, purchases), show its conversion next to the name so same-name products can be told apart.
- Ledgers: party balance = opening (+ receivable / - payable) + debit - credit; debit = what we gave (goods sold, money paid), credit = what we got. Screens say দিলাম / পেলাম and "৳X পাবো" / "৳X দেবো" (`PartyText`). Money moves write through `AccountLedgerService.post` / `PartyLedgerService.post`; lock order: party row first, then account rows (by id).
- Names the user enters for master data (accounts, account types, ...) have a Bangla and an English name, shown by language (`I18n.pick`).

## Build
- Before every build, show a big warning with the current `app.profile` value. Never change the profile.

## Forms and messages (every page)
- Number fields: `InputFilters.digits` / `InputFilters.decimal` (no letters, Bangla digits → 0-9). Text: `InputFilters.maxLength`.
- Validation: `FormValidator`. Check all fields, show every error (red box + message under the field), focus the first.
- Every save/delete: `Dialogs.confirmSave()` / `Dialogs.confirm(...)` first. Deletes use `Dialogs.confirmDelete(...)` (red Yes button).
- Result and general errors: `Notifier.success` / `Notifier.error` (top bar, 5 s). No status labels on the page.
- Layout: label column 200 px, labels top-aligned (`form-label`); every input fills the second column, same width everywhere.
- Buttons: one class per action — `save-button`, `add-button`, `edit-button`, `delete-button`, `cancel-button` (red), `view-button` (opens a read-only window, e.g. a ledger) (also `login-button`, `page-button`, `no-button` = grey No in confirm dialogs, `icon-button` = action on a table row via `TableHelper.actionsColumn`, exempt from 140 x 36). All are 140 x 36 px (Login: full card width); keep labels short enough to fit. Their look lives only in the "Buttons" block of `main.css`; change it there to change every such button. Never leave a button unstyled.
- Popup forms: 540 px wide, one field per row (`form-row` + `form-row-label`), Bangla and English names on separate rows. Optional rows hide with no gap.
- Every popup (forms, ledgers, Yes/No) opens through `WindowManager.dialog`: no window title bar (user decision); the full title stands turned 90° on a dark strip on the left (drag it to move). Every popup needs its own close/cancel button; Esc also closes. Yes/No questions go through `Dialogs`, never `Alert`.
- Pages with a main part and a side part (e.g. Accounts + Account types): root `TwoColumnLayout` (62/38; stacks the side part under the main part on a narrow window, the page scrolls). Toolbars are `FlowPane` so buttons wrap instead of pushing the page wider.
- Per-row actions (ledger, money in/out, inactive, ...): icon buttons in one column via `TableHelper.actionsColumn` (`icon-button` + colour class, SVG icons from `Icons`, tooltip says what it does), edit first. The toolbar keeps only "New ...". Delete sits in the edit form, shown only when the record can be deleted (e.g. a party with no history and earlier balance 0).
- Parties are never deleted once used; they are made inactive (`parties.status`) and listed in a second table below. Pickers (sale, purchase, ...) show active parties only. A party with money due cannot be made inactive.
- Table column widths fit their text (`TableHelper.fitColumns`, run by `PaginatedTable`); never set column widths by hand.
- Table columns whose text can be long (ledger details, notes): `TableHelper.longTextColumn`, which shows the full text in a tooltip on hover.
- Mobile number: exactly 11 digits, any start (no prefix rule), required where asked.
- Dates: `DateUtil` only (Bangla digits in Bangla mode via `I18n.digits`). Files the user adds: under `AppPaths.storageDir()`, path stored relative (`AppPaths.toStored`).
- Every new text key goes into both `messages_bn` and `messages_en`.

## Database indexes
- Index only columns important for reports.
- Before creating each table, list proposed index columns and get user approval.

## Task workflow
- Work in small tasks from `doc/progress.md`.
- After a task: test it, mark `[T]`. Mark `✅` only after the user approves. When all tasks in a stage are `✅`, add ✅ after the stage title.
- New ideas go to the "New Ideas" section, numbered, split into small tasks.
