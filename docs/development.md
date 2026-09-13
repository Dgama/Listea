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

**196 JVM unit tests**, run with `./gradlew testDebugUnitTest`. No device or emulator needed.

They deliberately target the pure logic rather than the UI, which is why so much of that logic lives
in files with no Android imports:

| Test | Covers |
|---|---|
| `FileArrangeTest` | Filtering and sorting: the unknown rule, calendar-day freshness, every comparator, encode/decode round trips |
| `ListArrangeTest` | A List's items under the same machinery |
| `ReviewRoundTest` | Composing a page's arrangement with *unchecked items only* |
| `QuickReviewQueueTest` | Resolving a folder into a review queue |
| `FolderTypeTest` | None / List / Sublist attribution and subtree progress |
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

There is one instrumented test (`ExampleInstrumentedTest`), which is the template's and is not load
bearing.

## Project layout

```
app/src/main/java/com/example/listea/
  MainActivity.kt          app shell, top-level navigation, the Folder screen
  AppShell.kt              shared scaffolds and the shared spacing scale
  ManagementUi.kt          shared pieces for Folder / Lists / List Detail

  ListsScreen.kt           the Lists index
  ListDetailScreen.kt      one List: source, progress, items
  ListsViewModel.kt        all list, folder, freshness and webhook operations

  ReviewScreen.kt          full Review, and the shared review session
  QuickReviewScreen.kt     folder-scoped Review over the same session
  QuickReview.kt           resolving a folder into a review queue
  ReviewArrange.kt         what a round queues, given a filter and the settings
  FileViewerScreen.kt      the plain gallery
  ListItemViewerScreen.kt  the same, reached from a List

  MediaUi.kt               shared media shell, review action bar, Info sheet
  MediaPreview.kt          the one renderer: images, GIF, video
  VideoControls.kt         the video transport: play, seek, speed, mute
  SwipeCard.kt             the one swipe gesture, and where paging and zoom are arbitrated
  MediaZoom.kt             the pinch, the pan, and how far either may go
  MediaSave.kt             copying a viewed file into the DCIM/Listea gallery album
  MediaShare.kt            staging a viewed file for Android's share chooser

  FileArrange.kt           filtering and sorting, as pure values and one pure function
  FileArrangeUi.kt         the filter and sort headers and dialogs
  FolderReader.kt          reading a SAF folder
  FolderScan.kt            walking a subtree, and diffing it against a List
  FolderStore.kt           the persisted root grant
  FolderListStatus.kt      None / List / Sublist, and per-folder progress

  Settings.kt              AppSettings, custom actions, the DataStore behind them
  SettingsScreen.kt        Settings
  FileManagement.kt        the delete preview, and the deleting itself

  Webhook.kt               payload construction and delivery
  WebhookHistoryScreen.kt  every kept payload, with resend and delete

  data/ItemId.kt           the public id an item carries onto the webhook
  data/ListEntities.kt     Room entities, actions, decisions
  data/ListsDao.kt         every query
  data/ListeaDatabase.kt   the database and its migrations
  ui/theme/                colours, typography, theme
```

## Architectural notes

These are descriptions of the code as it stands, not proposals. Changing any of them is a design
decision, not a refactor.

**One ViewModel.** `ListsViewModel` owns list, folder, freshness, settings-writing, file-deletion
and webhook operations. There is no repository layer and no domain-mapping layer. It is large; that
is a known cost, and it is the first thing the [roadmap](roadmap.md) work will have to address.

**Pure logic is kept separable.** `FileArrange.kt`, `ReviewArrange.kt`, `QuickReview.kt`,
`FolderListStatus.kt`, `ItemId.kt` and the payload helpers in `Webhook.kt` have no Compose and
little or no Android dependency. That is what makes 196 JVM tests possible, and it is the seam the
cross-platform roadmap item would widen.

**State belongs to the file, not to the List.** Checked, favourite and custom actions are stored in
their own table keyed by root plus relative path; a List stores membership and projects that state.
This is the single most load-bearing decision in the codebase — see
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

Room, schema **version 12**, database file `listea.db`. Four entities: `ListEntity`,
`ListItemEntity`, `WebhookRecordEntity`, `FileReviewStateEntity`.

**Every version step from 1 has a written migration** and they are all registered; there is no
destructive fallback. Upgrading in place is expected to keep existing data. `exportSchema` is off.

The two most substantial steps, for context when reading them: **9 → 10** introduced public item
ids and backfilled them, and **11 → 12** rebuilt the item tables when the two fixed custom-action
columns became a list of action ids.

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
