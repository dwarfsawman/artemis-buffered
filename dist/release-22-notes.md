## 変更点

- Deco mini7W V2など、Androidがマウスとペンの両方として扱う外付けペンタブの接続中は、ポインター捕捉を解除します。通常のマウスやキーボードを併用した場合も、ペンタブの描画入力が止まらないようにしました。
- Android 15以降で外付けペンタブの傾き方向が失われる入力経路に対応しました。既存のアクセシビリティサービスで変換前の入力を受け取り、画面の回転を補正してPCへ送信します。正常な方向値は保持します。
- 外付けペンタブの方向取得にShizukuやrootは不要です。Androidの設定で「Artemis Buffered Physical Keyboard」を有効にしてください。既存のWinキー転送は維持しています。
- ペンの接続・切断と画面のフォーカスに合わせて入力経路を切り替え、ストリーミング画面から離れるとマウス入力の受け取りを解除します。

## 制限事項

- 実機確認はXiaomi Pad 8 ProとBluetooth接続のDeco mini7W V2、WindowsのApollo/Kritaで行いました。他の端末・ペンタブでの同等動作は未確認です。
- 変換前の方向を受け取る経路はAndroid 15以降の標準APIを利用します。アクセシビリティサービスを有効にする必要があります。
- Kritaのブラシで報告された見た目の90度差は、ブラシ設定との関係を含めて未解決です。カーネルの傾きX/YとPC受信値の照合では、転送中の一定の90度ずれは見られませんでした。
- Totemのマウスキーでポインターは動くがクリックが効かなくなる症状を確認しました。Artemisの再接続で復帰し、Deco併用中のクリックも確認できましたが、原因の特定と恒久対策は未完了です。

## 検証

- `testNonRoot_gameReleaseUnitTest`: 107件中102件成功。失敗はRELEASE.mdで許容されている既知のRobolectricテスト5件のみでした。追加したペン入力関連の12件はすべて成功しました。
  - `LayoutInflationTest.allLayoutsInflateSuccessfully`
  - `ProfilesNavigationTest.clickingProfileButton_launchesProfilesActivityFromAppView`
  - `ProfilesNavigationTest.clickingProfileButton_launchesProfilesActivity`
  - `SimpleStartupTest.testApplicationOnCreate`
  - `StartupTest.testApplicationStartup`
- `assembleNonRoot_gameRelease`成功。APK内のApplication ID、versionCode、versionName、ABIと、コピー前後のSHA-256一致を確認しました。
- `apksigner verify`成功（v1/v2）。既存buffered.21 APKと署名証明書が一致することを確認しました。
- 同じ入力修正を含む実機版で、Decoの描画・筆圧・傾き方向のPC転送を確認しました。カーネルとPCの入力7,530件を対応づけ、方向差の中央値は約0.48度でした。
- 再接続後の採取では、マウス押下・解放23組とペン入力1,974件を確認しました。ペン使用後もTotemのクリックをGPT/Kritaが受け取ることを確認しました。

## APK

- File: `artemis-buffered-20.2.6-buffered.22-arm64-v8a.apk`
- ABI: `arm64-v8a`
- Application ID: `io.github.artemisbuffered`
- Version: `20.2.6-buffered.22` / versionCode `5822`
- APK source commit: [`1f090867bbedfa484a23a666764258be3858f148`](https://github.com/dwarfsawman/artemis-buffered/commit/1f090867bbedfa484a23a666764258be3858f148)（リリースタグにはリリースノートのみの追記を含みます）
- Size: `8,598,817 bytes`
- SHA-256: `3b19e6cab023c558f3a0c20f7256fa266cd0a3184a54aa151d8814a52b19e5d4`
- Signing certificate SHA-256: `d445070935ceda58ff2e0ecfa7f10b191f2b3a892378f78f86c98bb6cba5666e`
