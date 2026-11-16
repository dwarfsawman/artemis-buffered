# Xiaomi Pad 8: ShizukuによるDecoペンカーソルの抑止

## 目的と対象

改造済みDeco mini7W V2のBluetooth接続で、Xiaomi側の狭い領域に出るペンカーソルを抑止する。全域座標・筆圧・傾き・傾き方位と、別マウスの相対入力・物理キーボードのWinキー転送を維持する。Legion Tabやホストの画面設定、Decoのファームウェアは変更しない。

Xiaomi製端末、Android 8以降、VID/PID `28bd:0949`、Bluetoothの改造用HID記述だけを対象にする。USBや他のペンには自動適用しない。Shizuku未起動・権限拒否・直接入力の失敗時には従来のAndroid入力を使用する（この場合ペンカーソル抑止はできない）。Shizukuは端末の再起動後に起動し直す必要がある。

## 通常のカーソル非表示では解決しない理由

以下は先行する実機OS解析の記録に基づく設計上の前提。今回すべてのOSバイナリを再解析したという意味ではない。

- Xiaomiの外付けペンはInputReaderのPOINTERモードで、通常マウスとは別のDrawingTabletControllerが描画する。マウス用TYPE_NULLだけでは消えない。
- Pointer Captureを有効にすると、この端末の外付けペン用TouchInputMapperが無効になり、ペン入力が停止する。
- アクセシビリティによるペン入力の消費とView側のPointerIcon更新は両立せず、消費を外すと他のアクセシビリティサービスによる再注入で傾き方位が失われる。
- motion observingの設定はアプリ内で保持できてもOS側で0に戻された（過去のv3試作は撤去済み）。Xiaomi固有のhideCursorもシステムアプリ限定で、ペン用Controllerを対象にしない。

したがって、ペン入力をAndroidのInputReaderに渡す前に占有し、Artemisから直接ホストへ転送する。

## 入力ノードの分離

`/dev/input/eventN`の番号は固定しない。実機で2026-11-16に確認した構成:

| 当日のパス | 名前 | 扱い |
| --- | --- | --- |
| event23 | Deco mini7W V2 Pen | 占有・読み取り。ABS_X/Y、筆圧、傾き、接地・ツール・ペンボタン |
| event21 | Deco mini7W V2 | 同じBluetooth機器の補助ノードとして占有・読み取り。ホバー座標・マウスボタン・ホイール |
| event22 | Deco mini7W V2 Keyboard | 対象外。既存のキーボード転送を維持 |
| event24 | Deco mini7W V2 Mouse | 対象外。相対モードの入力を維持 |
| 別マウスのノード | TOTEMなど | 対象外。既存の相対移動・速度補正を維持 |

ペンの名前・BUS_BLUETOOTH・VID/PID・ABS/KEY/REL能力・座標/筆圧範囲を検証する。補助ノードは名前と同じ識別条件に加え、ペンと同じ空でないEVIOCGUNIQを要求する。KEY_AやREL_X/Yのあるノードは対象外。同一機種を複数接続しても別機器の補助ノードと混在させない。

改造ファームウェアの`payload/src/main.rs::ble_hook_inner`は、接地時にreport 7、ホバー・割り当て済みサイドボタン時にreport 9（絶対マウス）を送る。event23だけではホバー座標が取得できないため、event21も扱う。ホバー時の筆圧・傾きはファームウェアから送られないため生成しない。ペンとして得られる接地中の情報を保持する。

## 実装とプロトコル

- `app/src/main/jni/deco_reader/deco_reader.c`: Shizukuのshell権限で動く専用プロセス。root向けの既存evdev readerとは分離し、両フレーバーに`libdeco_reader.so`というPIE実行ファイルとして梱包する。
- `ShizukuDecoCapture.java`: Shizuku起動・権限を確認し、専用プロセスを起動する。新規ライブラリは不要。既存のShizuku API 13.1.5の`newProcess`を利用し、R8から保護する。このAPIは非推奨なので、API 14への更新時にはUserServiceへの移行が必要。
- `DecoEvdevStream.java`: evdevのSYN_REPORT単位の状態から、ホスト向けペン・マウスイベントを生成する。Androidへ再注入しない。
- `Game.java`: ストリーミング中・フォーカスあり・Activity再開中・入力取得有効・ローカルカーソル非表示・メニュー閉鎖中だけ開始する。ペン非対応ホストでは従来同様マウスへフォールバックする。

### Deco自体の相対モードで隣のディスプレイへ移動する

ファームウェアの相対モードはevent24のREL_X/Yを使う。Androidではevent21とevent24が同じInputDevice（当日Device 46、名前`Deco mini7W V2`、sources=MOUSE | ROTARY_ENCODER）になる。ペン用のInputDevice（Device 47、名前末尾Pen、sources=KEYBOARD | MOUSE | STYLUS）とは別である。

既存のVID/PIDによる描画タブレット判定を相対入力の除外にも使っていたため、この入力がrelative=falseとなって配信画面内へ固定されていた。`ExternalStylusSupport.isDecoRelativeMouse()`で、VID/PID・機器名・MOUSEあり/STYLUSなしを満たす相対マウス側を区別し、`AndroidNativePointerCaptureProvider`から通常マウスと同じAXIS_RELATIVE_X/Y経路へ送る。ペン保護のPointer Capture抑制は維持する。ゼロ移動のクリック・スクロールでも絶対位置へ戻さない。端末側感度の補正も通常マウスと共通。

Artemisの絶対マウスモードを設定で有効にした場合は、従来どおり相対移動を配信画面内の絶対位置へ変換する。画面間移動の実機確認ではArtemis側も相対モードを使う。

標準出力の行プロトコル（ASCII、最大160文字）:

```text
READY
P x y pressure tiltX tiltY touch pen rubber stylusButtons
M x y mouseButtons wheel
RESET
```

`READY`は両ノードのgrab成功後のみ送る。Pの座標を`17797 × 11109`、筆圧を`8191`で正規化し、0〜1に制限する。Mの座標は`32767 × 32767`で正規化する。X/Yはタブレット全域からストリーム全域へ直接対応し、画面の回転で再回転させない。傾き方位も同じタブレット座標系を使用する。

傾き計算は[AOSPのtouch tilt校正](https://source.android.com/docs/core/interaction/input/touch-devices)に従い、`orientation = atan2(-sin(tiltX), sin(tiltY))`、`tilt = acos(cos(tiltX) * cos(tiltY))`とする（入力を度からラジアンへ変換）。方位は0〜359度、傾きは0〜90度で送る。ホバーへの移行時にはペンのUP/LEAVEを送り、サイドボタンから接地へ移る際にはマウスボタンを解放する。ホイールだけのレポートで描画やカーソル位置を変えない。

SYN_DROPPEDではRESETを送り、ジェスチャーをキャンセルしてEVIOCGABS/EVIOCGKEYから状態を再同期する。grab前にキューに入っていたAndroidの同じDecoペン用InputDeviceのMotionEventは直接入力が有効な間だけ捨てる。キーイベントと、event24に由来する相対マウスイベントは対象外。

## 解除と障害時の動作

- フォーカス喪失・onPause・入力解放・切断・終了・Shizuku Binder deathで子プロセスのパイプを閉じて終了させる。ホストにもペンキャンセル・ホバー退出・押下中のマウスボタン解放を送る。
- メインスレッドから250ms間隔でハートビートを送る。子プロセスは1500ms受信がなければ両fdを閉じ、EVIOCGRABを解除する。アプリの異常終了や停止時にも占有し続けない。
- stdin EOF/HUP、SIGTERM/SIGINT、デバイス切断、stdoutの詰まり・書き込み失敗でも解除する。プロセス強制終了時もカーネルがfdとgrabを解放する。
- 片方のgrabに失敗した場合、成功していた側も解除する。通常入力へ戻り、3秒後から再試行する。
- 通常の設定ファイルやSELinuxポリシー、入力デバイスのパーミッションは変更しない。

Shizukuの実行方式については[公式APIソース](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/Shizuku.java)も参照。

## 検証記録

2026-11-16、Xiaomi Pad 8（25091RP04G / piano_global）:

- ADB shell uid=2000で、専用readerによるevent23とevent21の検出・EVIOCGRAB成功・P初期状態の読み取り・stdin終了後の解除を確認。
- APKへの`lib/arm64-v8a/libdeco_reader.so`同梱と、インストール先nativeLibraryDirでの実行を確認。Shizuku経由でもuid=2000で両ノードをgrabし、アプリの`Direct pen + hover capture active`を確認した。
- ユーザーがXiaomiカーソルの消失と描画の動作を確認。通常マウスの移動・クリック、ペンのサイドボタン、物理キーボードを含む追加確認への回答は「問題なく使える」だった。
- ホバー時のMフレーム、接地時のPフレームで非ゼロの筆圧・傾きX/Yを実測。アプリを離れると`Direct capture released`となり、子プロセスも終了することを確認。
- 新規の`DecoEvdevStreamTest`6件成功。全体122件中117件成功、5件失敗はRELEASE.mdの既知のLayoutInflationTest、SimpleStartupTest、StartupTest、ProfilesNavigationTestの2件と一致した（相対モード修正前の全体実行）。
- 途中の試作ではShizukuのprivateメソッドへのアクセス許可不足と、NDKの既存構成キャッシュに新規readerが反映されない問題があった。`setAccessible(true)`、R8 keep規則、最上位Android.mkの更新による構成再生成で修正した。Javaビルド成功だけではreaderの同梱を保証できないため、APKのZIPエントリと実機上のファイルを必ず検証する。
- Deco相対モードは当初Device 46が`relative=false`となり画面間移動できないと報告された。専用判定と回帰テストの追加後は`relative=true / capture=false`となり、AndroidカーソルがX=3199の画面端にあるときもdx=1を取得できた。
- ユーザーがDecoの相対モードでディスプレイ3から4へ移動できることを確認した。Windows側でも`\\.\DISPLAY26`（論理座標X=0..1438）から隣の`\\.\DISPLAY27`（X=1439..2238）へのカーソル移動を記録した。例: `(1453,553)`、`(1521,427)`。ホストの画面配置と端末別表示設定は変更していない。
- 故障注入によるメインスレッド停止、SYN_DROPPEDの実機発生、端末再起動後の再開は未実測。1500msのwatchdogはネイティブ実装で、RESET時のペン・ボタン解放は単体テストで検証した。

実装時のテストAPK（相対モード修正を含む）:

- 関連24件成功（DecoEvdevStream 6、AndroidNativePointerCaptureProvider 7、ExternalStylusSupport 7、KeyboardAccessibilityService 4）。端での非ゼロ移動、ゼロ移動クリック、ペン用InputDeviceの除外を回帰テストで確認。
- `assembleNonRoot_gameRelease`成功。APKのreader同梱・署名検証成功。Xiaomi Pad 8へ上書きインストール済み。
- `dist/artemis-buffered-20.2.6-buffered.23-deco-shizuku-test-arm64-v8a.apk`
- Version: `20.2.6-buffered.23` / `5823`、ABI: arm64-v8a、Application ID: `io.github.artemisbuffered`
- SHA-256: `E21EB054B36FA431AD0ED36540046E50773162104AF54D2E3BC8A372B232429E`
- ログ: `out/deco-shizuku-tests.log`、`out/deco-shizuku-relative-build.log`、`out/deco-shizuku-device.log`、`out/deco-shizuku-device-final.log`、`out/deco-shizuku-input-active.txt`、`out/deco-shizuku-relative-cursor.csv`、`out/deco-shizuku-signature.log`。`out/`とAPKは既存のignore対象。
- `adb shell setprop log.tag.ArtemisDeco DEBUG`で250ms間隔の生状態フレーム、`log.tag.ArtemisMouse DEBUG`で相対経路と感度補正の診断を有効化できる。通常利用時は両方INFOに戻す。

上記はbuffered.23の番号を維持したテストAPKの記録。その後のリリース依頼により、本対応を`20.2.6-buffered.24` / versionCode `5824`として公開する。正式APK・署名・ハッシュ・全体テスト結果は[release-24-notes.md](dist/release-24-notes.md)に記録する。
