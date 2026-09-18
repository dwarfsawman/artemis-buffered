## 変更点

- **DeX 完全全画面化（Shizuku 連携による自動トグル）**:
  - One UI 8 / 8.5 DeX 環境では標準 AOSP の全画面要求（`requestFullscreenMode` / `windowingMode=1`）が OS の Desktop Windowing レイヤーでブロックされる制約を解決。
  - Shizuku 経由の特権権限を活用し、アプリ起動時に対象ディスプレイ（DeX 外部ディスプレイ）のキャプションバー装飾（`ReusableWindowDecorViewHost`）の座標領域を動的に取得して全画面トグルボタン（`toggle_immersive_window`）を自動タップする `ShizukuDesktopImmersive` を実装。
  - 画面サイズ事前判定（Full Immersive 到達チェック）およびデバウンス（2.5秒）を設け、既に全画面状態の場合の誤トグルや多重タップを防止。
  - ユーザー設定の「DeXで全画面起動」（`checkbox_launch_fullscreen`）トグルと完全連動（OFF の場合は自動タップを行わない）。
- **不要・無効コードのクリーンアップ**:
  - 全画面トグルができなかった時代の試行錯誤コード（`LaunchTrampoline` 内の無効な `KEY_WINDOWING_MODE_*` Hidden API キー注入、`DesktopFullscreen` 内の拒否されていた `requestFullscreenMode` リフレクション呼び出し、および不要となった `HiddenApiBypass` 依存関係）を完全に削除・整理。
  - `LaunchTrampoline` を後方互換性を保つシンプルな中継 Activity に簡素化。

## 検証

- リリースAPKのビルド成功 (`testNonRoot_gameReleaseUnitTest`, `assembleNonRoot_gameRelease`)
- 単体テスト102件中97件成功。失敗5件は buffered.15/16/17 と同様の既知の Robolectric テスト。今回関連するテスト（`LaunchTrampolineTest`, `SettingsPresetControllerTest`）はすべて成功。
- APK署名検証成功（`apksigner verify`）
- Galaxy S26（実機 Android 16 / One UI 8.5、SM-S942Q）上の DeX 環境（2560x1440）にて、タイトルバー・DeXタスクバー双方が消失した完全全画面表示への自動遷移を確認済み。

## APK

- ABI: arm64-v8a
- Application ID: io.github.artemisbuffered
- Version: 20.2.6-buffered.18 (5818)
- SHA-256: ced3aaf7053b048f393246091a23a3e4057436548413cc06eb7a4978af330897
