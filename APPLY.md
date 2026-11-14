# Apply: ambientmusicmod-changes.zip (Phase 0 + Phase 5 build plumbing)

1. Unzip over the ROOT of your AmbientMusicMod fork (files land at their repo paths, overwriting).
2. Delete the files listed in DELETED_FILES.txt (none this time).
3. Commit and push everything (including `.github/workflows/build.yml`). You may delete APPLY.md, DELETED_FILES.txt and TESTPLAN.md afterwards.
4. Repo > Settings > Secrets and variables > Actions > add 4 secrets:
   KEYSTORE_B64 (`base64 -w0 your.jks`, any base64 tool/site), KEY_ALIAS, STORE_PASSWORD, KEY_PASSWORD.
   Use the SAME keystore in the Now Playing repo.
5. Actions tab > "Build and release Ambient Music Mod" > Run workflow. Read the log; annotations (red/blue) say exactly what failed.
6. A Release tagged 2.4 is created with AmbientMusicMod-2.4.apk. Install it (uninstall Kieron's AMM first: different signing key).
7. Open the app drawer: a second icon "AMM Diagnostics" appears. Follow TESTPLAN.md.
