# Samsung DeX 全画面起動の調査・検証記録 (DEX_FULLSCREEN_INVESTIGATION.md)

本ドキュメントは、Galaxy S26（Android 16 / One UI 8.5）上の Samsung DeX 環境において、アプリ起動時に自動で全画面表示（タイトルバーなし・タスクバーなしの完全全画面 1920x1080）にならない課題に関する調査結果、試したアプローチ、判明したOS/SystemUIの内部仕様、および今後の対応案をまとめたものです。

---

## 1. 課題の概要

- **現状の挙動**:
  - 非 DeX 画面（スマホ本体画面）でアプリアイコンをクリックした場合は正常に全画面で起動する。
  - DeX 画面（外部ディスプレイ: Panasonic-TV, 1920x1080）でアプリアイコンをクリックした際、完全全画面にならず、**上部にタイトルバー（キャプションバー）、下部に DeX タスクバー（高さ 56px）が表示された「最大化ウィンドウ（Bounds: [0, 0][1920, 1024]）」** で起動してしまう。
- **手動全画面化の挙動**:
  - タイトルバー右上の全画面化アイコン（`toggle_immersive_window`）を手動タップ（または ADB からタップ）すると、**タイトルバーもタスクバーも完全に消え、1920x1080 の完全全画面** に遷移する（実機スクリーンショットおよび dumpsys にて確認済み）。

---

## 2. 試したアプローチと解析結果

Galaxy S26 実機から `services.jar` および `SystemUI.apk` を pull し、dexdump による逆アセンブル解析を実施して OS および SystemUI の内部挙動を調査しました。

### ① AOSP Android 15+ 標準の `Activity.requestFullscreenMode(1, null)`
- **結果**: OS（`ActivityTaskManagerService`）により明示的に拒否される。
- **詳細**:
  `services_classes.dex` の `ActivityClientController.requestFullscreenMode` を逆アセンブル解析したところ、外部ディスプレイ DeX 環境（`isDeskRootTask() && !isDefaultDisplayDesktop()`）の場合、OS 側で以下のブロック処理が実装されていました。
  ```smali
  invoke-virtual {v0}, Lcom/android/server/wm/Task;.isDeskRootTask:()Z
  ...
  invoke-virtual {v0}, Lcom/android/server/wm/WindowContainer;.isDefaultDisplayDesktop:()Z
  if-nez v0, ...
  const-string/jumbo v7, "requestFullscreenMode: Not allow change to fullscreen windowing on desktop windowing. request target="
  ```
  このため、アプリから標準 AOSP の全画面化要求（`windowingMode = 1`）を行っても、DeX の Desktop Windowing では無条件で弾かれます。

---

### ② Samsung 独自の非公開フラグ `samsungFlags`（Hidden API）
- **結果**: タイトルバー（キャプションバー）の非表示フラグ適用には成功。
- **詳細**:
  One UI の `TaskFragment.prepareSurfaces` を解析した結果、`WindowManager.LayoutParams.samsungFlags` の `0x01000000`（`SAMSUNG_FLAG_HIDE_CAPTION`）がタイトルバーの表示/非表示を司っていることを特定。
  ```smali
  iget v3, v3, Landroid/view/WindowManager$LayoutParams;.samsungFlags:I
  const/high16 v4, #int 16777216 // 0x01000000 (SAMSUNG_FLAG_HIDE_CAPTION)
  and-int/2addr v3, v4
  if-eqz v3, ...
  iput-boolean v3, v0, Lcom/android/server/wm/Task;.mIsCaptionHiddenRequested:Z
  ```
  Android 14+ の Hidden API 制限を回避するため `org.lsposed.hiddenapibypass:hiddenapibypass:4.3` を導入し、`ArtemisApplication.onCreate()` で `HiddenApiBypass.addHiddenApiExemptions("L")` を実行後、`DesktopFullscreen.java` からリフレクションで `samsungFlags |= 0x01000000` を設定。
  実機 dumpsys で `mIsCaptionHiddenRequested=true` および Window の `sfl=1000000` が反映されることを確認。
- **課題**:
  これによってタイトルバー領域は消せるものの、Task の Bounds は依然として `[0, 0][1920, 1024]`（タスクバーを除外した最大化領域）のままであり、タスクバー部分（下部 56px）まで拡張された 1920x1080 には自動拡大しませんでした。

---

### ③ マニフェストによる DeX メタデータ
- **結果**: 最大化ウィンドウ（1920x1024）止まり。
- **詳細**:
  `AndroidManifest.xml` に以下のメタデータを設定：
  ```xml
  <meta-data android:name="com.samsung.android.dex.launchwidth" android:value="0" />
  <meta-data android:name="com.samsung.android.dex.launchheight" android:value="0" />
  <meta-data android:name="WindowManagerPreference:FreeformWindowSize" android:value="maximize" />
  ```
  `services_classes.dex` の `DexController` を解析したところ、これらは「DeX 上での初期ウィンドウサイズを最大化（＝1920x1024）する」ためのものであり、DeX 自体のタスクバーを覆う「フルイマーシブ全画面」へ引き上げるものではありませんでした。

---

### ④ DeX タイトルバーの「全画面ボタン」の内部実装解析
- **結果**: DeX における全画面化の真のメカニズムを特定。
- **詳細**:
  実機から pull した `SystemUI.apk`（WMShell）の `classes4.dex` を逆アセンブル解析。
  タイトルバー右上の全画面化ボタンは `id/toggle_immersive_window`（`0x7e0a0e64`）であり、クリック時に以下のフローが実行されていました：

  1. `DesktopModeTouchEventListener.onClick` が `0x7e0a0e64` を検知
  2. `DesktopModeWindowDecorViewModel$DefaultWindowDecorationActions.onImmersiveOrRestore(RunningTaskInfo)` を呼び出し
  3. `DesktopImmersiveController.moveTaskToImmersive(RunningTaskInfo)` を実行：
     ```java
     WindowContainerTransaction wct = new WindowContainerTransaction();
     wct.setBounds(taskInfo.token, new Rect()); // 空Rect (0, 0, 0, 0) を設定
     if (CaptionGlobalState.FORCE_HIDE_TASKBAR_ENABLED) {
         wct.setForceImmersiveChange(taskInfo.token); // タスクバー強制非表示
     }
     transitions.startTransition(6, wct, ...);
     ```
  4. これにより、Task は `mode=freeform` のまま維持されつつ、**「Full Immersive State（タスクバー非表示＋空Bounds付与）」** という SystemUI / WMShell 特有のトランジションによって 1920x1080 の完全全画面へ移行していることが判明しました。

---

## 3. 残された課題と今後の解決アプローチ

一般アプリ権限（非システム権限）から DeX の「Full Immersive State」をトリガーするか、あるいはタスクバー領域まで描画を拡張するための有力なアプローチは以下の通りです：

### アプローチ A: `Activity.setImmersive(true)` の検証
- `services_classes.dex` の解析において、`ActivityClientController.setImmersive(IBinder, boolean)` が存在：
  ```smali
  f83794: iput-boolean v7, v6, Lcom/android/server/wm/ActivityRecord;.immersive:Z
  ```
  この処理によって `ActivityRecord.immersive = true` となり、`TaskInfo.requestFullscreenMode` が立ちます。
  `SystemUI.apk` 側の `DesktopTaskChangeListener` にて `RunningTaskInfo.requestFullscreenMode` を監視する分岐（`3535d2`）が存在するため、アプリ起動直後に `activity.setImmersive(true)` を呼ぶことで、WMShell 側が自動的に `moveTaskToImmersive` を発行する可能性がある。

### アプローチ B: ウィンドウフラグ `FLAG_LAYOUT_NO_LIMITS` によるタスクバー領域へのオーバースキャン
- `WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS`（`0x00000200`）を Window に付与：
  ```java
  activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
  ```
  タスクバーのインセット制約を無視してウィンドウサーフェスを 1920x1080 全体に広げられるか検証する。

### アプローチ C: `samsungFlags` または `semAddExtensionFlags` の別ビット調査
- `0x01000000`（`SAMSUNG_FLAG_HIDE_CAPTION`）以外に、DeX のタスクバー非表示やフルイマーシブを直接要求するフラグビットが存在するか、`framework.jar` / `services.jar` の定義値を調査する。

---

## 4. デバッグ環境・検証情報

- **検証実機**: Samsung Galaxy S26 (SM-S942Q), Android 16 / One UI 8.5
- **外部ディスプレイ**: Panasonic-TV (Display ID: 6 / SurfaceFlinger ID: `4626509527032991253`, 1920x1080)
- **ワイヤレスデバッグ**: `adb connect 192.168.1.102:<port>`（ポートは端末側の設定から確認可能）
- **DeX スクリーンショット取得コマンド**:
  ```powershell
  adb shell screencap -d 4626509527032991253 -p /sdcard/dex.png
  adb pull /sdcard/dex.png .
  ```
