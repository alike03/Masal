# Masal

Android TV app: a home interface with IPTV live TV, Plex playback and app shortcuts, in Plex's look. Developed on an NVIDIA Shield. Package `com.alike.masal`, minSdk 26. Runs as a normal app and can also be the device's home screen (`docs/specs/home.md`).

**This file and `docs/specs/` are the source of truth.** Behaviour per feature is in `docs/specs/` (index in `docs/specs/README.md`). When a change alters behaviour, update the matching spec in the same commit; for larger features write or update the spec first and get it confirmed before building.

## Conventions
- Remote: designed for HDMI-CEC TV remotes: D-pad, OK, Back and play/pause only. No number keys, channel keys or Menu, and long press may not arrive. Every key is logged under tag `MasalKeys` (`adb logcat -s MasalKeys`) to check what arrives.
- Local settings (device address, toolchain paths, optional Home Assistant fallback, test values) live in `.env` (gitignored, template `.env.dist`). Never commit machine-specific values, IPs or paths.
- Credentials (Plex, IPTV) are entered on the device through the setup page and stay in the app's private storage. Never hardcode, log or print them.
- `snapshot/` holds personal library data for the real-data tests: gitignored, never commit it.
- `scripts/install.sh` installs, compiles and opens Masal, but only on an awake TV: it never wakes the TV and never starts other apps (`NO_START=1` installs only).

## Stack
Kotlin, Compose for TV (`androidx.tv:tv-material`), Media3 ExoPlayer, OkHttp, Coil, NanoHTTPD (QR setup page), ZXing.

## Build and install
- JDK 17 and the Android SDK (set `JAVA_HOME` / `ANDROID_HOME`, or put them in `.env`; `local.properties` holds `sdk.dir`).
- `./gradlew assembleDebug` builds; `scripts/install.sh` builds and installs over network ADB (`TV_ADB` in `.env`), with an optional Home Assistant fallback, then compiles the app on the device (`cmd package compile -m speed`; Android compiles a debuggable APK only to `quicken`, a release build fully): an uncompiled debug APK cold-starts slowly, and as the home screen Masal is cold-started at the moment the device goes to standby. Then it opens Masal if the TV is awake. Its stale-APK check ignores test sources.

## Releases
`.github/workflows/release.yml`: on a `v*` tag, tests, builds `assembleRelease` signed with the key from the repository secrets (created once by `scripts/create-release-key.sh`), checks the signature and publishes a GitHub release with `gh release create` (tags with `-` become pre-releases). Manual runs are dry runs that keep the APK as a workflow artifact. Version name comes from the tag, version code from the run number (`MASAL_VERSION_NAME` / `MASAL_VERSION_CODE`). Actions are pinned to commit SHAs with the version in a comment; when updating, look up the latest release of each action and runner image (`ubuntu-26.04` at the time of writing) instead of guessing.

## Tests (no TV needed)
`./gradlew testDebugUnitTest` runs the Robolectric suites below; video surfaces are off in tests (`VideoPreviews.enabled`), and so is Coil's fade-in (`ImageFades.enabled`, Robolectric's frozen clock leaves images half faded). Values from `.env` are passed to the tests as environment variables.
- `UiFlowTest`: real MainActivity driven with remote keys, fictitious data only, focus assertions. Shots: `app/build/outputs/roborazzi/build/masal-shots/`.
- `RealDataScreensTest`: your own library from `snapshot/snapshot.zip` served by `SnapshotServer` (MockWebServer acting as Plex, Xtream and Bing); skipped without a snapshot. Shots: `.../real-shots/`. Images load asynchronously, so a background photo can be missing in a shot without an app bug. Menu entries are reached with Back + Up/Down (focusing a rail label by text does not work).
- `PlayerOverlayTest`: Plex player controls and subtitle panel. Shots: `.../player-shots/`.
- `AmbientSeamTest`: the colour fill meets the picture frame without a line (pixel columns at the frame edge).

Snapshot: made on the device by the debug-only `SnapshotReceiver`, which drives the app's own `PlexClient` through a recording interceptor; responses are keyed with `debug.key()` (path + sorted query without token), so new Plex calls must also be made in the receiver (Plex search is not recorded yet). Run `adb shell cmd deviceidle tempwhitelist -d 300000 com.alike.masal` (needed while the device sleeps) and `adb shell am broadcast -n com.alike.masal/.debug.SnapshotReceiver`, wait for `snapshot.done` in the app's files folder, pull `snapshot.zip` into `snapshot/`, delete the device copy and scan it for credentials. Logins are replaced by [x] on the device.

Compose pitfalls found by the tests: a lazy grid that overlaps the element you press Down on is skipped by focus search (keep grids below headers with padding, not contentPadding); scrolling a lazy grid inside an onFocusChanged callback drops focus (scroll from a LaunchedEffect); `focusRestorer` on rows that leave composition crashes ("Release should only be called once"); `graphicsLayer` has no `blendMode` in Compose 1.7 (use `canvas.saveLayer` with a blend-mode Paint); anything drawn to line up with a laid-out element must use the layout's whole pixels (`roundToInt()` like `fillMaxWidth`/`aspectRatio`), a half-pixel offset shows as a thin line on the TV; `setIntent()` in `onNewIntent` makes `ActivityScenario` lose its activity and hang on teardown; infinite animations (Ken Burns) need `mainClock.autoAdvance = false`.

## Structure
- `data/` Settings (SharedPreferences; favourites, recent channels, Home apps order, Home live TV row), IPTV (M3U, Xtream API, XMLTV EPG), Plex client (JSON API, hubs, search, collections, genres, clearLogo, UltraBlurColors, streams, subtitle search/download, PIN link, timeline/scrobble), installed apps, Home rows (`HomeRows.kt`: Plex rows and collections chosen for Home, stored as differences from Plex's own Home), Bing photo-of-the-day backdrops (no key), `foldText()` (accent and Turkish I folding).
- `setup/` QR setup page for the phone and the Settings screen on the TV (spec: `setup.md`).
- `ui/` Home (`HomeScreen.kt`), accordion rows and info blocks (`Rows.kt`, `FoldingRows`), background and previews (`Ambient.kt`), libraries (`BrowseScreens.kt`), search (`SearchScreen.kt`), Apps page, TV tab, side menu, theme and soft scrolling (`Components.kt`).
- `player/` `PlayerActivity` (Plex and live), `PlexOverlay.kt`, `LiveOverlay.kt`, `Media.kt` (ExoPlayer with audio focus).
- `debug/` `SnapshotReceiver` (debug builds only).
- Look, assets, fonts and motion timings: `docs/specs/look.md`.
