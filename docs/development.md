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

**The release names in these docs are the packaged version.** Prose here, in the release notes and in
`docs/iterations/` refers to rounds as v0.5.6 and back, and since 2026-10-11 `app/build.gradle.kts`
declares the same name: `versionName = "0.5.6"`, with `versionCode` derived from that one literal so
it cannot fall behind it. Nothing in the app reads either value, so they never disagree on a screen
— but a build handed to someone now says which round produced it.

Two project-level settings worth knowing, both in `gradle.properties`:

- **Configuration cache is on.** A change that affects build configuration invalidates it; that is
  normal and the next build stores a fresh entry.
- **`android.disallowKotlinSourceSets=false`** — KSP (for Room) registers its generated sources
  through the `kotlin.sourceSets` DSL, which AGP 9's built-in Kotlin support rejects by default.

## Dependencies

Versions are centralised in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

| Library | Used for |
|---|---|
| Room 2.8.2 | Persistence — Lists, items, per-file review state, templates, export history |
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
| `app-debug.apk` on disk | **25.7 MB** — 24.5 MiB, 25,696,704 bytes |
| What is inside it, uncompressed | **75.8 MB** |
| …of which DEX | **74.7 MB** |
| …of which `classes.dex` alone | **43.4 MB** |
| Compose icon classes compiled in | **21,320** — 4,264 glyphs × 5 styles |
| Compose icons the app actually names | **43** |

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
2. **Replace the extended set with the 43 icons actually used**, either as hand-written
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

**815 JVM unit tests**, in 88 test classes, run with `./gradlew testDebugUnitTest`. No device or
emulator needed.

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
| `MigrationChainTest`, `MigrationSmokeTest`, `MigrationV12ToV13Test`, `MigrationV13ToV14Test`, `MigrationV15ToV16Test`, `MigrationV16ToV17Test`, `MigrationV17ToV18Test`, `MigrationV18ToV19Test`, `MigrationV19ToV20Test`, `MigrationV20ToV21Test`, `MigrationV21ToV22Test`, `MigrationV22ToV23Test`, `MigrationV23ToV24Test`, `MigrationV24ToV25Test` | Every migration, applied in order against a real database |
| `TodoDissolutionTest`, `ListAfterDissolutionTest`, `HistoryRecoveryTest` | Deleting a Todo: what it takes, what it leaves, what a List says afterwards, and what a later Todo can load from what was left |
| `ContentPolicyTest` | What a changed Task means: the three tiers, the "unchanged means no policy" rule, and every rule by which an Asset counts as changed, missing, or not news |
| `ContentPolicyRunTest` | The same tiers as *shapes of outcome* through real SQL: which of a Task's id, its judgments and its List memberships survive each one |
| `IdentityTest`, `ReviewResponseTest`, `TaskActionTest`, `ExportChoiceTest`, `TemplateRegistryTest`, `TemplateButtonTest`, `TemplateScopeTest` | The `domain/` model: that id types are not interchangeable and never derive from a path, that a response's stored form is canonical, that only one of the three actions is a submission, that an export choice is one value and never a set, and what a template version's content hash is built from |
| `ListsDaoTest`, `TaskWriteTest`, `AssetStepTest`, `CurrentStateTest`, `EligibilityTest` | The DAO directly: what a write leaves behind, how Assets step, what "current state" resolves to, and which Tasks a List may take |
| `ReviewSessionTest`, `ReviewSubmissionTest` | A review session's lifetime, and what submitting one records |
| `LegacyItemIdTest`, `LegacyManualCleanupTest` | The two legacy paths: older public ids, and manual items with no file behind them |
| `CheckedFileReaderTest` | Which files count as checked under an Upstream, and which are still on the device |
| `WebhookBodyTest`, `WebhookCompatTest` | The payload's own shape, and the compatibility rules it must not break |
| `ExportDispatchTest`, `ExportRoutingTest`, `ExporterDeliveryTest`, `ExportQuietRoundTest`, `TxtExportTest` | The export split: which kind a surface resolves to, how a round is cut into per-Todo payloads, where delivery runs, the round that is deliberately quiet, and the text file's own shape |
| `TemplateStoreTest`, `TemplateSeedTest`, `TemplateAttachmentTest`, `TemplateMatchAfterRecoveryTest` | Templates as rows: the built-in every install is seeded with, that an edit appends a version and nothing can revise one, and what a Task is attached to when the template has moved on |
| `AssetPairResolutionTest`, `PerAssetRecordTest`, `AssetControlRunTest`, `DissolvedRecordsTest`, `HistoryRecordsTest`, `FolderRoundRowTest`, `ListCompletionTest` | The per-file record: which control governs which file of a multi-file Task, that a mark belongs to one file, what a dissolution writes down and a recovery reads back, and a List's completion flag against its own rows |
| `AndroidFactoryTest` | That `ListsViewModel` can still be built through Android's real `ViewModelProvider` factory — the one test that runs under Robolectric, which is what lets it use the framework's own factory rather than a stand-in |

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
  ListsViewModel.kt        all list, folder, freshness and export operations

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

  TemplateListScreen.kt    every template, newest name first, and the way into one
  TemplateDetailScreen.kt  one template: its name, its scope, its buttons, and the draft Update commits
  TemplateResolver.kt      what a Todo or a Task is attached to, resolved in the order it names

  Exporters.kt             what an export is — the payload, and the renderer/destination seam
  ExportsScreen.kt         the export manager: the three export-wide switches, and a page per method
  ExportHistoryScreen.kt   every export performed, what became of it, and what you can do about one
  Webhook.kt               payload construction and webhook delivery

  data/ItemId.kt           the public id an item carries onto the webhook
  data/CatalogRow.kt       the catalog's row projections, scopes and Todo summaries
  data/DissolvedRecords.kt one file's record as a dissolved history keeps it — a path, not an id
  data/ImportEntities.kt   Room entities: sources, task_imports, dissolution, legacy links
  data/ImportRun.kt        the run itself, and what it reports
  data/ListEntities.kt     Room entities, actions, decisions
  data/ListsDao.kt         every query
  data/ListeaDatabase.kt   the database and its migrations
  data/ReviewAsset.kt      one Asset as the review surface reads it, and why it is missing
  data/TaskEntities.kt     Room entities: tasks, assets, task_state, task_asset_state, review_events
  data/TemplateEntities.kt Room entities: templates, template_versions
  data/TemplateStore.kt    the templates as rows, and the one write that appends a version
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
| `Templates.kt` | Asset Viewer against Task Review, the built-in asset control, and what one version of one says |
| `TemplateScope.kt` | `ScopeRule` and `TemplateScope` — what a template governs, and the rules that decide it |
| `TemplateButtons.kt` | `ButtonType` and `TemplateButton` — what a button records, which is what decides how its mark is read |
| `Exports.kt` | `ExportKind` and `ExportChoice` — how decisions leave the app, and who said so |

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
and export operations. There is no repository layer and no domain-mapping layer. It is large; that
is a known cost, and it is the first thing the [roadmap](roadmap.md) work will have to address.

**Pure logic is kept separable.** `FileArrange.kt`, `ReviewArrange.kt`, `QuickReview.kt`,
`FolderListStatus.kt`, `ItemId.kt`, `Catalog.kt`, `CatalogSelection.kt`, `ContentPolicy.kt`,
`FolderListGate.kt`, `Importer.kt`, `TemplateResolver.kt`, `DissolvedRecords.kt`, the payload
helpers in `Webhook.kt`, and the whole of `domain/` have no Compose and little or no Android
dependency. That is what makes 815 JVM tests possible, and it is the seam the cross-platform roadmap
item would widen.

The rule the newest of these follow: **the decision is a pure function, and the DAO acts on its
answer.** `Importer.kt` plans Tasks from a `FolderSnapshot` and writes nothing;
`decideContentDecision` in `ContentPolicy.kt` says what one policy means for one Task's shape;
`FolderListGate.kt` is the decision table behind Create List's two confirmation gates. Each is
pinned by tests that owe nothing to Android, SAF or Room, which is why "which files are one Task"
and "what a changed Task means" are the two questions this codebase can answer precisely.

**State belongs to the Task, not to the List — and since v0.5.1 half of it is filed under a pair.**
Completion is still Task-level: `task_state.isCompleted`, keyed by `taskId` and cascading from
`tasks`. `★` and every configured action are not. They live on the **Task–Asset pair** in
`task_asset_state.selections`, one row per Asset, so two files of one folder-as-Task can carry
different tags, and the band under an item's name is the *file's* rather than the item's. A List
stores membership and projects both. (Before v0.2.5 all of it was keyed by root plus relative path
on a row of its own, which is why the older columns are still on those tables and still dead.) This
is the single most load-bearing decision in the codebase — see
[Core concepts → Where state lives](core-concepts.md#where-state-lives) — and a great deal of
behaviour is downstream of it.

The divergence is **recorded rather than settled**: [Architecture → TaskState](ARCHITECTURE.md#taskstate)
now carries a blockquote stating the pair split, the argument that the record is still the *Task's*
current state filed under an Asset key, and the question left open — whether "current state belongs
to the Task" is still the right sentence for a model whose completion is one value and whose marks
are several, or whether the rule should narrow to completion with marks named as pair state in a
sentence of their own.

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

Room, schema **version 25**, database file `listea.db`, and `exportSchema` is on. Nineteen entities
across five files:

| File | Entities |
|---|---|
| `ListEntities.kt` | `ListEntity`, `ListItemEntity`, `FileReviewStateEntity`, `ExportRecordEntity` |
| `TaskEntities.kt` | `TaskEntity`, `AssetEntity`, `TaskStateEntity`, `AssetStateEntity`, `ReviewEventEntity`, `LegacyItemIdEntity`, `LegacyManualTaskEntity` |
| `ImportEntities.kt` | `SourceEntity`, `TaskImportEntity`, `DissolvedTaskStateEntity`, `LegacySourceTaskEntity`, `LegacyTaskIdEntity` |
| `TemplateEntities.kt` | `TemplateEntity`, `TemplateVersionEntity` |
| `UpstreamEntities.kt` | `UpstreamEntity` |

The list is the `@Database(entities = […])` list at `ListeaDatabase.kt:24`, and the file column is
where each class is declared. One name has since drifted from its table: a kept export is an
`ExportRecordEntity` and its table is still `webhook_history`, because v0.5.6 added a `kind` column
rather than rebuilding a history that already held rows.

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

**19 → 20** adds the asset control a Todo, and each import run under it, records
(`sources.assetControls`, `task_imports.assetControls`). Two `ALTER`s, both not-null with a default,
and the default is the substantive part: `''` means *never chosen* and must be read as this app's
current configuration for the kind, never as *no tags*. Nothing is backfilled, for two reasons — a
migration runs against SQLite and cannot read DataStore, and more importantly there is nothing
correct to write even if it could: what a Todo predating this version "chose" is the app default
*continuously*, so freezing a snapshot of today's settings onto the row would end that behaviour
rather than preserve it. Absence is both behaviour-preserving and self-healing; the next import run
stamps the answer the user gives it. See
[v0.5.0](iterations/v0.5.0.md#where-the-choice-is-made-and-where-it-is-recorded).

**20 → 21** moves the record from the Task down to the Task–Asset pair. It creates
`task_asset_state` and copies each Task's marks onto every one of its Assets, and the copy is the
whole of the behaviour preservation: a file showing `p2` and `p5` still shows them the moment the app
reopens. A multi-Asset Task has its one Task-level record copied to each file rather than
distributed, which is deliberate and is not an attempt to reconstruct a per-file answer — there was
no such fact to recover, and the copy preserves what the user saw rather than guessing. The
`task_state` columns are left **populated and never read again**: dropping them needs SQLite 3.35
against a `minSdk` of 24, and they are the only remaining home for a legacy manual item's marks,
since a Task with no Asset has no pair to have been copied to. See
[v0.5.1](iterations/v0.5.1.md#the-record-still-belongs-to-the-task).

**21 → 22** gives a dissolved history its files' own records instead of a Task-level summary of
them. v0.5.1 had left `dissolved_task_state` reading `task_state`'s retired columns, so a
dissolution froze a value the migration had written and a recovery wrote into a column no surface
reads. One row per Task keyed by `groupPath` cannot say *which* file a mark was on, so the records
move into a payload — `records`, with `recordVersion` beside it — and the two retired columns come
off. **A full rebuild, because there is no `DROP COLUMN` to use**: create the new table, copy, drop,
rename, and recreate both indices, since Room compares what it finds against `22.json` on open.
Existing rows keep their completion and get an empty payload, which is the admission that the old
table never knew which file a mark was on. See
[v0.5.2](iterations/v0.5.2.md#why-a-payload-and-not-a-column-per-field).

**22 → 23** is **data only — no DDL at all**. `lists.completedAt` answers *is this List finished* and
is what the Lists screen files a row under, while the `N / N` beside it is counted live. Four writes
moved a List's membership without re-asking the question, so a List built from Tasks that were
already done was recorded as unfinished and stayed that way; the four are fixed in this release, and
this step repairs the rows that had already drifted. Its two statements are `ListsDao.refreshCompletion`
written out as SQL, because a migration cannot call a DAO, and a repaired row is stamped with the
upgrade time — the moment it really became complete was never written down, and `completedAt` is
sent in payloads, so an invented earlier value would be worse than an honest *as of now*. See
[v0.5.3](iterations/v0.5.3.md#the-lists-that-are-already-wrong).

**23 → 24** creates `templates` and `template_versions`, seeds the built-in asset control as a row
rather than a constant, and adds a `selections` column to `task_asset_state` — the record, merged
from `isFavorite` and `customActions` — plus a projection slot of the same name to `list_items` and
`file_review_state`, which the queries fill from the pair table. It then backfills those slots and
rewrites the two `assetControls` columns as references to the template that answers them. The
additions are additive so the merge can be checked against the rows it came from; the rewrite is
the half with no second chance, and it is this step rather than a later one because a build that
resolved a reference while the column still held a configuration would be two answers to one
question. Neither projection slot's old pair is dropped — `DROP COLUMN` again — so it stays the only
copy of a mark if the merged column is ever found wrong. See
[v0.5.4](iterations/v0.5.4.md#acceptance-as-run--the-schema-step-only).

**24 → 25** stops calling one mechanism the model. `sources.exportKind` and `lists.exportKind` name
what a Todo, and a List, does about exporting; `webhook_history.kind` names what a row already in the
history was. Three columns and one `UPDATE`, and the `UPDATE` is why this is not purely additive: a
List whose old `webhookEnabled` switch was on becomes `webhook`, which is exactly what it did, and
one that was off keeps `inherit`. The column default for a Todo is `webhook`, so a Todo that posted
before the upgrade posts after it, while a Todo created now is offered `none` first — that asymmetry
is deliberate and lives in the Kotlin default, not here. `lists.webhookUrl` is not dropped and not
cleared. See [v0.5.6](iterations/v0.5.6.md#the-change-in-one-paragraph).

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

The version travels with the round: `app/build.gradle.kts` holds one literal, and `versionCode` is
that name flattened as `major * 10_000 + minor * 100 + patch`, so bumping the name moves both — see
[Environment](#environment). That is part of finishing a round. Before 2026-10-11 both had read
`versionName = "0.1.0"` and `versionCode = 1` since the first build, so two APKs published from
different rounds claimed the same version and anything keying on it to tell them apart had nothing
to read.

---

Next: [Roadmap](roadmap.md) for where the architecture is going, or
[Core concepts](core-concepts.md) for the model the code implements.
