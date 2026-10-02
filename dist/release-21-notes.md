## 変更点

- 旧「適応20～80 ms＋WSOLA型処理」を「適応音声バッファ（クロスフェード補正）」へ置き換えました。既存の設定位置と保存キーを維持しているため、以前のON/OFFを引き継ぎます。
- punktfunkの共有音声ポリシーから、通常PCMの無加工再生、過剰蓄積の低頻度クロスフェード削除、条件付き再蓄積をAndroidのAAudio経路へ移植しました。パケットごとの時間伸縮や0.97～1.03倍の速度補正は廃止しました。
- 適応目標は40 msから開始し、音声不足や不足寸前の状態に応じて最大90 msまで増やします。安定後は基本25 msへ段階的に戻し、縮小後に不足すれば以前の目標へ戻します。端末のコールバック出力量が大きい場合は、出力量＋1パケットを下限にします。
- 平均蓄積量の過剰が2秒続く場合に、1パケットを短いクロスフェードで削除します。急激な大量蓄積には別途上限処理を行います。クロスフェードの重みは全チャンネルで共通です。
- 一時的な不足は不足分を無音にし、持続した不足や蓄積不足にはAAudioを止めずに再蓄積します。適応モードの端末出力バッファは3 burstから開始し、XRun発生時にburst単位で増やします。
- 適応モードOFF時の固定40～120 msと、AAudioを利用できない場合のAudioTrackへのフォールバックを維持しました。診断ログにクロスフェード削除・再蓄積・端末バッファ増加の回数を追加しました。
- 移植元はpunktfunk commit `d016f73683b6b96ca64da99679ca5f4005846726`。MITの許諾文と帰属表記をソースおよびAPK内に同梱しました。

## 制限事項

- Galaxy等の実機での聴感確認は未実施です。再蓄積中は無音になるため、すべてのネットワーク条件で音切れをなくすものではありません。
- punktfunkの映像時刻を使った厳密なA/V同期と、新しいパケット未到着時PLCは含めていません。ホスト側の変更は不要です。
- Windowsクライアントの音声処理には今回の変更を適用していません。

## 検証

- アプリで使用するCの音声ポリシーを直接コンパイルした試験18件が通過しました。通常PCMの保持、短い到着バースト、過剰蓄積、目標の増減、再蓄積、異なる出力量・サンプルレート、リングの折り返し、複数チャンネルを確認しました。
- ±200/500 ppmのクロックずれを各5分間シミュレーションしました。正のずれでは不足0回、負のずれでは不足1～2回・再蓄積1～2回でした。実機の収録結果ではありません。
- `testNonRoot_gameReleaseUnitTest`: 95件中90件成功。失敗はRELEASE.mdで許容されている既知のRobolectricテスト5件のみでした。既存トグルの保存値と日本語の新設定名の確認は通過しました。
  - `LayoutInflationTest.allLayoutsInflateSuccessfully`
  - `ProfilesNavigationTest.clickingProfileButton_launchesProfilesActivityFromAppView`
  - `ProfilesNavigationTest.clickingProfileButton_launchesProfilesActivity`
  - `SimpleStartupTest.testApplicationOnCreate`
  - `StartupTest.testApplicationStartup`
- `assembleNonRoot_gameRelease`成功。APK内の新しいnativeポリシーとMITライセンス同梱を確認しました。
- `apksigner verify`成功（v1/v2）。既存buffered.18 APKと同じ署名証明書を確認しました。

## APK

- File: `artemis-buffered-20.2.6-buffered.21-arm64-v8a.apk`
- ABI: `arm64-v8a`
- Application ID: `io.github.artemisbuffered`
- Version: `20.2.6-buffered.21` / versionCode `5821`
- APK source commit: [`5770c526f3c99ca663d3edd04e03608363f3701f`](https://github.com/dwarfsawman/artemis-buffered/commit/5770c526f3c99ca663d3edd04e03608363f3701f)（リリースタグにはドキュメントのみの追記を含みます）
- Size: `8,596,869 bytes`
- SHA-256: `50d3bdb8e46fea658782f3d549edc8177aa9c720fd4219749b8d777bdfd63e8e`
- Signing certificate SHA-256: `d445070935ceda58ff2e0ecfa7f10b191f2b3a892378f78f86c98bb6cba5666e`
