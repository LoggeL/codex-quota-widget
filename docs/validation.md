# v1.0.3 validation

- 47 JVM/Robolectric tests. Regression coverage checks two static account slots, independent quota values, hidden removed accounts, cleared extra-account text, removal of unavailable windows and recovery from missing quota.
- Six native instrumentation tests. The new host test parcels RemoteViews before passing them to an asynchronous AppWidgetHostView using the test package as the host context. It replaces the previous compact layout, then exercises two accounts, one account, changed consumption, no accounts and recovery to two accounts. Visible account names, quota rows and the hidden extra-account label are asserted after each update.
- Existing 250 x 56 dp and 360 x 56 dp layout checks remain, including both windows, high forecasts and 130% system text. Added a single account with a long email-style display name at the minimum size and enlarged font.
- An actual pinned widget was inspected in the AOSP Launcher3 on the dedicated API 36 emulator. The launcher stayed running through application updates. The encrypted fixture accounts were preserved; both account labels, forecasts, reset times and bars were visible. UI hierarchy assertions verify the exact quota strings and absence of the extra-account indicator.
- The reported phone screenshot's failure was not reproduced on this emulator, including before the change. The fixed layout removes nested dynamic row insertion and repeated view IDs as potential compatibility failure points. This establishes emulator/host behavior; acceptance on the user's launcher is still pending.
- Version code 10 keeps the v1 application ID, signer and encrypted storage format. No real credentials or the user's screenshot are included in the repository or APK.

# v1.0.2 validation

- 46 JVM/Robolectric tests. Added calculations for exact elapsed fractions, weekly-only projections above 100%, API-provided durations, deficit/ahead signs, the original warning thresholds, unavailable/start-of-window forecasts, reset expiry, fixed snapshot time and independent account/window selection.
- Five native Android instrumentation tests on the dedicated API 36 emulator. Existing encryption, migration, app and widget checks remain; pixel assertions verify the consumed segment, unused track and white expected-consumption marker using Android's real Canvas renderer.
- Compact RemoteViews checked at 360 x 56 dp and 250 x 56 dp, with one or both quota windows, 130% font scaling, forecasts over 1,000%, complete reset countdowns, failed refreshes, absent data and no accounts. Names may ellipsize; forecast, delta and reset labels are checked for their full measured width and line height.
- Detailed RemoteViews checked at 360 x 200 dp (including two accounts with failed refreshes) and 360 x 260 dp. Bars and quota text must fit their measured bounds; screenshots were visually inspected. The compact layout remains active until enough height is available for the restored details.
- Forecasts use each snapshot's original timestamp, preserving its budget comparison while cached. They become unavailable at the reset, rather than implying a refilled quota. The app and README explain the linear estimate and percentage-point notation.
- Version code 9 retains the v1 signer and encrypted account format. Release packaging runs unit tests and release lint and verifies certificate, package identity and non-debuggable configuration. The published APK is independently downloaded and checked against its packaged SHA-256.

# v1.0.1 validation

- 37 JVM/Robolectric tests, including two-column 4x1 rendering, both window types, independent bars, account removal/reapplication, empty state, failed refreshes and expired snapshots.
- Four Android instrumentation tests. The new layout is checked at 360 x 56 dp and 250 x 56 dp, with weekly-only accounts, both windows, 130% text scaling, partial failures, absent data and no accounts.
- Native layout assertions cover text height, percentage/reset text width and quota-bar bounds. No percentage or reset label is ellipsized in the tested sizes.
- Existing detailed layouts are still checked at 360 x 180 dp. The compact layout is selected below 140 dp; launcher metadata requests four columns and one row with a 56 dp minimum height.
- Version code 8 keeps the v1 signing certificate and account-storage format for installation over 1.0.0.

# v1.0.0 validation

- 33 JVM/Robolectric tests cover parser classification, independent quota values, encrypted-state serialization, cache expiry, duplicate login identity, refresh-token rotation, interrupted login, restored pending login and RemoteViews reapplication.
- Three instrumentation tests on a dedicated Android 16 / API 36 ARM64 emulator exercise real Keystore storage, encrypted data read-back from a new store instance, deletion of one account, legacy preference migration and app/widget rendering.
- Widget rendering checks every visible text view against the allocated height, with two weekly-only accounts, two accounts with both windows and a failed account beside a healthy one. Reference images use invented names and credentials.
- The live usage endpoint was read successfully for the two locally configured desktop accounts. Both responses contained weekly-only `primary_window` data with `limit_window_seconds: 604800`. No credentials or personal account identifiers are stored in this repository.
- The Android app obtained a real device code, opened the official device-login URL in the browser, and restored the pending login after stopping its process and relaunching it through the launcher intent. The pending login was then cancelled. Browser account approval and the subsequent live token exchange require the account owner and were not completed in this run; coordinator completion is covered with injected test responses.
- The release packaging script checks tests, lint, APK identity, non-debuggable configuration and the expected certificate. Public release assets are downloaded again and verified after publication.

The published 0.4.0 certificate is `e6b07afea39b5957763b9ffd136344b907f1aa55fdfd32d34359cbf61acea5ed`. The retained local key used for 1.0.0 has certificate `ff7f5e3369f7063ad452036834317fda47c1f40d329816f1d3e948004fb0c53c`. These are different signers, so installing 1.0.0 requires replacing the old installation. A matching-certificate legacy data migration is tested at the storage layer, not claimed as an install-over update from the public 0.4.0 APK.
