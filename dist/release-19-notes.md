## 変更点

- Windows x64 Desktop Client を追加。Artemis Qt（wjbeckett/artemis）を `desktop/` に取り込み、既存 Android クライアントは `app/` に維持。
- Desktop 音声設定に Low latency／40／60／80／120 ms の固定 PCM バッファを追加。Buffered モードは既存の libsoundio/WASAPI リングを使い、初回だけ設定量を蓄積して再生。underrun 時は不足 frame を無音で補い、再蓄積待ちは行わない。適応バッファ、WSOLA、再生速度補正は含まない。
- Desktop の SDL video 初期化直後にローカル text input を停止し、Windows クライアント側の IME 未確定文字表示を抑制。
- Windows x64 portable ZIP の GitHub Actions ビルドを追加。Android Release 公開後に同じ Release へ ZIP を添付する構成。

## 検証

- Windows x64: Visual Studio 2022／MSVC／Qt 6.8.3 でビルド成功。portable ZIP の整合性検査、アプリ起動、表示バージョン `20.2.6.19` を確認。
- 音声: 実際の SoundIoAudioRenderer を使うローカル WASAPI プローブで、Low latency の約15 ms リングと 40 ms 設定の 1,920 frame 蓄積後の再生開始を確認。後続の PCM 不足では無音補完して継続し、再 priming は発生しなかった。この試験は模擬 PCM 入力であり、ネットワーク接続中の音声試験ではない。
- QML 構文検査と GitHub Actions 定義の `actionlint` は通過。
- Android: `assembleNonRoot_gameRelease` 成功。`testNonRoot_gameReleaseUnitTest` は102件中97件成功、失敗5件は前版と同じ既知の Robolectric テスト（`LayoutInflationTest`、`SimpleStartupTest`、`StartupTest`、`ProfilesNavigationTest` の2件）。
- Android APK の署名証明書は前版 `buffered.18` と一致。`apksigner verify` 通過。

## 未確認・既知の問題

- Apollo／Sunshine への実ストリーミング接続、接続中の Low latency 音声、Windows クライアント側の日本語 IME 表示とホストへのキー送信は未確認。
- Android `main` が参照する Moonlight common C のコミットは、設定済みの公開 remote から取得できない。Android ビルドはこの PC の既存ローカル checkout のオブジェクトを利用した。Windows workflow は Desktop 用 submodule のみを取得する。

## 成果物

- Android: `artemis-buffered-20.2.6-buffered.19-arm64-v8a.apk` — Application ID `io.github.artemisbuffered`、versionCode `5819`、versionName `20.2.6-buffered.19`、SHA-256 `829c525be363988b35c16950f8379fdd8d4bb3eb0e9eae8ecec1be49d63c5580`
- Windows x64: `artemis-buffered-windows-x64-portable-v20.2.6-buffered.19.zip` — SHA-256 `609ff3d22e9625d636f27d1e4270e34e3b911a161a003764ffef05da1a776fdc`
