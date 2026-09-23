## 変更点

- Windows クライアントのリモートストリーミング中、ホスト側で文字入力をしようとするとクライアント側の日本語 IME 未確定文字欄が画面左上付近に出現する問題を修正。
  - ストリーミングウィンドウ生成直後、およびウィンドウフォーカス獲得時（`SDL_WINDOWEVENT_FOCUS_GAINED`）、ウィンドウ復帰・再表示時（`SDL_WINDOWEVENT_RESTORED`, `SDL_WINDOWEVENT_SHOWN`）において、Win32 API の `ImmAssociateContext(hwnd, nullptr)` を明示的に適用。
  - SDL2 内部のフォーカス管理・状態フラグ（`SDL_StopTextInput()` の no-op 化）およびマルチウィンドウ環境における IME 再活性化を OS レベルで遮断し、クライアント側ローカル IME のポップアップを完全に抑止。
  - 生のキーボードイベント（`WM_KEYDOWN` / `WM_KEYUP` / 半角全角キー等）の伝送には影響せず、ホスト側での日本語入力・変換は通常通り動作。
- バージョンを `20.2.6-buffered.20`（versionCode: `5820`、Windows Desktop: `20.2.6.20`）に更新。

## 検証

- Windows 実機（Yoga）での診断ログ採取により、ストリーミング中にストリーミングウィンドウの IME コンテキスト（HIMC）が保持されたままキー入力が発生していた再発メカニズムを特定・確認。
- Windows x64: Visual Studio 2022／MSVC／Qt 6.8.3 でローカルビルド成功。portable ZIP 生成、バージョン `20.2.6.20` を確認。
- Android: `assembleNonRoot_gameRelease` 成功。`testNonRoot_gameReleaseUnitTest` は102件中97件成功（失敗5件は既知の Robolectric テスト）。
- Android APK の署名検証通過（`apksigner verify`）。

## 成果物

- Android: `artemis-buffered-20.2.6-buffered.20-arm64-v8a.apk` — Application ID `io.github.artemisbuffered`、versionCode `5820`、versionName `20.2.6-buffered.20`、SHA-256 `33d8792db2546cf0ea3813903e4580f7b383655260b990a3a3c8a0ef8ee8be14`
- Windows x64: `artemis-buffered-windows-x64-portable-v20.2.6-buffered.20.zip` — Version `20.2.6.20`、SHA-256 `ba343f619d937941ea7df09968a0cae5ccad0b22da494009daa02c51d9fe12ea`
