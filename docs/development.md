# Development

Building, testing and finding your way around the code.

- [Environment](#environment)
- [Dependencies](#dependencies)
- [Building](#building)
- [Tests](#tests)
- [Project layout](#project-layout)
- [Architectural notes](#architectural-notes)
- [Database and migrations](#database-and-migrations)
- [Release builds](#release-builds)

## Environment

| | |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose (BOM 2026.02.01), Material 3 |
| Build | Android Gradle Plugin 9.3.0, Gradle wrapper, KSP |
| Min SDK | 24 (Android 7.0) |
| Compile / target SDK | 37 |
| Java | 11 |

You need Android Studio, or a standalone Android SDK, with **API level 37** installed. Gradle comes
from the wrapper in the repository.

Two project-level settings worth knowing, both in `gradle.properties`:

- **Configuration cache is on.** A change that affects build configuration invalidates it; that is
  normal and the next build stores a fresh entry.
- **`android.disallowKotlinSourceSets=false`** — KSP (for Room) registers its generated sources
  through the `kotlin.sourceSets` DSL, which AGP 9's built-in Kotlin support rejects by default.

## Dependencies

Versions are centralised in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

| Library | Used for |
|---|---|
| Room 2.8.2 | Persistence — Lists, items, per-file review state, webhook history |
| DataStore Preferences 1.1.7 | Settings, custom actions, remembered filters and sorts |
| `androidx.documentfile` 1.1.0 | Storage Access Framework folder access |
| Media3 (ExoPlayer) 1.8.0 | Video playback |
| Coil 3.4.0 (+ `coil-gif`) | Images, and animated GIF/WebP |
| Lifecycle ViewModel / runtime-compose | The single ViewModel and its state collection |

`org.json` is used for webhook payload construction — part of the Android platform, which is why
payload-shape tests are written against the pure helpers rather than the builder.

### Build size, and where it comes from

Measured on the current tree:

| | |
|---|---|
| `app-debug.apk` on disk | **25.2 MB** |
| What is inside it, uncompressed | **75 MB** |
| …of which DEX | **73.9 MB** |
| …of which `classes.dex` alone | **43.4 MB** |
| Compose icon classes compiled in | **21,320** — 4,264 glyphs × 5 styles |
| Compose icons the app actually names | **38** |

The APK file has not grown; its **contents** are what is large, and almost all of that is one
dependency.

**`androidx.compose.material:material-icons-extended` ships every Material icon there is**, and it
is declared as a plain `implementation` dependency, so all of it is compiled in. Compose's
`Icons.Filled.*` accessors are properties on an object in that artifact, so importing the artifact is
what makes `Icons.Filled.Refresh` resolve — there is no per-icon tree-shaking to fall back on,
because the reference is a field read on a class that also holds the other four thousand.

**Nothing shrinks it.** Debug builds never run R8, and the release build type sets
`optimization { enable = false }` in `app/build.gradle.kts`, so even a release APK keeps the whole
set. The builds on the releases page are debug.

**Why a reported size can read much higher than 25 MB.** Any tool that measures the *contents*
rather than the download — an analyzer showing "raw size", a file manager that unpacks the archive,
or an OEM app-info screen that totals the uncompressed footprint — sees the 75 MB figure, not the
25 MB one. On the attached device, `du` over the installed app directory is 24.6 MB, matching the
APK. Worth knowing before treating a size report as growth.

Three ways out, none taken in the v0.3.0 iteration because this is a build-configuration change with
no design content and the round was already carrying a model split:

1. **Turn R8 on for release** (`optimization { enable = true }`, with a keep rule for anything
   reflective). Largest single win, and it is the setting that was switched off.
2. **Replace the extended set with the 38 icons actually used**, either as hand-written
   `ImageVector`s or by dropping to `material-icons-core` and the glyphs it has.
   `grep -rhoE 'Icons\.[A-Za-z.]+\.[A-Za-z]+' app/src/main/java | sort -u` is the complete list.
3. **Ship release-signed builds.** Independent of size, and already a stated gap — the
   [getting started](getting-started.md#install-a-build) page warns that debug builds cannot be
   installed over a future release-signed one.

## Building

```bash
git clone https://github.com/Dgama/Listea.git
cd Listea

# Point the build at your SDK. Gitignored, never committed.
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug        # build the APK
./gradlew installDebug         # build and install on a connected device
./gradlew testDebugUnitTest    # run the unit tests
```

Opening the project in Android Studio and pressing Run works too; Studio writes `local.properties`
for you.

The debug APK lands in `app/build/outputs/apk/debug/`.

## Tests

**561 JVM unit tests**, run with `./gradlew testDebugUnitTest`. No device or emulator needed.

They deliberately target the pure logic rather than the UI, which is why so much of that logic lives
in files with no Android imports. The table is a selection by subject rather than the whole list;
`app/src/test/java/com/example/listea/` and its `data/` and `domain/` subpackages hold all of them.

| Test | Covers |
|---|---|
| `FileArrangeTest` | Filtering and sorting: the unknown rule, calendar-day freshness, every comparator, encode/decode round trips |
| `ListArrangeTest` | A List's items under the same machinery |
| `ReviewRoundTest` | Composing a page's arrangement with *unchecked items only* |
| `QuickReviewQueueTest` | Resolving a folder into a review queue |
| `FolderTypeTest` | None / List / Sublist attribution, subtree progress, and which Todo holds a row in which capacity |
| `ItemIdTest` | Public id format, ordering, fingerprint determinism |
| `WebhookPayloadTest` | Payload shape, `relativePath` derivation, action resolution |
| `WebhookHistoryTest`, `WebhookStatusLabelTest`, `WebhookErrorLabelTest` | History records, status lines, network error wording |
| `SentCheckedFilesTest` | Which files the post-webhook delete offer covers |
| `FileManagementTest` | Delete preview grouping |
| `MediaSaveTest`, `MediaShareTest` | Album destination and naming, share staging and sweeping |
| `MediaZoomTest` | Zoom clamping and pan arbitration |
| `VideoControlsTest` | Transport state |
| `SettingsTest` | Settings encoding, custom action defaults and decode tolerance |
| `SourceFreshnessTest` | List-versus-folder diffing |
| `ImportPlanTest`, `ImportRunTest`, `ImportInvariantTest` | Building Tasks from a folder: what a run plans, what it writes, and what it must not break |
| `CreateTasksConfigTest` | What a Create Tasks run stores — the name, the importer and the folder-tree answer |
| `CatalogTest`, `CatalogScopeTest`, `CatalogSelectionTest`, `TaskNavigationTest` | A Todo's catalog: scopes, selection units, and where a row leads |
| `CreateListFromTasksTest` | Making a List out of Tasks picked from a catalog |
| `SharedTaskTest` | Two Todos over one folder: two Tasks, two decisions, the reads that had assumed one of each, and the claims that say which Todo holds a path as its own Task and which only covers it |
| `ConsentGateViewModelTest`, `FolderListGateTest`, `ConsentTest` | The consent questions asked before an import or a List, driven through the ViewModel and through the DAO |
| `MigrationChainTest`, `MigrationSmokeTest`, `MigrationV12ToV13Test`, `MigrationV13ToV14Test`, `MigrationV15ToV16Test`, `MigrationV16ToV17Test`, `MigrationV17ToV18Test`, `MigrationV18ToV19Test` | Every migration, applied in order against a real database |
| `TodoDissolutionTest`, `ListAfterDissolutionTest`, `HistoryRecoveryTest` | Deleting a Todo: what it takes, what it leaves, what a List says afterwards, and what a later Todo can load from what was left |
| `ContentPolicyTest` | What a changed Task means: the three tiers, the "unchanged means no policy" rule, and every rule by which an Asset counts as changed, missing, or not news |
| `ContentPolicyRunTest` | The same tiers as *shapes of outcome* through real SQL: which of a Task's id, its judgments and its List memberships survive each one |
| `IdentityTest`, `ReviewResponseTest`, `TaskActionTest`, `TemplateRegistryTest` | The `domain/` model: that id types are not interchangeable and never derive from a path, that a response's stored form is canonical, that only one of the three actions is a submission, and what a template version's content hash is built from |
| `ListsDaoTest`, `TaskWriteTest`, `AssetStepTest`, `CurrentStateTest`, `EligibilityTest` | The DAO directly: what a write leaves behind, how Assets step, what "current state" resolves to, and which Tasks a List may take |
| `ReviewSessionTest`, `ReviewSubmissionTest` | A review session's lifetime, and what submitting one records |
| `LegacyItemIdTest`, `LegacyManualCleanupTest` | The two legacy paths: older public ids, and manual items with no file behind them |
| `CheckedFileReaderTest` | Which files count as checked under an Upstream, and which are still on the device |
| `WebhookBodyTest`, `WebhookCompatTest` | The payload's own shape, and the compatibility rules it must not break |

There is one instrumented test (`ExampleInstrumentedTest`), which is the template's and is not load
bearing.

## Project layout

```
app/src/main/java/com/example/listea/
  MainActivity.kt          app shell, top-level navigation, the Upstreams browser
  AppShell.kt              shared scaffolds and the shared spacing scale
  ManagementUi.kt          shared pieces for Upstreams / Todo / Lists / List Detail

  UpstreamsScreen.kt       the Upstreams tab: one card per folder material comes from
  SourcesScreen.kt         the Todo tab: one card per Todo (the file is named for the old word)
  CatalogScreen.kt         a Todo's catalog, and selecting Tasks out of it
  CatalogTaskPreviewScreen.kt  one Task from the catalog, presented for reading
  ListsScreen.kt           the Lists index
  ListDetailScreen.kt      one List: source, progress, items
  ListsViewModel.kt        all list, folder, freshness and webhook operations

  ReviewScreen.kt          full Review, and the shared review session
  QuickReviewScreen.kt     Todo-scoped Review over the same session
  QuickReview.kt           resolving a scope and its catalog rows into a review queue
  ReviewArrange.kt         what a round queues, given a filter and the settings
  FileViewerScreen.kt      the plain gallery
  ListItemViewerScreen.kt  the same, reached from a List

  MediaUi.kt               shared media frame, the item and file control bands, Info sheet
  MediaPreview.kt          the one renderer: images, GIF, video
  VideoControls.kt         the video transport: play, seek, speed, mute
  SwipeCard.kt             the one swipe gesture, and where paging and zoom are arbitrated
  MediaZoom.kt             the pinch, the pan, and how far either may go
  MediaSave.kt             copying a viewed file into the DCIM/Listea gallery album
  MediaShare.kt            staging a viewed file for Android's share chooser

  Catalog.kt               the catalog's containment algebra and viewability classification
  CatalogSelection.kt      what the catalog has picked, as scopes and excluded Tasks
  ContentPolicy.kt         the three tiers, and the pure rule that applies one to a Task
  FileArrange.kt           filtering and sorting, as pure values and one pure function
  FileArrangeUi.kt         the filter and sort headers and dialogs
  FolderListGate.kt        the decision table behind Create List's two confirmation gates
  FolderReader.kt          reading a SAF folder
  FolderScan.kt            walking a subtree, and diffing it against a List
  FolderSource.kt          every read of the user's folders, behind one seam
  FolderListStatus.kt      None / List / Sublist, and per-folder progress
  Importer.kt              the grouping rule: a folder snapshot into planned Tasks

  Settings.kt              AppSettings, custom actions, the DataStore behind them
  SettingsScreen.kt        Settings
  FileManagement.kt        the delete preview, and the deleting itself

  Webhook.kt               payload construction and delivery
  WebhookHistoryScreen.kt  every kept payload, with resend and delete

  data/ItemId.kt           the public id an item carries onto the webhook
  data/CatalogRow.kt       the catalog's row projections, scopes and Todo summaries
  data/ImportEntities.kt   Room entities: sources, task_imports, dissolution, legacy links
  data/ImportRun.kt        the run itself, and what it reports
  data/ListEntities.kt     Room entities, actions, decisions
  data/ListsDao.kt         every query
  data/ListeaDatabase.kt   the database and its migrations
  data/ReviewAsset.kt      one Asset as the review surface reads it, and why it is missing
  data/TaskEntities.kt     Room entities: tasks, assets, task_state, review_events
  data/UpstreamEntities.kt Room entity: upstreams
  domain/                  the review model as plain Kotlin — see below
  ui/theme/                colours, typography, theme
```

### `domain/` — the model with no framework in it

`domain/` holds the review model as plain Kotlin, and it is live rather than aspirational:
`ListsDao`, `ListsViewModel`, `ReviewScreen` and `QuickReviewScreen` all read and write through it.

| File | What it holds |
|---|---|
| `Domain.kt` | `SourceRef`, `AssetKind`, `Task`, `Asset`, `TaskState`, `ListEntry`, `ReviewEvent`, `ReviewEventKind`, `ReviewContext` |
| `Ids.kt` | `TaskId`, `AssetId`, `ListId`, `ListEntryId`, `ReviewEventId`, `TemplateId`, `TemplateVersionRef` |
| `ReviewResponse.kt` | What a person submitted, its canonical stored form, and what counts as completing |
| `TaskAction.kt` | The vocabulary a gesture, a button and a keyboard shortcut all resolve into |
| `Templates.kt` | Asset Viewer against Task Review, and what one version of one says |

Nothing in it imports Room, Compose, SAF, `org.json` or any HTTP shape. That is not purity for its
own sake — it is what lets the identity rules be pinned by tests that owe nothing to Android, and
it is the seam the [cross-platform roadmap item](roadmap.md) would widen.

The identity types are the substantive part: **several** id types rather than one, so passing a
Task id where an Asset id belongs does not compile, and so a Task's identity cannot be derived from
where its file sits. They are UUIDs rather than [public item ids](webhooks.md#item-ids), whose
middle field is a fingerprint of the path under its Upstream — deliberately readable, deliberately
different for two arrivals of one file, and disqualifying for a Task that has to survive a move.
Both schemes stay; the adapter is where one becomes the other.

## Architectural notes

These are descriptions of the code as it stands, not proposals. Changing any of them is a design
decision, not a refactor.

**One ViewModel.** `ListsViewModel` owns list, folder, freshness, settings-writing, file-deletion
and webhook operations. There is no repository layer and no domain-mapping layer. It is large; that
is a known cost, and it is the first thing the [roadmap](roadmap.md) work will have to address.

**Pure logic is kept separable.** `FileArrange.kt`, `ReviewArrange.kt`, `QuickReview.kt`,
`FolderListStatus.kt`, `ItemId.kt`, `Catalog.kt`, `CatalogSelection.kt`, `ContentPolicy.kt`,
`FolderListGate.kt`, `Importer.kt`, the payload helpers in `Webhook.kt`, and the whole of `domain/`
have no Compose and little or no Android dependency. That is what makes 561 JVM tests possible, and
it is the seam the cross-platform roadmap item would widen.

The rule the newest of these follow: **the decision is a pure function, and the DAO acts on its
answer.** `Importer.kt` plans Tasks from a `FolderSnapshot` and writes nothing;
`decideContentDecision` in `ContentPolicy.kt` says what one policy means for one Task's shape;
`FolderListGate.kt` is the decision table behind Create List's two confirmation gates. Each is
pinned by tests that owe nothing to Android, SAF or Room, which is why "which files are one Task"
and "what a changed Task means" are the two questions this codebase can answer precisely.

**State belongs to the Task, not to the List.** Checked, favourite and custom actions are stored in
`task_state`, keyed by `taskId` and cascading from `tasks`; a List stores membership and projects
that state. (Before v0.2.5 the same state was keyed by root plus relative path on a row of its own,
which is why the older columns are still on those tables and still dead.) This is the single most
load-bearing decision in the codebase — see
[Core concepts → Where state lives](core-concepts.md#where-state-lives) — and a great deal of
behaviour is downstream of it.

**An item stores action *ids*, never labels or wire values.** Display names and webhook values live
in settings and are resolved at render and delivery time. Renaming an action is therefore not a
migration.

**Writes are ordered.** Webhook payloads are read under the same lock the item writes take, so a
payload built off the back of a swipe cannot be missing the item that triggered it.

**Review queues are snapshots.** A round's items and order are fixed when it opens, by id, and
re-projected against later database emissions rather than re-derived. See `rememberRoundQueue` in
`ReviewScreen.kt`.

**Rotation is handled in place.** `MainActivity` declares `configChanges` for orientation and size
so an ExoPlayer instance and a decoded image survive a rotate. It deliberately does not list
`uiMode`: a light/dark switch should still recreate, because `enableEdgeToEdge()` picks its
system-bar scrims in `onCreate`.

**Webhook diagnostics.** `Webhook.kt` has a `DIAGNOSTICS` flag that logs delivery timing and
failures to logcat under the tag `ListeaWebhook`. It is currently on, changes nothing about what is
sent or stored, and is marked in the source as temporary.

## Database and migrations

Room, schema **version 19**, database file `listea.db`, and `exportSchema` is on. Sixteen entities
across four files:

| File | Entities |
|---|---|
| `ListEntities.kt` | `ListEntity`, `ListItemEntity`, `FileReviewStateEntity`, `WebhookRecordEntity` |
| `TaskEntities.kt` | `TaskEntity`, `AssetEntity`, `TaskStateEntity`, `ReviewEventEntity`, `LegacyItemIdEntity`, `LegacyManualTaskEntity` |
| `ImportEntities.kt` | `SourceEntity`, `TaskImportEntity`, `DissolvedTaskStateEntity`, `LegacySourceTaskEntity`, `LegacyTaskIdEntity` |
| `UpstreamEntities.kt` | `UpstreamEntity` |

**`SourceEntity` is a Todo, not a Source.** The model name is the pre-v0.3.0 one, kept deliberately
rather than renamed — see the note on `TopLevelDestination` in `AppShell.kt`, and
[the naming conflict](ARCHITECTURE.md#file-source-and-upstream) it leaves open. Read every `source`
identifier in `data/` as "the Todo this run wrote".

**Every version step from 1 has a written migration** and they are all registered; there is no
destructive fallback. Upgrading in place is expected to keep existing data.

Steps worth reading for context: **9 → 10** introduced public item ids and backfilled them,
**11 → 12** rebuilt the item tables when the two fixed custom-action columns became a list of action
ids, **14 → 15** added `sources.keepTree`, defaulting to the tree `catalogAtScope` already drew, and
**15 → 16** split the Upstream out of the Source — a new `upstreams` table carrying one row per
grant, and `sources` narrowed to mean a Todo. Its data step is the readable one: every Source a run
had touched became an Upstream, and a Source no run had touched stopped being a Todo unless a Task
still named it. See [v0.3.0](iterations/v0.3.0.md#3-schema). **16 → 17** relaxed two `UNIQUE`
indices, which is the whole of what it does: `sources` may now hold any number of rows at one path,
and `assets` is unique on `(taskId, rootUri, relativePath)` instead of `(rootUri, relativePath)`.
No table is rebuilt and no row is touched, so a mistake in it would show up as a lost index rather
than as lost data — which is why `MigrationV16ToV17Test` checks both halves, that nothing moved and
that the rules really are relaxed. See [v0.4.0](iterations/v0.4.0.md#schema).

**17 → 18** adds `dissolved_task_state`, the snapshot a deleted Todo leaves behind, along with the
two columns a List uses to say it lost rows (`lastDissolvedCount`, `lastDissolvedAt`) and
`sources.keepStateOnDelete`. Nothing is rewritten and no row is touched: the table arrives empty,
because this step cannot invent a history for a Todo that was never deleted, and both note columns
are null, which is the only honest value for a List nothing has happened to. The per-Todo default
is **off**, which is the behaviour-preserving answer rather than the friendly one — before this
release nothing could delete a Todo at all, so a Todo that started keeping a snapshot would have
begun doing something the user never asked for. See
[v0.4.0](iterations/v0.4.0.md#step-2--lifecycle).

**18 → 19** adds the two standings a Todo holds (`sources.contentPolicy`, `sources.followUpstream`)
and the third `lists` note column, `lastDissolvedReason`. Three `ALTER`s and nothing else: no table
is rebuilt and no row is touched. Both Todo columns arrive at the values an untouched install
already behaved with — `keep` and off — so an upgraded Todo behaves exactly as it did, and
`lastDissolvedReason` arrives holding the delete's own key, because a deletion is the only
operation that could have written a note before this version. Leaving it null or defaulting it to
the drop's key would make every List that already had a note start reporting a Task replacement
that never happened. See [v0.4.0](iterations/v0.4.0.md#step-2b--the-two-per-import-policies).

The schema is **not settled** — see [Project status](../README.md#project-status). Adding a
migration is expected of any change to an entity.

## Release builds

The `release` build type currently has `optimization { enable = false }`, so R8 shrinking and
obfuscation are off. `app/src/main/keepRules/rules.keep` exists but holds only commented template
rules.

Turning optimisation on would need keep rules verified for Room, Compose and Media3, and the result
tested on a device. Until someone does that, published builds are debug builds — see the
[releases page](https://github.com/Dgama/Listea/releases).

Signing is not configured in the repository, and `*.jks`, `*.keystore` and `keystore.properties` are
gitignored. A keystore and its passwords are credentials, not source.

---

Next: [Roadmap](roadmap.md) for where the architecture is going, or
[Core concepts](core-concepts.md) for the model the code implements.
