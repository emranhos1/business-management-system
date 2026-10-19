# How to release a new version

Claude bumps `<version>` in `pom.xml` and writes `release-notes/v<version>.md` (English, then `<!-- bn -->` on its own line, then Bangla; see `v1.0.0.md`).

Then the user pushes: `dev-emon` → `development` → `stage` → `production`.

GitHub → **Actions** runs "Release" (~5–10 min). Then **Releases** gets `BMS-Update-<version>.exe` and its `.sha256`, and the `v<version>` tag is created. Every PC with BMS shows "new version" on the login screen.

- Push to `production` with the same version → nothing is released.
- New version without `v<version>.md` or without `<!-- bn -->` → the build fails.
