# FitTrack — personal workout, nutrition & body-progress tracker (Android)

Offline-first native Android app (Kotlin · Jetpack Compose · Material 3 · Room/SQLite).
No account, no internet permission, no ads, no analytics. Everything is stored on the phone.

> **Build status:** this source was written in an environment that had **no Android SDK, no Gradle,
> no Kotlin compiler and no network**, so it has **not been compiled, run, or tested yet**.
> See “Verification status” at the bottom. Expect to fix a few compile errors on the first build.

## What it does

| Area | Features |
|---|---|
| **Projects** | Unlimited projects (Lean Bulk, Cut, …). Create / edit / duplicate (settings only) / archive / restore / delete (typed confirmation). Start/end date, goal, notes, per-project kcal/protein/carbs/fat/fibre targets, weekly workout goal, units (kg/lb, cm/in), optional height. Active project switcher on Home. Per-project JSON export. |
| **Workouts** | Start empty, from a routine, repeat a past workout, or log a past workout. Auto-saved on every keystroke; unfinished workout resumes after closing the app. Warm-up/working sets, weight × reps, bodyweight (+/− weight), duration and distance exercises, RPE + set notes, supersets, reorder, replace, duplicate, undo on delete, “previous” values beside each set (tap to copy), rest timer (±15 s, pause, reset, default in Settings, per-exercise override), finish summary with PRs. Completed workouts stay fully editable (sets, reps, date, duration, notes, add missed exercises/sets). |
| **Exercises** | 59 built-in exercises + unlimited custom ones (primary/secondary muscle, equipment, category, tracking type, notes). Used exercises are archived rather than deleted so history is never broken. Per-exercise stats can be switched off. |
| **Statistics** | Workouts total / 30 days / per week, duration trend, most-performed exercises, per-exercise charts: estimated 1RM (**Epley**, sets of 1–12 reps only), heaviest set, reps, volume; personal records. Warm-ups excluded; PRs only computed for exercise types where they make sense. |
| **Nutrition** | Date-based diary, customisable meals, quantity in g / ml / servings, live recalculation, quick-add, manual correction per entry, duplicate / move / delete (undo). Daily summary vs. project targets. |
| **Foods & recipes** | Own food library (per 100 g or 100 ml, serving sizes, fibre/sugar, brand, category, notes, favourites, recents, search, duplicate). Recipes / meal templates with servings, scaling and per-serving nutrition. **Diary entries store a snapshot** of their nutrition, so editing a food never rewrites history. |
| **Body** | Weight entries (any days, notes, edit/delete, trend chart, change since first entry), optional height, any subset of measurements (13 built-in + body-fat % + custom types) entered on any day; missing values are never zero. Everything is per project. |
| **Data** | Full JSON backup, per-project JSON backup, restore (Merge = idempotent by stable IDs, or Replace-all with automatic in-app safety copy), validation with readable errors, backup version field, CSV export (workouts, food, weight, measurements; one or all projects; date range) via Android’s file picker. |
| **UX** | Bottom navigation (Home · Workouts · Nutrition · Progress · Projects), light/dark/system theme, empty states, confirmation dialogs, snackbars with Undo. No demo data is ever inserted. |

## Architecture

```
app/src/main/java/com/personal/fittrack/
  domain/   Pure Kotlin: units, dates, nutrition maths, 1RM / PR statistics   (JVM-testable)
  data/     Room entities, DAOs, FitDatabase (+ migrations), repositories,
            BackupCodec (pure), BackupRepository, CsvExporter, Prefs, Seeds, AppContainer
  ui/       Compose screens + ViewModels (StateFlow), shared components, charts, theme
```
* **Database** (`FitDatabase`, v1, schema exported to `app/schemas/`): projects, exercises, routines,
  routine_exercises, workouts, workout_exercises, workout_sets, foods, food_entries, recipes,
  recipe_ingredients, weight_entries, measurement_defs, measurement_entries.
  UUID string primary keys, foreign keys with indices on `(projectId, date)`, ISO local dates (`yyyy-MM-dd`) for
  daily logs, epoch-millis timestamps, kg / cm / metres / seconds storage with display conversion.
* **Project ownership:** every *logged* table (workouts, food_entries, weight_entries, measurement_entries) has a
  `projectId` FK with `ON DELETE CASCADE`. Catalog tables (exercises, foods, recipes, routines, measurement types)
  are shared. Queries always filter by project.
* **Migrations:** `ALL_MIGRATIONS` in `FitDatabase.kt` (empty at v1). `fallbackToDestructiveMigration` is never used.
  Add one `Migration(n, n+1)` per schema bump and test with `MigrationTests`.
* **No history cap:** lists page via “Load more” (increasing SQL `LIMIT`); aggregates use SQL; nothing is truncated.
  (Charts show a recent window — e.g. last 12 active weeks / 50 workout durations / 30 home weight points — display only.)
* **Backup format:** `BackupFile` JSON (`format`, `backupVersion`, `schemaVersion`, `scope`, all tables, preferences).
  Merge uses `INSERT OR IGNORE` on stable IDs, so importing the same file twice adds nothing.
* Android cloud auto-backup is **disabled** (`allowBackup=false`) so no data leaves the phone implicitly.
  Use the in-app backup and copy the file somewhere safe — uninstalling the app deletes its data.

## Building the APK

Requirements: JDK 17, Android SDK (platform 34), Gradle 8.9 (or Android Studio Koala+).

**Android Studio (easiest):** open this folder → let it sync → *Build ▸ Build APK(s)*, or run on a device.
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

**Command line:** `gradle wrapper --gradle-version 8.9` once (the wrapper JAR could not be generated offline),
then `./gradlew :app:assembleDebug` (or `:app:assembleRelease` — signed with the debug key for personal use).

**No local setup — GitHub Actions:** push this folder to a (private) GitHub repo; the included workflow
`.github/workflows/build-apk.yml` runs the unit tests and uploads `app-debug.apk` as a build artifact
(Actions ▸ latest run ▸ Artifacts).

## Installing on your phone
1. Copy `app-debug.apk` to the phone (USB, Drive, Telegram “saved messages”, …).
2. Open it from the Files app. Android asks to allow “Install unknown apps” for that app — allow it once.
3. Tap **Install**, then **Open**. (Or with USB debugging: `adb install -r app-debug.apk`.)
Updating later with a newer APK built from the same source keeps all data (same package, same debug key).
**Keep the same signing key**, otherwise Android requires uninstalling first (which deletes data) — make a backup before.

## Tests
* `app/src/test` (JVM, run with `./gradlew :app:testDebugUnitTest`): nutrition scaling & rounding, unit conversion,
  1RM/volume/PR logic, date handling, backup encode/parse/validate (corrupt, empty, wrong format, newer version, orphans).
* `app/src/androidTest` (device/emulator, `./gradlew :app:connectedDebugAndroidTest`): project separation, workout
  create/edit-after-completion, custom exercises, food scaling & totals, history immutability when a food changes,
  target changes, recipes, weight/measurements with missing values, routines, project deletion, full & project backup
  round-trips, idempotent re-import, rejected inconsistent backups, CSV, close/reopen persistence, 30 000-entry
  dataset, Room migration harness.

## Known limitations
* Not yet compiled or run (see below). No Compose UI tests; UI behaviour has only been desk-checked.
* Reordering uses up/down controls, not drag-and-drop. Supersets link an exercise with the next one.
* Routines are shared across projects (templates); logged data is not.
* A recipe is logged as one diary entry (per-serving snapshot), not one entry per ingredient.
* Rest timer is in-app only (no notification while the app is closed).
* Charts are simple (no tooltips). Distances use km, or miles when the project’s weight unit is lb.
* No built-in food database (by design: fully manual/offline). The built-in exercise list can be edited/archived.
* Release build is signed with the debug key (fine for personal installs, not for Play Store).

## Verification status (honest report)
| Item | Status |
|---|---|
| Source files written (Kotlin ~5,500 lines incl. tests, Gradle, manifest, resources, CI workflow) | ✅ done |
| Static bracket/structure check of every `.kt` file | ✅ passed |
| Manual desk-check for API/signature mismatches | ✅ done (a few defects found and fixed) |
| Gradle build / compilation | ❌ **not run** — no Android SDK, Gradle, Kotlin compiler or network in the authoring environment |
| Unit tests | ❌ **not run** (written, need `./gradlew :app:testDebugUnitTest`) |
| Instrumented/Room tests | ❌ **not run** (need an emulator or device) |
| APK | ❌ **not built** — none exists. Build it with one of the routes above |
| Run on a device/emulator | ❌ not done |
