# Applying `ambientmusicmod-changes.zip` to AmbientMusicMod (`codyandersan/AmbientMusicMod`)

## 1. Unpack Over the Repository Root
From the root of your `AmbientMusicMod` repository clone (or GitHub Codespaces terminal):

```bash
unzip -o ambientmusicmod-changes.zip -d .
```

*(Note: `DELETED_FILES.txt` is intentionally empty because no existing repository files need to be deleted. You can optionally remove `APPLY.md` and `DELETED_FILES.txt` before committing if you do not want them tracked in git.)*

## 2. Configure GitHub Actions Repository Secrets
In your GitHub repository (`codyandersan/AmbientMusicMod` -> **Settings** -> **Secrets and variables** -> **Actions** -> **New repository secret**), add the following 4 secrets (use the **exact same keystore** for both `AmbientMusicMod` and `NowPlaying` so `com.kieronquinn.app.ambientmusicmod.ACCESS_SERVICE` signature verification succeeds):

- `KEYSTORE_B64`: Base64-encoded `.jks` or `.keystore` file (`base64 -w0 release.keystore`)
- `KEY_ALIAS`: Your signing key alias
- `STORE_PASSWORD`: Your keystore password
- `KEY_PASSWORD`: Your key password

*(Tip: If you need to generate a fresh keystore once in Codespaces or Termux, run:*
`keytool -genkeypair -v -keystore release.keystore -alias amm_key -keyalg RSA -keysize 2048 -validity 10000`
*and then run `base64 -w0 release.keystore` to get `KEYSTORE_B64`.)*

## 3. Commit, Push & Build on GitHub Actions
```bash
git add -A
git commit -m "Implement Phases 0-5: resumable shards downloader, Astrea local cache, diagnostics, SAF import/export, and CI release workflow"
git push origin main
```

Then trigger **Build & Release Ambient Music Mod** from the **Actions** tab (`workflow_dispatch`) or push tag `2.4`:
```bash
git tag 2.4
git push origin 2.4
```
This runs unit tests (`testDebugUnitTest`), builds and signs `assembleRelease` with JDK 17 / Gradle 8.10 / AGP 8.5.2, uploads the signed APK artifact, and publishes a non-draft GitHub Release tagged `2.4`.
