# Test plan (real device)

Install order: build/install the PAM fork APK (other repo) and this AMM APK, both from YOUR releases, then run AMM setup.

1. Open "AMM Diagnostics" > Run all tests. Expected lines, in order:
   - `== Environment ==` AMM 2.4 (240), github owner=codyandersan, manifest flag, `superpacksRequireWiFi=false` (if it says true, you had it saved: turn it off in Advanced settings).
   - `Network: transports=[...] unmetered=... validated=true`.
   - `DNS <host> OK` and `HTTPS <host> -> 206` (or 200/403) for api.github.com, raw.githubusercontent.com, storage.googleapis.com. Any FAILED line names the exact exception: that host is your blocker.
   - `Manifest OK v3099 ... 460 packs, 1 core ...` plus one `country=xx` line each.
   - `Status=206 ... Real file length=N -> matches size=true/false, matches compressed_size=true/false`. TELL ME these two booleans; they decide the size-verification rule for Phase 1.
   - `Bound OK, pingBinder=true`. `bindService returned false` = signature mismatch or PAM not installed.
   - `pending Superpacks downloads ...: 1 row(s)` while the model is "Updating"; `scheduled Superpacks download job ids: [..]`.
   - Astrea section: `REQUEST url=... headers=[...]`, `RESPONSE ... code=200 ...`, `BODY ... pfdPresent=true`, then either `COMPLETE(pfd) bytes=...` or `FAILED(...)` / `CANCELLED`. Empty = Superpacks never asked Astrea (job not running: check Wi-Fi/charging/battery lines).
2. Tap "Copy" and send me the whole output.
3. "Fix manifest": expect `Trying remote pointer`, ..., `Using ...`, `APPLIED manifest flag`.
4. Updates tab: AMM should say up to date (tag 2.4 = versionName 2.4); Now Playing 1.3.5 likewise.
5. logcat filter (if you ever have it): tags `AstreaHttp`, `AMMShards`, `AMMUpdates`.

Not verified / could not build here: everything below.
- No Gradle build or unit test was run (sandbox has no network). The first Actions run is the first compile; paste me any compile error.
- Which of manifest `size` / `compressed_size` equals the download length.
- How Superpacks inside the closed-source ASI APK selects packs and validates files. I only inferred country matching from the manifest.
- Why the download hangs: the code shows three candidates (requireWiFi default true, an unlogged Astrea failure, the pointer fetch swallowed). The diagnostics decide which.
