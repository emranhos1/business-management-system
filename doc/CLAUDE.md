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

## Build
- Before every build, show a big warning with the current `app.profile` value. Never change the profile.

## Database indexes
- Index only columns important for reports.
- Before creating each table, list proposed index columns and get user approval.

## Task workflow
- Work in small tasks from `doc/progress.md`.
- After a task: test it, mark `[T]`. Mark `✅` only after the user approves. When all tasks in a stage are `✅`, add ✅ after the stage title.
- New ideas go to the "New Ideas" section, numbered, split into small tasks.
