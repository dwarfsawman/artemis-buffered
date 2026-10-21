## 変更点

- 「Custom Resolution」の独立した設定項目を廃止し、「画面サイズ」の解像度選択に統合しました。
- 画面サイズの選択ダイアログから解像度を追加・保存できます。保存した解像度は次回以降も選択でき、追加した解像度の削除にも対応します。
- 選択した解像度はプリセットの保存・適用・表示に反映されます。既存のカスタム解像度を引き継ぎ、古いプリセットで選択されている解像度も選択欄に表示します。
- 1440pの直下に2560x1600を追加しました。

## 検証・制限事項

- `testNonRoot_gameReleaseUnitTest`: 111件中106件成功。失敗はRELEASE.mdで許容されている既知のRobolectricテスト5件のみです。変更前でも同じ5件の失敗を確認しました。
  - `LayoutInflationTest.allLayoutsInflateSuccessfully`
  - `SimpleStartupTest.testApplicationOnCreate`
  - `StartupTest.testApplicationStartup`
  - `ProfilesNavigationTest.clickingProfileButton_launchesProfilesActivityFromAppView`
  - `ProfilesNavigationTest.clickingProfileButton_launchesProfilesActivity`
- 解像度の追加・保存・選択・削除、旧設定からの移行、2560x1600の配置を含む関連テスト27件はすべて成功しました。
- `assembleNonRoot_gameRelease`成功。コピー前後のSHA-256一致、APKのバージョン・Application ID・ABIを確認しました。
- `apksigner verify`成功（v1/v2）。署名証明書はbuffered.22と一致します。
- 今回の変更について実機での動作確認は未実施です。追加する解像度はホスト側でも対応している必要があります。

## APK

- File: `artemis-buffered-20.2.6-buffered.23-arm64-v8a.apk`
- ABI: `arm64-v8a`
- Application ID: `io.github.artemisbuffered`
- Version: `20.2.6-buffered.23` / versionCode `5823`
- APK source commit: [`caeffaed665f097897864d4775ef46f62c1c34fb`](https://github.com/dwarfsawman/artemis-buffered/commit/caeffaed665f097897864d4775ef46f62c1c34fb)（リリースタグにはリリースノートの追記を含みます）
- Size: `8,604,761 bytes`
- SHA-256: `93057af78588ae5b2491f538dde58cf63231024ecf0ffcedca4496bf00b115cb`
- Signing certificate SHA-256: `d445070935ceda58ff2e0ecfa7f10b191f2b3a892378f78f86c98bb6cba5666e`
