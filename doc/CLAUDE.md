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
  - Exception (user decision): account types are a user-managed table `account_types` (Bangla/English name + number rule), seeded with Cash, Bank, Mobile banking.
- Names the user enters for master data (accounts, account types, ...) have a Bangla and an English name, shown by language (`I18n.pick`).

## Build
- Before every build, show a big warning with the current `app.profile` value. Never change the profile.

## Forms and messages (every page)
- Number fields: `InputFilters.digits` / `InputFilters.decimal` (no letters, Bangla digits → 0-9). Text: `InputFilters.maxLength`.
- Validation: `FormValidator`. Check all fields, show every error (red box + message under the field), focus the first.
- Every save/delete: `Dialogs.confirmSave()` / `Dialogs.confirm(...)` first.
- Result and general errors: `Notifier.success` / `Notifier.error` (top bar, 5 s). No status labels on the page.
- Layout: label column 200 px, labels top-aligned (`form-label`); every input fills the second column, same width everywhere.
- Buttons: one class per action — `save-button`, `add-button`, `edit-button`, `delete-button`, `cancel-button` (also `login-button`, `page-button`). All are 140 x 36 px (Login: full card width); keep labels short enough to fit. Their look lives only in the "Buttons" block of `main.css`; change it there to change every such button. Never leave a button unstyled.
- Popup forms: 540 px wide, one field per row (`form-row` + `form-row-label`), Bangla and English names on separate rows. Optional rows hide with no gap.
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
