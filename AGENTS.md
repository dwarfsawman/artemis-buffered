# Instructions for AI Agents (AGENTS.md)

This project is an experimental fork of Artemis / Moonlight Android (`artemis-buffered`).

## Release Instructions
When asked by the user to "release" or "create a release" (e.g. 「リリースして」「リリースビルドを作って」):
Do **NOT** stop at local build/dist placement. You **MUST complete all steps through step 6** detailed in [RELEASE.md](RELEASE.md):

1. Increment `versionCode` and update `versionName` in `app/build.gradle`.
2. Run unit tests (`./gradlew testNonRoot_gameReleaseUnitTest`).
3. Build release APK (`./gradlew assembleNonRoot_gameRelease`).
4. Copy APK to `dist/`, verify SHA-256 and APK signature, create `dist/release-<N>-notes.md`.
5. Commit, tag (`v20.2.6-buffered.<N>`), and push commits + tags to the `fork` remote (`https://github.com/dwarfsawman/artemis-buffered.git`).
6. Publish to **GitHub Releases** using `gh release create`:
   ```powershell
   gh release create v20.2.6-buffered.<N> dist/artemis-buffered-20.2.6-buffered.<N>-arm64-v8a.apk `
     --repo dwarfsawman/artemis-buffered `
     --title "Artemis Buffered 20.2.6-buffered.<N>" `
     --notes-file dist/release-<N>-notes.md
   ```
