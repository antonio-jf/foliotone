# FolioTone development handoff

Updated 2026-11-09, America/Mexico_City.

Archive update on 2026-11-09: the downloaded Qwen3-TTS model was removed at the user's request, and the local Voice Lab server was stopped. The source archive retains the Android project, documentation, and Voice Lab setup/downloader files, while excluding generated artifacts, environments, caches, model weights, and machine-specific settings. See [archive restoration](../ARCHIVE_RESTORE.md) before resuming from that ZIP.

FolioTone is a native Android EPUB reader with device text-to-speech, optional ElevenLabs narration, private book storage, and Android Auto media browsing. The recent requests in this conversation are implemented, including the latest fix for the reader button remaining on Pause after another app interrupts playback. The last verified debug build passed and was installed in the emulator. Remaining work is primarily device validation and repeatable regression coverage, as detailed below.

Start with this file, the root [README](../README.md), and [AGENTS.md](../AGENTS.md). The README describes user-facing behavior; this handoff explains implementation decisions, verification limits, and how to resume development. Check current files before editing because other work can occur in this shared directory.

## Project identity and working environment

| Item | Current value |
| --- | --- |
| Workspace | `~/Development/reader` |
| Display name | FolioTone, still a provisional name |
| Android namespace and application ID | `com.linguareader` |
| Gradle root project name | `LinguaReader` |
| Implementation | Kotlin with programmatically constructed Android Views |
| Minimum Android version | API 26, Android 8.0 |
| Compile and target SDK | 35 |
| Gradle wrapper | 8.9 |
| Android Gradle plugin | 8.7.3 |
| Kotlin plugin | 2.0.21 |
| Java and Kotlin bytecode target | 17 |
| Android dependencies | DocumentFile 1.0.1 and jsoup 1.18.3 |

The user has Android Studio open and has authorized using it for verification. Recent checks used its running emulator through ADB, historically `emulator-5554`; run `adb devices` rather than assuming that serial is still available. This folder was **not a Git repository** when checked for this handoff. There is no commit or branch identifier for the changes described here.

The original requirements cover English, Italian, French, German, and Spanish; EPUB import and resumption; language selection or inference; Android Auto; section navigation; and simple listening controls. Installed speech providers and downloaded voices determine whether a particular device voice can actually synthesize a language offline.

## Build and install

The following configuration successfully built the Android app on this Mac:

```sh
cd ~/Development/reader
export JAVA_HOME='/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --offline --no-daemon assembleDebug
```

The SDK location in this machine's ignored `local.properties` is `~/Library/Android/sdk`. Adjust these paths on another machine. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

```sh
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" devices
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s emulator-5554 shell am start -W -n com.linguareader/.MainActivity
```

Use `install -r` to preserve app data. Clearing data or uninstalling the reader removes its private library and settings. Temporary verification apps can be uninstalled separately.

Environment issues encountered:

- Android Studio's bundled Java runtime was 25.0.3 during an earlier build attempt. This project's Gradle setup failed with that runtime. The explicit Java 21 configuration above worked; source compatibility remains Java 17.
- An offline lint attempt could not resolve `com.android.tools.lint:lint-gradle:31.7.3`. A later online attempt stalled while fetching dependencies and was stopped. **Do not report lint as passed.** With dependency access available, run `./gradlew --no-daemon lintDebug` using the working JDK.
- An empty temporary Gradle user home triggered an unavailable wrapper download. Reuse the existing Gradle cache when working offline.
- The restricted execution environment sometimes requires escalation for Gradle's user cache/daemon and ADB's local socket. This is a tooling limitation, not an app defect.

No source changes are needed to reproduce the last successful build. A documentation-only change does not require reinstalling the APK.

## Source map

Android Kotlin files are under `app/src/main/java/com/linguareader/`.

| File | Responsibility and useful entry points |
| --- | --- |
| `MainActivity.kt` | Home/library, List/Grid selection, reader, contents, ratings, themes, Gutenberg browsing, listening settings, voice previews, and playback-state subscription. UI is rebuilt by methods such as `showHome()` and `showReader()`. |
| `ReaderService.kt` | Authoritative live reading/playback state, device TTS queue, ElevenLabs playback and lookahead, media session, notifications, Android Auto browsing, progress saving, and completion detection. |
| `BookStore.kt` | `Book`, `Section`, `ParsedBook`, synchronized JSON library persistence, preferences, import/deduplication, covers, folder scanning, and the `Epub` parser. |
| `PlaybackAudioFocus.kt` | Shared audio-focus and becoming-noisy handling for narration and voice previews. |
| `SectionTimeline.kt` | Estimated section duration and position in milliseconds; maps media seek positions to page indices. |
| `AppThemes.kt` | The 20 named palettes and their interface/reading-page colors. |
| `NarrationVoices.kt` | Language-to-locale mapping, provider voice filtering/ranking, preference selection, and preview samples. |
| `ElevenLabs.kt` | Encrypted API-key storage, voice discovery, narration generation, audio cache, and offline audio files. |
| `OfflineDownloadService.kt` | Foreground generation/storage of complete ElevenLabs book audio, cancellation, and progress reporting. |
| `GutenbergCatalog.kt` | Gutendex search/pagination, supported-language filtering, allowed download URLs, and bounded EPUB downloads. |
| `NarrationServer.kt` | Narration-server source: encrypted `ServerCredentials`, `ServerAudio` page storage and keys, and the HTTPS client for `tts-server/app.py` (health, jobs, page download, voice samples). |
| `NarrationLibrary.kt` | Library badges and storage for saved narration: per-book summaries (`narration_<source>_<bookId>` = voice, saved, total), recount after downloads and reader status checks, total size, and delete-all. |
| `GutenbergDownloadService.kt` | Foreground EPUB download/import with cancellation and notification progress. |

`app/src/main/AndroidManifest.xml` declares the activity, media browser service, foreground download services, permissions, and Android Auto metadata. Resources include branding, monochrome notification icons, and the base Android theme; most layout and selected-theme colors live in Kotlin.

## Features and completed changes

| Area | Current behavior |
| --- | --- |
| EPUB library | Individual document import and selected-folder scanning copy EPUBs into private storage. SHA-256 IDs deduplicate files; Gutenberg IDs also identify catalog imports. Embedded covers are extracted when available. |
| Reader progress | The reader shows `Book progress · X%` beneath the current section's page count. The draggable bar navigates **within the current section**. |
| Ratings | Personal scores from 0.0 to 5.0, in 0.1 steps, edited using a slider and fine-adjustment buttons. Ratings can be removed. Home links to highest/lowest rated, alphabetical, and unrated views, with a rated-book count and average. |
| Read status | Reader button and library long-press actions mark a book read/unread without moving its position. Home filters All books/Unread/Read. Automatic marking defaults on and occurs after final narration completion or Next beyond the final page. It can be disabled in settings. |
| Themes | Folio, Polar, Candy, Paper, Midnight, Ocean, Forest, Lavender, Rose, Sunset, Ember, Sand, Mint, Sky, Plum, Cocoa, Slate, Monochrome, Cherry, and Lemon. Selection persists and updates interface, reading page, and system-bar icon appearance. |
| Library layout | List/Grid buttons beside the library filter. List is the default; selection persists. Grid column count adapts to width, retaining covers, progress, ratings, status, tap-to-read, and long-press actions. Cover-cache keys include requested dimensions to avoid enlarging a small list thumbnail in the grid. |
| System media widget | Book artwork or generated title artwork, book title, section number/title, page count, and a functioning section seek bar. A tap opens the current book without contextual rewind or interrupting active playback. Android controls the artwork's exact presentation. |
| Audio handoff | Narration requests Android audio focus. Full focus loss (another media app) pauses and releases focus; it does not resume by itself. Transient and duckable loss (navigation directions, notification sounds, calls) pause briefly while keeping the focus request, then resume about 0.6 s after focus returns: saved audio continues 2 s before where it stopped; the device voice restarts the interrupted sentence (page start if the engine gives no word progress). Pressing Play during the pause resumes at once; Pause cancels the automatic resume. Voice previews still stop on any loss. Changed 2026-11-12 (previously all losses paused for good). |
| Output disconnect | An active-playback `ACTION_AUDIO_BECOMING_NOISY` receiver pauses when Android reports headphone/Bluetooth output falling back to the speaker. Physical device validation is still outstanding. |
| Reader button state | On returning to the activity, it requests a current playback snapshot after registering receivers. The green button changes from Pause to Listen after an interruption while the screen was stopped. Its label also updates directly when a state broadcast arrives, even if a full reader rebuild is temporarily suppressed. |

## Narration server source (added 2026-11-09)

The third narration source, `narration_source = server`, uses the user's own Voxtral 4B TTS server on Modal (`tts-server/app.py`; setup in `tts-server/README.md`; design and costs in `docs/SELF_HOSTED_TTS_PROPOSAL.md`).

- **Download-first:** `OfflineDownloadService.downloadFromServer()` posts the not-yet-saved pages to `POST /v1/jobs`, polls `GET /v1/jobs/{id}?after=N`, downloads each finished page, and deletes the job. Its id and voice are stored as `server_job_<bookId>` (`jobId|voice`), so pausing or an Android background time limit only stops the phone side; the next download resumes the same job. A 404 means the job expired, and only the missing pages are resubmitted.
- **Playback:** `ReaderService.speakServer()` plays `noBackupFilesDir/server_audio/<bookId>/<key>.mp3` through the existing `playCloudFile()` path (no lookahead) and applies `store.speechRate` with `MediaPlayer.playbackParams`. A missing page pauses with a message rather than fetching audio during playback.
- **Partial downloads:** `MainActivity.offerDownloads()` offers, for ElevenLabs and the server, from the current page to the end, the rest of the current section, or the whole book, counting only pages missing for the current voice. `OfflineDownloadService` takes `from`/`to` page indices in reading order; the server path stores the range as `server_range_<bookId>` for **Resume download**, and asks once more for still-missing pages if a resumed job covered a different range.
- **Regenerate and auto-switch:** `regenerateCurrentPage()` starts `OfflineDownloadService` with `regenerate=true` for one page; the server path deletes the local file and uses a fresh job that is not saved as `server_job_<bookId>`, and the ElevenLabs path calls `ElevenLabs.forgetPage()` (cache and offline copy). `server_device_fallback` (default off) makes `ReaderService.speakServer()` call `speakDevice(fallback = true)` for a page without saved audio or whose MP3 fails to play; only that page is queued, and `deviceDone()` then calls `speak()`, which returns to server audio when the next page has it.
- **Keys:** `ServerAudio.key(voice, text) = sha256("voxtral-4b-2603|voice|text")`. Speed is not part of the key; voice and model are. Pages with identical text share one file.
- **Preferences:** `server_voice_<lang>`, `server_voices` and `server_defaults` (from `/v1/health`), `server_job_<bookId>`. Credentials (URL, Modal proxy token ID and secret) live encrypted in `noBackupFilesDir/narration_server.json`.
- **Server:** CPU FastAPI app behind Modal proxy auth; GPU class `Voxtral` (L4, vLLM 0.32.0 and vLLM-Omni 0.32.0 pinned together, max 2 containers, 2-minute idle shutdown, retries resume finished pages); `foliotone-jobs` Volume; daily cleanup of jobs older than 3 days. Phase-0 measurement on an L4: about 242 characters per second at 16 simultaneous requests, about 7.5 minutes for the first cold start including the weight download.
- **Verification so far:** the server job flow was tested locally with a fake model (dedupe, a failed page, resume without re-rendering, validation, delete). `./gradlew assembleDebug` passed on the Mac after these changes (2026-11-09). An end-to-end run against the deployed server on a device is still outstanding.

Original versions of the modified files are in `backups/before-server-narration/`.

## Interface language (added 2026-11-09)

- English and Spanish only. `I18n.kt` holds the current language and `tr(text, args…)`: the English text is the key, Spanish lives in `app/src/main/assets/i18n/es.json`, and `{0}`, `{1}`… are filled from the arguments. A missing key falls back to English.
- Choice is stored as `app_language` in BookStore (`system` default, `en`, `es`); MainActivity shows it under Listening settings → App language. `I18n.init(context)` runs in `onCreate` of MainActivity, ReaderService, OfflineDownloadService and GutenbergDownloadService so notifications match.
- Not translated on purpose: book-language voice sample sentences (NarrationVoices), the default `Section N` title in BookStore (MainActivity parses it), palette names, voice-command phrases, and keys/URLs/JSON fields.
- New UI text: wrap it as `tr("Text with {0}", value)` — a plain literal, not a `$` template — then run `python3 tools/i18n/check_translations.py` (add `--add` to append English placeholders to es.json) and translate the new entries. Adding a language = new `assets/i18n/<code>.json` plus an entry in `I18n.LANGUAGES`.
- Pre-i18n sources are in `backups/before-i18n/`.

## Data and progress semantics

`BookStore` is a process-wide singleton using the application context. Each `Book` stores ID, title, author, language, zero-based section/page indices, section count, page count for the saved section, and optional Gutenberg ID.

| Location or preference | Meaning |
| --- | --- |
| `filesDir/books/<id>.epub` | Private imported book file |
| `filesDir/covers/<id>.cover` | Extracted cover bytes |
| `filesDir/library.json` | Library metadata and reading positions; writes use a staged file/rename with fallback |
| SharedPreferences `settings` | Reader, provider, language voice, appearance, rating, and completion preferences |
| `book_rating_<id>` | Integer 0–50 representing tenths of a star; missing entry means unrated, **not 0.0** |
| `book_read_<id>` | Boolean completion status, default false |
| `auto_mark_read` | Boolean, default true |
| `context_resume` | Boolean, default true; reopen one page earlier |
| `theme` | Palette ID, default `folio` |
| `library_grid` | Boolean, default false |
| `last_opened_id`, `folder_uri` | Recent book and persisted document-tree location |
| `speech_rate`, `speech_engine`, `narration_source` | Narration settings; source is `device` or `elevenlabs` |
| `voice_<engine-or-system>_<language>` | Device voice preference; older system-default keys have a read fallback |
| `eleven_voice_<language>` and its `_name` key | Selected ElevenLabs voice ID and display name |
| `noBackupFilesDir/elevenlabs.key` | API key encrypted using Android Keystore; never print or copy it into documentation/logs |
| `noBackupFilesDir/eleven_audio/` | Recent generated MP3 cache, trimmed to approximately 150 MiB |
| `noBackupFilesDir/offline_audio/<bookId>/` | Persistent offline audio for that book and generation parameters |

**Progress overwrite fix (2026-11-09):** the home screen kept the `Book` copies it read when drawn, and `openBook()` wrote such a copy back with `update()`, undoing pages the narration service had saved since (reopening at page 32 after listening to page 40). Screens now change books only through `BookStore.edit(bookId) { … }`, which modifies the newest saved record (`openBook()` page counts, `chooseLanguage()`). The home screen redraws when a state broadcast reports a position different from what it shows, and tapping the book that is currently playing uses `OPEN_CURRENT` so playback continues without the contextual rewind. Do not reintroduce `store.update(staleCopy)` from UI code.

Ratings and completion are deliberately stored separately from the mutable `Book` JSON record. Service progress writes must not overwrite newly edited ratings or read status. `BookStore.update(book)` replaces a complete book record, so avoid writing stale copies over newer service progress. In particular, `MainActivity.openBook(..., fromMedia = true)` skips its metadata write before synchronizing with the service.

Pages are text chunks of roughly 950 characters, split on words/paragraphs; they are not EPUB print-page numbers or screen-sized pagination. Sections follow readable EPUB spine entries. The parser bounds ZIP/text input, rejects unsafe XML declarations, and limits section/page counts; preserve these protections when extending import behavior.

The library/reader percentage currently weights sections equally:

```text
floor(100 × (sectionIndex + pageIndex / pagesInCurrentSection) / sectionCount)
```

It is an approximate location, not a total-word/page-weighted percentage. A book marked read reports 100% even if its retained position is earlier. Automatic completion does not infer historical read status for all existing books, and merely opening/seeking to the last page does not mark it read. Pausing narration resumes from the saved page's beginning rather than an exact word or audio offset.

## Playback contracts to preserve

### Service commands and state

`MainActivity.command()` sends explicit intents to `ReaderService`. State-changing work and callbacks run on the main thread; network/generation work runs on executors.

| Action | Relevant extras or behavior |
| --- | --- |
| `GET_STATE` | Publish the current state; do not open, seek, or start playback |
| `OPEN` | String `id`; open from the library with the optional one-page contextual rewind; no autoplay |
| `OPEN_CURRENT` | String `id`; retain current playback if already loaded, otherwise open without rewind/autoplay |
| `PLAY`, `PAUSE`, `STOP` | Playback control |
| `NEXT`, `PREVIOUS` | Page navigation, crossing section boundaries |
| `SEEK` | Integer `page`, zero-based within the current section |
| `SECTION_START`, `SECTION_NEXT`, `SECTION_PREVIOUS` | Section navigation |
| `SECTION_JUMP` | String `id` and integer `section`, zero-based |
| `LANGUAGE` | String `language` |
| `VOICE_CHANGED`, `ENGINE_CHANGED`, `SOURCE_CHANGED` | Apply changed narration settings |

The package-scoped broadcast `com.linguareader.STATE` carries `playing`, `error`, `finished`, `id`, `section`, and `page`. The media session exposes the same live playback state. Android Auto browses `root`, `book:<id>`, and playable `book:<id>:<sectionIndex>` media IDs. Download services have separate progress broadcasts.

**Latest regression and fix:** `MainActivity.onStop()` unregisters its state receiver. An audio-focus interruption while Spotify/another player is foregrounded therefore cannot update the stopped reader's cached `playing` value. `onStart()` now registers receivers first and sends `GET_STATE` afterward. `stateReceiver` immediately updates the tracked `playbackButton` using `playbackLabel()`, then performs the normal conditional screen rebuild. Keep this subscription-plus-snapshot ordering in future lifecycle/UI refactors. The service, rather than a remembered button label, is the playback authority.

Notification taps carry `mediaBookId` with single-top/clear-top flags. `openMediaBook()` handles both initial and new intents and selects the `OPEN_CURRENT` path. Do not use regular `OPEN` for notification taps: it would rewind and pause the book.

### Narration cancellation and audio focus

Device TTS keeps the current and next page queued. Utterance IDs include a generation token, section, and page. Completion handling advances the queue and ignores stale callbacks. ElevenLabs uses a player plus generation/prefetch futures; its one-page lookahead can cross section boundaries. Token checks prevent cancelled requests or old players from starting after a pause, seek, or setting change.

`haltAudio()` clears queues, cancels pending work, releases players, and invalidates callbacks. It intentionally does **not** abandon focus on every page transition. Terminal pause, stop, error, completion, destruction, or opening a book without autoplay release focus separately.

`PlaybackAudioFocus` uses `USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`, `AUDIOFOCUS_GAIN`, pause-on-duck, and no delayed focus gain. Permanent loss releases the request and invokes the owner's pause/preview-stop callback. Narration also passes `onPausedBriefly`/`onResumed`: transient or duckable loss keeps the request, `ReaderService.pauseBriefly()` pauses the MediaPlayer in place (or stops TTS and remembers the sentence start in `resumeAt`), and focus gain calls `resumeBriefly()` after 600 ms. Old request callbacks are ignored by identity; focus gain resumes only after a brief pause, never after a permanent loss or a user pause. The disconnect receiver is registered only while a request is active and unregistered on release, using non-exported registration on API 33+.

`ReaderService.beginPlayback()` enters foreground service mode **before** requesting focus, including while waiting for device TTS initialization. This order is necessary for background focus requests when targeting Android 15. Losing focus while initialization or cloud generation is pending must cancel the intended playback too. Activity voice previews have their own guard and stop when the activity stops or their sheet is dismissed.

### Media progress

The original widget bug sent page counts/indices as Android durations/positions, which are milliseconds. Do not restore that unit mismatch. `SectionTimeline` estimates each page at 150 words/minute adjusted for speech rate, with a 1,000 ms minimum. `MediaSession.onSeekTo()` converts milliseconds back to a containing page. This differs from the in-app `SEEK` action, which already takes a page index.

Within-page position uses device speech range callbacks, elapsed-time estimates where ranges are unavailable, or actual cloud player position divided by that page's duration. The service updates playback position once per second without rebuilding the reader every tick. Whole-section timing remains estimated because narration is generated per page.

## Verification completed in this conversation

These are development checks, not a committed automated test suite. Neither `app/src/test` nor `app/src/androidTest` existed at handoff. Scratch helpers, screenshots, a Java timeline check, and an independent audio-focus probe APK were created under `/private/tmp`; their continued availability is not guaranteed and the probe app was uninstalled after verification.

| Area | Checks completed |
| --- | --- |
| Build | `assembleDebug` passed with Java 21; final button synchronization build installed in emulator |
| Progress and widget | Reader percentage rendered; cover background and section/page details visually checked in the actual Android media panel; system seeking changed to an interior page; tapping the widget opened the book without rewind |
| Timeline | Millisecond units, fractional positions, seek boundaries/clamping, speech-rate effects, and empty input checked with a local JVM harness |
| Ratings | 4.1/3.2 values, both sort directions, average, 0.0/5.0 boundaries, cancelled edits, removal, restart persistence, and survival of page changes |
| Completion | Manual read/unread, filter behavior, persisted status, Next past final page, automatic marking disabled, and natural completion using a working English device voice |
| Themes | Polar/Candy screenshots, saved choice after restart, reading-page appearance, and a contrast calculation for all 20 palettes; placeholder cover text corrected for light themes |
| Grid | Two books side by side, switching back to list, read filtering/empty state, and saved Grid selection after restart |
| Audio focus | Real Android focus requests from an independent temporary app: full takeover in both directions, transient loss, duck requests, background reader resumption, remaining paused after the other owner releases focus, and focus release on manual pause |
| Disconnect lifecycle | Actual receiver registration during playback and removal after pause inspected in Android broadcast state |
| Latest reader button fix | Reader playing with Pause label → Home/stopped activity → another app takes focus → service paused → return to the existing activity **without reopening the book** → Listen label; one tap resumes and restores Pause. A separate background pause also refreshed the label on return. |

The emulator contained an existing short Spanish `Test Book` and `Nudge: The Final Edition`. Test ratings/read flags, temporary language changes, and reading positions were restored after checks. Do not hardcode those files or IDs into implementation or future tests. A Spanish emulator voice failed synthesis; a working English voice was used to exercise natural completion, then the test book language was restored. This does not establish all-provider/all-language audio coverage.

## Remaining verification and limitations

1. **Physical Bluetooth and headset tests:** verify active earbuds/car audio disconnect pauses both narration engines and previews, including while the screen is stopped and cloud audio is buffering. Reconnection must not resume automatically or unexpectedly route speech to the speaker. The emulator did not expose a usable wired audio device from virtual switch events, and Android rejected shell injection of the protected becoming-noisy broadcast. A physical disconnect has not been validated.
2. **Real Spotify and Android Auto checks:** focus handoff used an independent test app, not Spotify itself. Validate the exact two-app workflow on a phone and media browsing/controls/artwork on an Android Auto device or suitable host. Implementation is present; car hardware coverage is not established here.
3. **ElevenLabs regression:** the recent focus/lifecycle changes cover its code paths, but new paid cloud/offline-audio end-to-end tests were not run for those changes. With a configured test account, cover focus loss during generation, prefetched-page cancellation, reconnection, cache reuse, and finishing the last page. Generation and lookahead use account credits.
4. **Complete lint:** fetch the missing lint dependency through an available network and rerun `lintDebug` with the working JDK. Existing deprecation warnings in builds are not equivalent to a successful lint run.
5. **Preserve repeatable regression coverage:** no durable Android test harness is committed. The highest-value first test is the background focus interruption and reader-button resynchronization case below. Also cover focus denial/loss, cancellation of delayed playback, completion cleanup, and preference persistence when introducing an appropriate test setup.

Current scope limits are intentional or existing behavior: private copies rather than a user-selected writable book destination; approximate progress/timings; page-level audio resumption; a plain-text EPUB rendering model; five language choices; and no mobile integration of the separate local voice lab. Voice commands enter through Android media/Assistant callbacks; there is no always-listening in-app microphone interface. Changes to those behaviors would be new work rather than completion of the requests already implemented here.

## Reproducing the critical playback regression

1. Open a book using a working device voice. Press Listen and confirm both actual narration and the green Pause label.
2. Leave the reader with Home or switch apps, so `MainActivity.onStop()` runs.
3. Start Spotify or another app that requests media audio focus. Confirm the reader's media session becomes paused and speech stops.
4. Return to the **existing reader screen** using Recents or the app launcher. Do not tap the media notification or reopen the book from the library for this assertion: `OPEN_CURRENT`/`OPEN` would issue a state update and could mask the missed-broadcast regression.
5. Confirm the button says Listen. One tap must start narration and change it back to Pause; it must not require a redundant first Pause tap.
6. Repeat with pause initiated from system/car controls while the activity is stopped. Confirm the current page is synchronized as well.
7. Pause, restore any changed book position/preferences, and remove temporary verification apps without uninstalling the reader.

Useful diagnostics:

```sh
"$ADB" -s emulator-5554 shell dumpsys media_session
"$ADB" -s emulator-5554 shell dumpsys audio
"$ADB" -s emulator-5554 shell dumpsys activity broadcasts
"$ADB" -s emulator-5554 exec-out screencap -p > /private/tmp/reader-screen.png
```

Inspect the FolioTone media-session entry for PLAYING/PAUSED and the audio-focus stack for package `com.linguareader`. Filter diagnostic output to relevant entries. Do not dump API-key files or unfiltered preferences into chat/logs.

ADB `am startservice` from a cold/background app can be rejected by Android's background-start restrictions. Launch the activity normally first, or exercise controls through the existing media session; do not weaken app restrictions to make a shell test pass. UIAutomator can fail to reach idle during live media progress updates or briefly return an earlier screen around installation/cold startup. Check the actual screen and poll for a stable expected state rather than trusting an old XML dump. Allow a layout preference write to complete before force-stopping for persistence tests.

## Separate local voice lab

`tts-lab/` is an independent Apple Silicon Qwen3-TTS/MLX browser playground, with its own [README](../tts-lab/README.md), setup/run scripts, local server, browser UI, dependency lock, and pinned model downloader. Model files and generated sample audio are excluded from the source archive; setup can recreate the model download. Its README documents a loopback server at `127.0.0.1:8765`, five language samples, and its own local verification results. Use that README for setup and current lab-specific status.

The Android app does not yet consume this lab's API. Integrating it as another narration source is a separate design/implementation task, not a feature completed in this Android conversation. Do not include large model weights, generated audio, Python environments, or Android build output in routine source searches or an eventual source-control commit by accident. Follow the lab's existing documentation rather than changing it as part of an unrelated Android fix.

## Picking up the next session

Read this handoff and the current affected source, confirm the working JDK/emulator, and identify whether the next request is Android work or voice-lab work. Keep the latest playback snapshot fix, focus lifecycle, callback cancellation, persistence formats, and notification-open behavior intact. All recent requested Android features are implemented; start with the concrete remaining verification above unless the user asks for a new feature. Update this handoff when behavior or verification status changes.
