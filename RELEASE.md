# リリース手順 (Release Process)

このドキュメントは、本プロジェクト（`artemis-buffered`）において新バージョンのリリースビルド作成から GitHub Releases への公開までを行う標準手順を定めたものです。
AI エージェント（Codex, Antigravity, Claude 等）が「リリースして」「リリースビルドを作成して」と依頼された場合は、**必ずステップ 6（GitHub Releases への公開）まで完遂**してください。

## 全体フロー
1. バージョン更新 (`app/build.gradle`)
2. 単体テストの実行 (`./gradlew testNonRoot_gameReleaseUnitTest`)
3. リリース APK のビルド (`./gradlew assembleNonRoot_gameRelease`)
4. 成果物の配置と署名・ハッシュ検証・リリースノート作成 (`dist/`)
5. Git コミット・タグ作成・リモート (`fork`) への Push
6. GitHub Releases への公開 (`gh release create`)

---

### ステップ 1: バージョン更新 (`app/build.gradle`)
- `versionCode` をインクリメント（例: `5816` -> `5817`）
- `versionName` を更新（例: `"20.2.6-buffered.16"` -> `"20.2.6-buffered.17"`）

### ステップ 2: 単体テスト実行
```powershell
./gradlew testNonRoot_gameReleaseUnitTest
```
※ 既知の Robolectric テスト失敗 5 件（`LayoutInflationTest`、`SimpleStartupTest`、`StartupTest`、`ProfilesNavigationTest` の 2 件）以外のテストがすべて通過することを確認します。

### ステップ 3: リリース APK のビルド
```powershell
./gradlew assembleNonRoot_gameRelease
```

### ステップ 4: 成果物の配置・検証・リリースノート作成
1. APK を `dist/` へコピー:
   ```powershell
   Copy-Item app/build/outputs/apk/nonRoot_game/release/app-nonRoot_game-arm64-v8a-release.apk dist/artemis-buffered-20.2.6-buffered.<N>-arm64-v8a.apk
   ```
2. SHA-256 ハッシュを計算:
   ```powershell
   Get-FileHash dist/artemis-buffered-20.2.6-buffered.<N>-arm64-v8a.apk -Algorithm SHA256
   ```
3. 署名検証:
   ```powershell
   & "$env:ANDROID_HOME/build-tools/35.0.0/apksigner.bat" verify --print-certs dist/artemis-buffered-20.2.6-buffered.<N>-arm64-v8a.apk
   ```
4. リリースノート作成 (`dist/release-<N>-notes.md`):
   - 変更点
   - 制限事項・注意事項
   - 検証結果（テスト・実機検証など）
   - APK 情報（ABI, Application ID, Version, Source commit, SHA-256）

### ステップ 5: Git コミット・タグ作成・リモートへの Push
※ リモートは `fork` (`https://github.com/dwarfsawman/artemis-buffered.git`) です。
```powershell
git add <変更ファイル>
git commit -m "feat/fix: ... for buffered.<N>"
git push fork main
git tag v20.2.6-buffered.<N>
git push fork v20.2.6-buffered.<N>
```

### ステップ 6: GitHub Releases への公開 (必須)
`gh` CLI を使って GitHub Releases に公開し、APK ファイルをアセットとして添付します。
```powershell
gh release create v20.2.6-buffered.<N> dist/artemis-buffered-20.2.6-buffered.<N>-arm64-v8a.apk `
  --repo dwarfsawman/artemis-buffered `
  --title "Artemis Buffered 20.2.6-buffered.<N>" `
  --notes-file dist/release-<N>-notes.md
```
※ これにより https://github.com/dwarfsawman/artemis-buffered/releases に公開され、ユーザーや外部ツールからダウンロード可能になります。

### Windows x64 ZIP の追加
上記の Android APK 公開手順はそのまま実施します。Release の `published` イベントで `.github/workflows/desktop-windows-x64.yml` が Windows x64 portable ZIP をビルドし、同じ Release に `artemis-buffered-windows-x64-portable-v20.2.6-buffered.<N>.zip` を追加します。Windows ビルドが失敗しても Android APK の公開は取り消されません。workflow を手動実行した場合は Actions artifact として ZIP を取得できます。
