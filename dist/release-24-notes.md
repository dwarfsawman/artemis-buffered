## 変更点

- Xiaomi Pad 8で、Bluetooth接続した改造済みDeco mini7W V2のペン使用時に出るAndroid側カーソルを、Shizuku経由の直接入力で抑止します。ペンとホバー用の入力ノードだけをEVIOCGRABし、筆圧・傾き・傾き方位をAndroidの再注入経路を通さずホストへ送ります。
- Bluetooth HIDの記述範囲と実座標範囲の不一致を補正し、ペンタブ全域をストリーム全域へ対応させます。
- 別の通常マウスとDecoの相対モードは相対移動量で転送し、Windowsの隣のディスプレイへ移動できます。Pointer Captureはペン保護のため無効のまま維持し、捕捉なし入力のAndroid側基礎感度倍率を補正します。
- 通常マウスのローカルカーソル非表示を改善し、物理キーボードやペンのサイドボタンの既存の転送を維持します。
- フォーカス喪失・アプリ終了・切断時に直接入力を解除します。専用プロセスはアプリからのハートビートが1.5秒途絶えた場合にも占有を解除する構成です。
- 設計・デバイス判定・ライフサイクル・実機検証を[DECO_SHIZUKU_INPUT.md](https://github.com/dwarfsawman/artemis-buffered/blob/v20.2.6-buffered.24/DECO_SHIZUKU_INPUT.md)に記録しました。

## 利用条件・制限事項

- 対象はXiaomi製端末上の改造済みDeco mini7W V2のBluetooth入力です。Xiaomi Pad 8で確認しています。USB接続や他のペンにはこの直接入力を適用しません。
- カーソル抑止にはShizukuの起動とArtemisへの権限付与が必要です。端末再起動後はShizukuを起動し直してください。rootは不要です。
- Shizuku未起動・権限拒否・直接入力の失敗時は通常のAndroid入力へ戻ります。この場合はペンカーソルが残ります。
- カーソル抑止はストリーミング画面で入力取得中に動作します。フォーカス喪失、メニュー表示、ローカルカーソル表示時は占有を解除します。
- 改造ファームウェアがホバーを絶対マウスとして送るため、ホバー中に送られない筆圧・傾きを生成することはありません。接地中のペン情報を保持します。
- 画面間移動にはArtemis側も相対マウスモードにしてください。絶対マウスモードは従来どおりストリーム内に制限されます。
- ホストの画面配置、端末別表示設定、Decoのファームウェアは変更していません。

## 検証

- `testNonRoot_gameReleaseUnitTest`: 123件中118件成功。失敗はRELEASE.mdで許容されている既知のRobolectricテスト5件のみです。
  - `LayoutInflationTest.allLayoutsInflateSuccessfully`
  - `SimpleStartupTest.testApplicationOnCreate`
  - `StartupTest.testApplicationStartup`
  - `ProfilesNavigationTest.clickingProfileButton_launchesProfilesActivityFromAppView`
  - `ProfilesNavigationTest.clickingProfileButton_launchesProfilesActivity`
- 関連24件はすべて成功。全域座標・筆圧・傾き計算、ペンとマウスの切り替え、キャンセルとボタン解放、通常マウスとDeco相対モードの画面端移動・ゼロ移動クリックを含みます。
- `assembleNonRoot_gameRelease`成功。コピー前後のSHA-256一致、バージョン・Application ID・ABI、`libdeco_reader.so`のAPK同梱を確認しました。
- `apksigner verify`成功（v1/v2）。署名証明書はbuffered.23と一致します。
- 同一実装のbuffered.23テストAPKで、ユーザーがXiaomi側カーソル非表示、描画、通常マウスとサイドボタンなどの既存操作、Deco相対モードでのディスプレイ3→4への移動を確認しました。Windows側でも隣の画面へのカーソル移動を記録しました。
- 正式APKはバージョンを更新して再ビルド・検証しています。メインスレッド停止などの故障注入、実機でのSYN_DROPPED発生、端末再起動後の再開は未実測です。

## APK

- File: `artemis-buffered-20.2.6-buffered.24-arm64-v8a.apk`
- ABI: `arm64-v8a`
- Application ID: `io.github.artemisbuffered`
- Version: `20.2.6-buffered.24` / versionCode `5824`
- APK source commit: [`be7df60579f6abc84060af79917bdaf318871864`](https://github.com/dwarfsawman/artemis-buffered/commit/be7df60579f6abc84060af79917bdaf318871864)（リリースタグにはリリースノートの追記を含みます）
- Size: `8,618,209 bytes`
- SHA-256: `737BD0349CB938DCF622C3AA1B9B14A3C77AA1ABEA502BFF76EBC1A12B4DF6FA`
- Signing certificate SHA-256: `d445070935ceda58ff2e0ecfa7f10b191f2b3a892378f78f86c98bb6cba5666e`
