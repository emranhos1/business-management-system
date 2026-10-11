# Business Management System (BMS)

A JavaFX desktop application for Bangladeshi SME trading, wholesale and retail businesses.
It covers stock, sales, purchases, customer/supplier dues, expenses, accounts and payroll.
The UI is in Bangla and English.

> Status: in development. See [doc/progress.md](doc/progress.md).

## Tech Stack
- Java 21 (JDK)
- JavaFX 21
- Hibernate 7 + HikariCP (no Spring Boot)
- MySQL 8
- Maven

## Requirements
- JDK 21
- Maven 3.9+
- MySQL 8 running on the same machine

## Setup
1. Open `src/main/resources/application.yml` and set the active profile:
   ```yaml
   app:
     profile: dev   # dev | stage | prod
   ```
2. Open `src/main/resources/application-{profile}.yml` and set the database and admin values:
   ```yaml
   db:
     url: jdbc:mysql://localhost:3306/bms?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Dhaka
     username: root
     password: <your-db-password>
   admin:
     name: Administrator
     username: admin
     password: <admin-password>
   ```
   The database `bms` is created automatically if it does not exist.

## Run
```bash
mvn javafx:run
```
If MySQL is not reachable, the app shows an error dialog and exits.

## Project Structure
```
src/main/java/com/eh/bms/
  config/      YAML config loader, database (HikariCP + Hibernate)
  controller/  JavaFX controllers
  service/     Business logic
  repository/  Database access
  model/       JPA entities and enums
  updater/     Auto-update
  util/        Helpers (transactions, i18n)
src/main/resources/
  application*.yml   Config per profile
  css/               Styles
  i18n/              Bangla and English texts
doc/
  bms-prompt.md      Full specification
  progress.md        Task list and status
  CLAUDE.md          Project rules for Claude Code
```

## Versioning
The version in `pom.xml` is the single source of truth. It is copied into `application.yml` at build time.
