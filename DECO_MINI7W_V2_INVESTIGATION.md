# Xiaomi Pad 8 Pro / Deco mini7W V2 入力調査

調査日: 2026-10-11（日本時間）

## 結論

最新の修正では、Bluetooth接続のDecoについて、Shizukuなしで傾きの方向をPCへ送信できた。既存のArtemisキーボード用アクセシビリティサービスで、Androidが方向を消去する前の `onMotionEvent()` を受け取り、アプリ内のストリーミング画面へ直接渡す。Winキーの処理も保持している。

問題は2つあった。Pointer CaptureによるPOINTER入力の停止と、外付けペンの方向対応フラグ不足によるAndroidの方向0上書き。後者はアクセシビリティ経由の再投入時に保存座標も失う。端末搭載ライブラリと物理入力で原因を特定した。Linuxカーネルは傾きX/Yを取得できている。

修正版を端末へ上書きし、Decoだけの操作でApolloの方向が0〜359度、傾き量が0〜69度へ変化した。追加の同時記録では、カーネルの傾きX/Yから求めた方向とPCの受信方向が一致した。ユーザー報告の「90度ずれて見える」点はKritaまで含めて確認中。

以下は調査の経過を時点ごとに記録したもの。途中の未確定事項やShizuku試作は、末尾の追加検証結果で更新している。

当初の描けない問題では、ArtemisのAndroidネイティブマウス捕捉（Pointer Capture）が、Android 16上で外付けDecoの入力を無効化していた。実機のArtemis接続中に、Decoの2つの入力デバイスが `Touch Input Mapper (mode - DISABLED)` へ切り替わったことを確認した。PCへの転送より前、Androidの入力処理で停止していた。

## 確認環境

- Xiaomi Pad 8 Pro / Android 16
- `io.github.artemisbuffered` / `20.2.6-buffered.21` / versionCode 5821
- Deco mini7W V2 / Bluetooth接続
- ユーザー報告: Xiaomi Pad内では動作。Artemis経由では描けず、PCのポインターも動かない（当初の「ポインターは動く」という回答は訂正済み）。Focus PenではPC側のペン入力が可能。

## 実機の証拠

`adb shell dumpsys input` / `adb shell getevent -lp` / InputReaderのログを読み取り確認した。

通常時のDeco Penは `KEYBOARD | MOUSE | STYLUS`、DeviceTypeは `POINTER`。カーネル入力には接地の `BTN_TOUCH`、筆圧の `ABS_PRESSURE`（0〜8191）、傾きの `ABS_TILT_X/Y` が存在する。Bluetooth接続でも端末はペン情報を認識している。

Artemisのストリーミング画面では、次の状態を確認した。

```text
Pointer Capture: Enabled, seq=47
Current Window with Pointer Capture: ... io.github.artemisbuffered/com.limelight.Game
Device 34: Deco mini7W V2 Pen
  Sources: KEYBOARD | MOUSE | STYLUS
  Touch Input Mapper (mode - DISABLED):
    DeviceType: POINTER
```

InputReaderは同じ切替時に次を記録した。通常のDecoデバイスもDISABLEDになっている。

```text
Touch device 'Deco mini7W V2 Pen' has invalid parameters or configuration. The device will be inoperable.
Device reconfigured: id=34, name='Deco mini7W V2 Pen', ... mode DISABLED
```

その後の捕捉解除状態では、同じデバイスが復帰した。

```text
Pointer Capture: Disabled, seq=48
Device 33: Deco mini7W V2
  Touch Input Mapper (mode - POINTER):
Device 34: Deco mini7W V2 Pen
  Touch Input Mapper (mode - POINTER):
  PRESSURE: source=MOUSE | STYLUS, min=0.000, max=1.000
```

捕捉解除後、ユーザーから「描けるようになった」と回答があり、PC側の描画復帰も確認できた。その後の筆圧・傾きの実測結果は下記を参照。

## コード上の理由

`app/src/main/java/com/limelight/binding/input/capture/AndroidNativePointerCaptureProvider.java`:

- `hasCaptureCompatibleInputDevice()` はSOURCE_MOUSE等を持つデバイスがあると捕捉可能と判断する。
- 既存のSOURCE_TOUCHSCREEN除外は画面一体型ペン向けであり、DecoのようなSOURCE_MOUSEとSOURCE_STYLUSを持つ外付けペンには適用されない。
- `hideCursor()`、フォーカス復帰、入力デバイス追加等で `requestPointerCapture()` を呼ぶ。捕捉は特定のマウスだけに限定されないため、別のマウスを接続している場合にもDecoが影響を受ける。
- `showCursor()` は `releasePointerCapture()` を呼ぶ。

[Android 16公式ソースのTouchInputMapper::configureInputDevice](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/inputflinger/reader/mapper/TouchInputMapper.cpp): DeviceType::POINTERはPointer Captureが無効な場合にPOINTERモードへ入る。捕捉中、画面直接入力でもTOUCH_NAVIGATIONでもないデバイスはDISABLEDに入る。実機で確認した状態と一致する。

Focus Penの画面入力は `TOUCH_SCREEN` 経路であり、この外付けPOINTERの条件とは異なる。Focus Penが動作することとDecoが停止することは矛盾しない。

## 回避策と修正方針

接続中のメニューで `Select Mouse mode` → `Toggle local mouse cursor(physical mouse needed)` を選び、ローカルカーソルを表示する。これにより捕捉を解除できる。この操作でPC側の描画が復帰したことをユーザーが確認済み。再接続時の初期状態は別途確認が必要。

恒久修正の候補は、外付けペン（SOURCE_MOUSEとSOURCE_STYLUSを持つ画面非一体型のデバイス）が存在する間、ネイティブPointer Captureを抑制すること。別マウス併用、接続途中のペンタブ追加、デバイス変更通知、ウィンドウフォーカス復帰時も同じ判定が必要。座標は画面に対応した通常のペンイベントで送る。

## 筆圧・傾きの実測（同日の追加調査）

ユーザーがDecoを動かし、垂直・右方向・手前方向へ傾けて短い線を描く操作を実施した。

- Androidのカーネル入力では `ABS_TILT_X/Y` の値が実際に変化した。最初の記録ではXは-1〜15、Yは-29〜15。記録開始前の読み取りでもY=-52を確認した。
- Android InputReaderの処理済み入力では、`orientation=2.976` ラジアン、`tilt=0.106` ラジアン、`pressure=0.711` の例を確認した。角度だけでなく方向もAndroid内部には存在する。
- PC側のApollo受信ログでは、操作後に採取した1,491件のpen packetで `tilt` は0〜60、筆圧は0〜0.336345。傾き角度と筆圧はPCへ届いている。同じ記録の `rotation` は全件0だった。
- 例: `2026-10-11 07:18:30.957` のペン移動パケットは `tilt [60]`、`pressureOrDistance [0.0528629]`、`rotation [0]`。
- したがって「傾き角度の転送」は実測確認済み。「傾けた方向の正しい転送」は確認できず、今回の記録では0固定。方向が失われる具体的な処理箇所は未特定。
- Kritaを開いてタブレットテスターを確認し、ユーザーが左側のテスト領域で描画した。追加された記録は `Mouse press/move/release` で、Tabletイベントの筆圧・TX/TYは出ていない。設定ダイアログではWinTabが選択されていた。
- ApolloのWindows実装は受信した傾きと方向からWindowsのtiltX/Yを計算し、合成ペン入力として注入する。Kritaでは `Windows 8+ Pointer Input (Windows Ink)` を使用する設定が必要。設定画面にもAPI変更にはKrita再起動が必要と表示されている。Kritaには未保存の絵があるため、今回API設定変更・再起動は行っていない。

参考: [Krita公式タブレット設定・テスター説明](https://docs.krita.org/en/reference_manual/preferences/tablet_settings.html)、[ApolloのWindowsペン入力実装](https://github.com/ClassicOldSong/Apollo/blob/master/src/platform/windows/input.cpp)。公式ソースは現在の実装説明として参照し、ローカルで動作中のApolloバイナリと完全に一致することは検証していない。

筆圧・傾きの受信記録は `C:/Users/raven/AppData/Local/Temp/artemis-deco-investigation/` に保存した。全アプリのログではなく、Decoの入力と関連するペンパケット・Kritaテスターの文字情報を記録した。

今回、アプリのソース変更・ビルド・インストール・リリースは行っていない。

## Windows Ink変更後の再確認

ユーザーがWindows Inkへ変更してKritaを再起動した後、2026-10-11に再確認した。Kritaの設定画面で `Windows 8+ Pointer Input (Windows Ink)` の選択を確認し、`AppData/Local/kritadisplayrc` でも `useWin8PointerInput=true` を確認した。

テスターに残った285件のStylus筆圧・傾き記録を集計すると、筆圧は0〜55.9%、TXは全件0、TYは1〜38度だった。したがってWindows Inkへの切り替えでペン情報の受信は復帰している。ただし、角度のみを受信し、傾けた方向を正しく再現できない状態が残る。テスター文字情報を `krita-windows-ink-tester.txt` として調査用一時ディレクトリへ保存した。

当時の画面の選択ブラシは `e) Marker Plain (mypaint)`。インストール済みの標準 `.myb` を読み取り確認すると、動的入力は不透明度の `pressure` とブラシ角度の `direction`（線を描く方向）だけだった。ペンの傾きに相当する `declination` / `ascension` は設定されていない。標準設定のままなら傾けてもブラシの見た目は変わらない。これは方向転送の問題とは別に確認が必要なブラシ設定の要因であり、現在のブラシに未保存の編集がある場合まで検証したものではない。

### 方向0固定の原因候補

Android 16公式 `TouchInputMapper::dispatchPointerStylus()` は、処理済みの傾き・方向を `dispatchPointerSimple()` に渡す。しかし `dispatchPointerSimple()` で生成する `NotifyMotionArgs` はflagsが0で、方向対応を示す `AMOTION_EVENT_PRIVATE_FLAG_SUPPORTS_ORIENTATION` 等を付けていない。一方、通常の画面タッチ経路では `dispatchMotion()` がそのflagsを付ける。

Android 16公式 `libs/input/Input.cpp` の `transformOrientation()` は、方向対応flagsが無い場合0を返す。Artemisの `Game.getRotationDegrees()` は `event.getOrientation()` の値を送信するので、この公式実装では外付けPOINTERペンの生の方向値が非ゼロでも送信方向は0になる。実測した「Android内部の方向は非ゼロ、Apollo rotationは全件0、Krita TXは0」の組合せと一致する。

これは公式Android 16コードからの原因推定。Xiaomi端末に搭載されたネイティブ実装や、Artemisが受信したMotionEventのprivate flagsそのものを計測して確定したものではない。恒久対策にはこの経路の実機検証と、方向を保持するAndroid側修正またはArtemis側回避策が必要。KritaのAPIを変更するだけでは方向欠落は復元できない。

参考: [Android 16 Input.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/libs/input/Input.cpp)、[Android 16 TouchInputMapper.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/inputflinger/reader/mapper/TouchInputMapper.cpp)、[KritaのMyPaint動的入力](https://docs.krita.org/en/reference_manual/brushes/brush_engines/mypaint_engine.html)、[Kritaの傾きセンサー](https://docs.krita.org/en/reference_manual/brushes/brush_settings/tablet_sensors.html)。

## Focus Penでの対照確認

ユーザーから「Focus Penでも傾きが入力されていなさそう」と報告を受け、2026-10-11 07:32頃にFocus Penだけの傾け操作を依頼して入力を採取した。Focus Penの傾きはKritaまで届いていることを確認した。Decoの方向0固定とは異なる結果になった。

- 画面用ペンデバイス `NVTCapacitivePenP81c` はDIRECT経路で、`TILT` と `ORIENTATION` のMotion Rangeが存在する。別の `NVTCapacitivePenM80p` も登録されているが、今回実際に入力が出たのはP81c側だった。Bluetoothデバイス名には `Xiaomi Focus Pen Pro` がある。製品の正確な型番は現物照合していない。
- `/dev/input/event13` のカーネル入力では、記録途中の集計で `ABS_TILT_X=-38〜53`、`ABS_TILT_Y=-52〜68` と変化した。event12側には今回の記録で入力が出なかった。
- 同時刻のApollo受信ログから4,934件のペンパケットを採取し、`tilt=0〜68`、`rotation=0〜359` を確認した。Focus Penでは傾きの量も方向もPCへ届いている。hover時の `pressureOrDistance` は距離を表すため、同フィールドの最大値を筆圧の最大値とは扱わない。
- KritaのWindows Inkテスターの読み取り可能な記録279件では、`TX=2〜41`、`TY=-9〜-1`、`P=0〜36%` と変化した。両軸の傾きが描画ソフトまで到達している。テスター全履歴の完全取得ではなく、UIから読み取れた範囲の集計。
- Focus Penで「見た目が傾きに反応しない」ことを、傾きが転送されていない証拠とは扱えない。ブラシが傾きを使うセンサー設定になっているか、別途確認が必要。Focus PenにもAndroidの外付けPOINTER経路の方向0問題があるとは結論しない。
- 今回のFocus Penテスト時の画面では `e) Marker Dry` が選択されていた。インストール済み標準プリセットは `RotationSensor=ascension`（傾き方向）を使い `PressureRotation=true` だが、`PressureSize=false` なので太さを傾きに連動させない。以前の `Marker Plain (mypaint)` と同一のプリセットではない。
- 同じPCにある標準 `c) Pencil-5 Tilted` は `SizeSensor=declination`（傾きの仰角）と `RotationSensor=ascension` を持ち、`PressureSize=true` / `PressureRotation=true`。Focus Penで傾きに応じた太さと方向の変化を確認する比較用プリセットとして使える。今回このプリセットへ切り替えて描画した結果までは確認していない。

ユーザーからテスト完了の「試しました」という回答を受領した。収集した記録はFocus Pen操作と対応する。

証拠: 調査用一時ディレクトリの `focus-tilt-events.txt`、`focus-m80p-tilt-events.txt`、`focus-apollo-pen-packets.txt`、`krita-focus-pen-tester.txt`。この対照検証でもArtemis/Apolloのソース変更・更新・リリースは行っていない。

参考: [Android InputDevice仕様（外付けペンタブはSOURCE_MOUSEとSOURCE_STYLUSを併用）](https://developer.android.com/reference/android/view/InputDevice)、[XP-Pen公式製品ページ（AndroidのBluetooth対応）](https://www.xp-pen.com/product/deco-mini7w-v2.html)。

## Deco対応の実装・検証（2026-10-11追加）

ユーザーがPencil-5 Tiltedへの変更後にFocus Penの傾き反映を確認し、Decoの対応を依頼したため、以下の修正を実装した。上記の「ソース変更なし」は各調査時点の記録。

- 外付けペンタブ（MOUSE + STYLUS、TOUCHSCREENなし）が接続されている間、ネイティブPointer Captureを抑制する。別のマウスがデバイス一覧の先頭にあっても抑制し、接続・取り外し・デバイス変更・フォーカス復帰で再評価する。カーソル表示中やフォーカス喪失後の遅延処理では再捕捉しない。
- Android 15以降で外付けペンの方向が0になる場合、MotionEventの一時コピーだけを非ポインターのSOURCE_JOYSTICKに変更し、保持されているAXIS_ORIENTATIONを読む。InputDispatcherのウィンドウ座標への逆回転を補正し、角度を正規化する。元イベントのsource、座標、筆圧、傾き量、履歴は変更しない。画面一体型のFocus Penや正常な非ゼロ方向は従来経路を使う。
- `Game`のペン方向送信でこの処理を使う。傾き量・筆圧の既存の転送処理は維持する。

検証結果:

- Robolectricの追加7件（ペンタブ判定、方向回復と4回転、Focus Pen等の既存経路、コピーの解放、別マウス併用、ペンタブの接続・取り外し、画面ペンとマウスの併用）が通過。
- 全体は102件中97件通過、5件失敗。失敗はRELEASE.mdに記載された既知のLayoutInflationTest、SimpleStartupTest、StartupTest、ProfilesNavigationTestの2件と一致。
- Xiaomi PadのAndroid 16実機で、方向対応のprivate flagsがないMotionEventを公開APIだけで構成した。通常のgetOrientation()は0になるが修正は元の0.7radを回復し、4方向の画面回転すべてで方向対応flagsを持つ正常イベントと0.0001rad以内で一致。元イベントを保持することも確認した。これは実機のネイティブAPI検証であり、物理DecoからKritaまでの確認とは区別する。
- `assembleNonRoot_gameRelease`成功。旧SDKパスを参照する生成済みネイティブキャッシュをapp/build内で退避し、現在のSDK/NDKで再生成した。
- インストール前のAPKを調査用一時ディレクトリへバックアップ。旧APKと新APKの署名SHA-256一致、署名検証成功。設定を保持する`adb install -r`でXiaomi Padへ上書き成功。
- 診断用ビルドのversionNameは20.2.6-buffered.21、versionCode 5821。APK SHA-256は`EF59C56CD780047FB35CA99C51FA2884BA746028907C13CA90A7EA927EADA696`。公開リリース・タグ・pushは実施していない。
- ネイティブ検証専用アプリは試験後にアンインストールした。

物理DecoでArtemisへ再接続し、Kritaで両軸の傾きを確認する試験はユーザーの操作待ち。更新直後のPointer Capture解除だけではストリーミング中の自動抑制の証明とは扱わない。

証拠は調査用一時ディレクトリの`native-probe/result.txt`、`deco-fix-build.log`、`deco-fix-assemble.log`、`input-after-deco-install.txt`、`artemis-before-deco-fix.apk`。

### 物理Decoの試験結果と追加対応

ユーザーの再接続・傾け試験を受領した。Gameが前面のストリーミング中にPointer Capture Requested=false、捕捉先Noneを確認し、入力が継続した。一方、最初の修正版のApollo記録はrotation=90固定、tilt=0〜70だった。API検証で有効だった一時コピーによる回復は、この端末の物理Decoイベントには効かなかった。方向を回復できたという完了判定は行わない。

追加の直接診断では55件の外付けペンイベント（deviceId=34、source=0x6002）で、通常の方向も一時コピーで読み取る生の方向も全件0だった。傾き量は変化した。Androidのカーネル・InputReaderには方向があるのに、アプリが受信するイベントの格納値から既に消えている。OS/OEMの具体的な消去箇所は未確定。生の0を回転させて90度を作ってしまわないガードを追加した。

Xiaomi PadにはShizukuが既にインストールされ、shell権限のshizuku_serverが動作している。Artemisも既存の全画面機能用にShizuku API 13.1.5とProviderを使用している。これを使うUserServiceを追加し、デバイス名を一致照合した外付けペンのevdevだけを読み、ABS_TILT_X/Yを補助方向として使う試作を実装した。Binder経由でアプリへ渡し、250msを超える古い値は使わない。アプリの一時停止、端末切断、Activity破棄時に読み取りを停止する。Shizukuが起動済みで、ユーザーが権限を許可している場合だけ利用できる。通常の画面ペンには適用しない。

この試作は追加3件のデコーダーテストを含む計10件の関連テストとassembleNonRoot_gameReleaseが成功。Shizuku方式のAPKはユーザーが「Shizukuは必要か」と質問した時点ではまだインストールしておらず、物理検証は未実施。ユーザーにBluetoothのShizuku方式を継続するか、Shizukuなしを優先してUSB接続を調査するか確認中。

Shizukuは機器そのものの要件ではなく、このBluetooth/Androidイベント経路で失われる値を回避するための実装上の手段。USB Host APIから直接HIDレポートを読む方式は別の候補だが、USB接続の実機・レポート形式・傾き転送はまだ検証していない。USBを使えば既存コードのまま必ず解決するとは説明しない。

追加証拠: `input-deco-fixed-stream.txt`、`deco-fixed-apollo-pen-packets.txt`、`deco-diagnostic-event-log.txt`、`deco-shizuku-test-build.log`。

### 消失処理の特定（2026-10-11、Winキー用アクセシビリティを維持）

ユーザーはWinキー送信にアクセシビリティを利用している。今回の調査ではサービス設定、Winキー処理、インストール済みAPKを変更せず、端末搭載ライブラリの静的解析と独立したネイティブ試験を行った。

端末: `Xiaomi/piano_global/piano:16/BP2A.250605.031.A3/OS3.0.304.0.WPYMIXM:user/release-keys`、SDK 36。

原因を構成する処理は次のとおり。

1. **外付けPOINTERペンのイベント生成側で方向対応フラグが欠落する。** 搭載 `libinputreader.so` の `TouchInputMapper::dispatchPointerStylus()`（仮想アドレス `0xe7598`、サイズ `0x160`）は処理済み座標を全体コピーし、X/Yを設定して `dispatchPointerSimple()` を呼ぶ。方向値を消す処理はこの関数内にはない。`dispatchPointerSimple()`（`0xe96e4`、サイズ `0x10fc`）は各 `NotifyMotionArgs` コンストラクターにflags=0を渡す。例: `0xe9e90` の `str wzr, [sp, #0x10]` がflags引数、`0xe9e9c` がコンストラクター呼び出し。一方、画面ペンの `dispatchMotion()`（`0xe677c`、サイズ `0x660`）は `0xe6b68`〜`0xe6bd4` で方向対応 `0x80` と方向付き対応 `0x100` を条件付きでORする。Android 16公式ソースにもこの差が存在する。
2. **アクセシビリティのフィルター経由でイベントが再投入される。** 実機 `dumpsys input` は `InputFilterEnabled: true`。`dumpsys accessibility` は `[KeyboardInterceptor, MotionEventInjector]`。Artemis自身の `KeyboardAccessibilityService` は `FLAG_REQUEST_FILTER_KEY_EVENTS` を使っている。Android公式の `AccessibilityInputFilter` / `InputFilter.sendInputEvent()` → `InputManagerService.InputFilterHost.sendInputEvent()` は、キーボードのみのフィルターであっても通過するMotionEventをnativeのinjectへ渡す。MotionEventInjectorの経路ではアクセシビリティ起源のpolicy flagも付く。既存の物理Deco記録のpublic flags `0x1000000` と0の混在はこの経路と一致する。ただしJavaの `getFlags()` はprivate flagsを隠すため、このpublic値だけを方向対応フラグ欠落の証拠にはしない。
3. **再投入時の座標変換が方向を実際に0で上書きする。** 搭載 `libinputflinger.so` の `InputDispatcher::transformMotionEntryForInjectionLocked()`（`0x22d7b4`）は、`0x22d930` で `MotionEvent::calculateTransformedCoords()` を呼び、戻った座標をMotionEntryへ保存する。搭載 `libinput.so` の `calculateTransformedCoordsInPlace()`（`0x163f8c`）は `0x16417c` で方向変換を呼び、`0x164184`〜`0x164188` でAXIS_ORIENTATION（8）を更新する。方向変換（ローカル関数 `0x163e80`）は `0x163e9c` で0を準備し、`0x163eac` の `tbz w2, #7` で方向対応 `0x80` が無ければその0を返す。Android公式 `Input.cpp::transformOrientation()` と一致する。

**端末の実ライブラリを直接呼ぶ再現試験**: 調査用の独立したarm64プログラムから端末の `/system/lib64/libinput.so` をdlopenし、identity変換で `calculateTransformedCoordsInPlace()` を呼んだ。実物のペン操作の代替ではなく、特定した消去関数の条件と出力を確かめる試験。

| source | native flags | 方向の変換 | 傾き量 / 筆圧 |
|---|---|---|---|
| Decoと同じ `0x6002` | `0` | `0.7 → 0` | `0.5 / 0.4`を保持 |
| `0x6002` | `0x1000000` | `0.7 → 0` | 同上 |
| `0x6002` | `0x180` | `0.7 → 0.7` | 同上 |
| `0x6002` | `0x1000180` | `0.7 → 0.7` | 同上 |
| 非ポインター `0x1000010` | `0` | `0.7 → 0.7` | 同上 |

5条件すべて期待結果に一致。これにより、方向が0になる原因コードは **POINTER経路のフラグ不足と、通常のAndroid座標変換にあるフラグ依存の0上書き** と特定できる。アクセシビリティの再投入経路は、アプリ側で非ポインターのコピーに変更しても回復しなかった理由を説明する。Linuxカーネルで取得済みの傾きX/Yの欠落ではない。Xiaomi専用の処理を原因と断定する根拠はなく、同じ問題を構成するコードがAOSPにも存在し、端末の該当バイナリでも確認された。

検証範囲: 端末バイナリの静的制御フロー、該当関数の実機での制御試験、既存の物理入力ログ、フィルターの実機設定を照合した。物理Decoの1イベントについてフィルター入口から再投入までの各関数を連続フックしたものではない。追加サービスやShizukuを導入していない。Winキーと傾きを両立させる回避策の物理確認は別途必要。

再現資料は `%TEMP%/artemis-deco-investigation/` の `native-transform-probe.cpp`、`native-transform-probe-result.txt`、`xiaomi-pointer-stylus-disassembly.txt`、`xiaomi-pointer-simple-disassembly.txt`、`xiaomi-direct-motion-disassembly.txt`、`xiaomi-transform-coords-disassembly.txt`、`xiaomi-injection-transform-disassembly.txt`、`input-deco-pinpoint-initial.txt`、`accessibility-pinpoint-initial.txt`。REAのGhidraプロバイダーはこのWindows環境でarm64 ELFを扱えないため、インストール済みAndroid NDKのLLVMで静的解析した。デバイス上の一時試験プログラムは試験後に削除した。

搭載ライブラリSHA-256:

- `libinput.so`: `9607C6258232953CF452D50FBB2A5EFB100C28B5A1BA9A2EEFD8D19EE3979577`
- `libinputreader.so`: `6A5BE45185E1D01CCA6BA8FD4496F351863C11B165E45A0A202A050BAC3BAC9A`
- `libinputflinger.so`: `9F83A011C7A930452DB67F3459BAA901A75A6C8AFA44A6AA588F10A9FE1F3219`

公式参照: [Android 16 InputDispatcher.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/inputflinger/dispatcher/InputDispatcher.cpp)、[Input.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/libs/input/Input.cpp)、[TouchInputMapper.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/inputflinger/reader/mapper/TouchInputMapper.cpp)、[AccessibilityInputFilter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/accessibility/java/com/android/server/accessibility/AccessibilityInputFilter.java)、[InputFilter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/view/InputFilter.java)、[InputManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/input/InputManagerService.java)。

### Shizukuなしの経路を実機で確認し、Artemisへ実装（2026-10-11 08:17〜）

公開API `AccessibilityServiceInfo.setMotionEventSources()` と `AccessibilityService.onMotionEvent()` で、再投入前の入力を読めることを確認した。前節の「Winキーと傾きを両立させる回避策は別途確認が必要」は、この検証で更新する。

一時的な診断サービスで、実際のDeco操作を80サンプル記録した。同じイベントで通常の `getOrientation()` は0、非ポインターとして読むコピー内の保存方向は非ゼロで変化した。例:

- 08:17:32.530: device=34 / Deco mini7W V2 Pen、normal=0、raw=3.0712838 rad、tilt=0.8389914 rad、pressure=0.044439018。
- 08:17:40.776: raw=-1.9948907 rad。
- 08:17:40.877: raw=2.279749 rad。

診断サービスは試験後にアンインストールし、9件の有効アクセシビリティサービスの設定が試験前と完全一致することを確認した。Shizukuは使用していない。

Artemisには以下を実装した。

- 外付けペンタブをデバイスのSOURCE_MOUSE + SOURCE_STYLUS（SOURCE_TOUCHSCREENを除く）で識別し、Pointer Captureを抑制する。別のマウスが先に列挙された場合と接続途中の追加にも対応する。
- 既存のKeyboardAccessibilityServiceが、Android 15以降で、ペンタブを接続した前面のストリーミング画面に限ってSOURCE_MOUSEを受け取る。切断、画面のフォーカス喪失、一時停止、デバイス取り外しで解除する。現在の対象は端末のメイン画面。
- このAPIは機器単位ではなくsource単位でイベントを消費するため、同時接続した通常のマウスもActivity/Viewの通常のUI処理へ転送する。OSへ再投入せず、アプリ内で同期してdispatchする。
- 方向は消去前の元イベントから読む。通常のAPIから既に非ゼロの方向が取れるイベントには補正を重ねない。早い経路の保存方向0は有効な方向として扱い、再投入後の保存方向0から方向を捏造しない。
- Focus Penなどの画面ペンはSOURCE_MOUSEに該当しないため元の経路を保つ。Winキー用のFLAG_REQUEST_FILTER_KEY_EVENTSと既存のキー転送を維持する。

未配布のShizuku読み取り試作（UserService、AIDL、生のevdevデコーダー）を削除した。今回のペン修正にShizuku権限、root、非公開APIは不要。プロジェクトの既存の別機能で使うShizuku依存関係は変更していない。

ビルド・検証:

- 関連する12テストすべて成功（ExternalStylusSupport 6、KeyboardAccessibilityService 3、AndroidNativePointerCaptureProvider 3）。
- 全107テスト中102成功、5失敗。失敗はRELEASE.mdに記載された既存のLayoutInflationTest、SimpleStartupTest、StartupTest、ProfilesNavigationTestの2件と一致した。
- assembleNonRoot_gameRelease成功。元のインストールとAPK署名のSHA-256が一致することを検証して上書きした。
- インストールしたAPK SHA-256: `219FCAA785BCE410407E55DCFE34DBB5C36117848676D75BD219DCD6B85EBACB`。
- バージョンは20.2.6-buffered.21のまま。GitHubリリースは依頼されていないため実施していない。

08:25のDecoだけの実機試験はユーザーが実施済み。Apolloで3,810件のpen packetを採取し、rotation=0〜359（279種類）、tilt=0〜69を確認した。アプリのログは `Accessibility pointer input: 8194`（SOURCE_MOUSE）を示し、Winキー用サービスも有効のままだった。

### ユーザー報告の90度ずれを切り分け（同日08:29〜）

カーネルのDeco入力event26とApolloペンパケットを同時に記録した。Androidのlogcat epoch/monotonicの同一ログを時刻基準とし、Android→PCの配送時間を25msとして近傍フレームを照合した。動作中の独立したログなので、各イベントを完全に一対一でトレースしたものではない。

カーネルのX/Yから `atan2(-sin(tiltX), sin(tiltY))` で方向を求め、PCの受信rotationと比較した。最終的な8,205件のPCパケットから7,530件を対応づけ、差の中央値が約0.48度、7,512件が5度以内だった。例: 08:29:41.190のkernel X=60/Y=0に対してrotation=270、08:29:48.907のX=12/Y=-60に対して期待193.5度・受信194度。ApolloのWindows実装ではrotation=270の傾きはtiltX正、tiltYほぼ0へ変換される。

したがって、現時点のカーネル→PC転送に90度の一定ずれは見られない。Kritaのブラシで見える向きとの関係は別途確認が必要。確認時の選択プリセットはc) Pencil-5 Tilted。標準プリセットは傾き方向ascensionで回転し、ブラシチップの基本角度はπ rad。Kritaの傾き方向オフセットはUIで0度だった。標準プリセットの内容と、未保存の現在のブラシ編集は区別する。

証拠: `%TEMP%/artemis-deco-investigation/early-pen-probe/physical-deco-result.txt`、`deco-accessibility-test-build.log`、`deco-accessibility-full-test.log`、`deco-accessibility-apollo-pen-packets.txt`、`deco-rotation-kernel-events.txt`、`deco-rotation-apollo-pen-packets.txt`、`compare-deco-rotation.py`、`deco-rotation-axis-comparison.json`。

公式API参照: [AccessibilityService.onMotionEvent](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onMotionEvent(android.view.MotionEvent))、[AccessibilityServiceInfo.setMotionEventSources](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#setMotionEventSources(int))。SOURCEの指定で対象の入力が他のシステム処理へ渡らなくなるため、前面のArtemis内への直接転送と、フォーカス喪失時の解除を実装した。
Krita公式のRotation説明では、Tilt Directionセンサーはブラシの縦軸をペン方向へ合わせ、自然な見え方にするにはブラシ先端角度を90度に設定すると説明されている。読んだ標準Pencil-5 Tiltedの基本角度は180度であり、ブラシ設定が見た目の90度差の候補になる。ただし、現在編集中のブラシの角度を実際に変更して再確認したものではない。途中でKritaが再起動され、Windows UIツールが新しいウィンドウを捕捉できなかったため、未保存キャンバスを閉じる操作は行っていない。

公式参照: [Krita Rotation / Tilt Directionの基準とブラシ角度](https://docs.krita.org/en/reference_manual/brushes/brush_settings/options.html)。

### Totemのマウスキーでクリックできない件（2026-10-11）

ユーザー報告は、PC側のGPTだけでなく他のPCアプリでもポインター移動はできるがクリックが効かず、Decoのペンではクリックできる状態。ペンをタブレットから10cm離しても復帰しなかった。

不調時のTotem Mouseのkernel BTN_LEFT押下・解放17組は、Apollo受信の左ボタン押下・解放17組と対応した。Windowsの読み取り専用WH_MOUSE_LL記録でも押下・解放を確認したため、Androidのゲームパッド経路でクリックが消えるという初期仮説は採用しない。クリック座標はDPI補正後のGPTウィンドウ内だった。不調時はクリック後にGetForegroundWindowがNULLとなる記録があるが、これだけでは発生原因を決められない。

ArtemisからPCへの再接続後、ユーザーがクリックの復帰とDecoのBluetooth接続継続を確認した。復帰後もアプリのログはSOURCE_MOUSEのアクセシビリティ経路（8194）を示した。Apollo記録には通常のマウス押下・解放23組、ペン入力1,974件（down/up各9、hover leave 12、rotation 0〜352、tilt 0〜62）があり、ペン使用後のTotemクリックもWindowsでGPT/Kritaのウィンドウが捕捉することを確認した。読み取り用マウスフックのCallNextHookEx戻り値は0で、採取できた復帰後のクリックには他のフックによる抑止やAlt/Ctrl/Shift/Winの押しっぱなしが見られなかった。

今回、アプリコード、APK、PCのマウス操作ソフト設定は変更していない。再接続によって解消したセッション内の入力状態の不整合が候補だが、Apollo/Windows/クライアントのどの処理が原因かは未確定。Decoの接続やアクセシビリティが有効であることだけを原因とは断定できない。恒久修正が完了したとは扱わない。

採取した読み取り用フックとログ監視は終了済み。資料は `%TEMP%/artemis-deco-investigation/` の `totem-mouse-kernel-events.txt`、`totem-apollo-mouse-buttons.txt`、`totem-windows-mouse-events.txt`、`totem-windows-mouse-events-v2.txt`、`totem-windows-mouse-events-v3.txt`、`totem-apollo-mouse-and-pen-v3.txt`、`input-totem-restored.txt`。
