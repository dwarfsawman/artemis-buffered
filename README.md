# artemis-buffered

## What's this

40 msの遅延と引き換えに、数十ms程度のジッターがある環境で音声が途切れないようにしたフォークです。

ネットカフェなどの共有Wi-Fi（目安: ping 50 ms前後）で、AndroidからWindows PCへ接続した際の音声途切れを抑えることを目的とした[Artemis Android v20.2.6](https://github.com/ClassicOldSong/moonlight-android/tree/v20.2.6)の実験的フォークです。

このフォークは、Windows RDPのようなリモートデスクトップ音声がクライアント側でネットワーク揺らぎを吸収する設計思想を参考に、ユーザーの依頼を受けてOpenAI Codexがコード調査・実装・テストを行いました。[RDP Audio Output Virtual Channel仕様](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-rdpea/)の互換実装や複製ではなく、ジッターバッファと小幅な時間伸縮という一般的な考え方から着想したものです。

## このフォークの音声変更

- Android 8.1以降では、AAudioデータコールバックとlock-free SPSCリングバッファを使用
- 初回は40 ms蓄積してから再生を開始
- 到着ジッターに応じて目標バッファを20～80 msで適応制御
- WSOLA型の相関選択付きoverlap-addにより、必要なときだけ0.97～1.03倍で時間伸縮
- アンダーラン後は停止・flush・40 msの再蓄積を行わず、不足分を無音にして再生を継続
- Android 8.0以前、AAudioを利用できない端末、Audio FX使用時は従来のAudioTrackへフォールバック
- Opus Deep PLCは含めず、元のPLC動作を維持

120秒、5 ms音声パケット、片道15～35 ms（RTT 30～70 ms）、1%に6 msのスケジューラ揺らぎを加えた決定論的試験では、固定40 ms版と適応版の音声枯渇はいずれも0で、平均リング量は41.23 msから30.76 msへ減少しました。これは実機Wi-Fi試験ではなくアプリ側シミュレーションであり、端末固有のAAudioバースト、ミキサー、CPU負荷、聴感品質は別途確認が必要です。

### English summary

This experimental fork targets shared Wi-Fi environments such as internet cafés at around 50 ms ping. At the user's request, OpenAI Codex implemented an adaptive 20–80 ms AAudio/SPSC jitter buffer with correlation-selected WSOLA-style 0.97–1.03x time scaling. It starts at 40 ms and never pauses to refill after an underrun. Deep PLC is intentionally not included.

## Upstream project

### Artemis Android

Previously named Moonlight Noir

An open source client for [Apollo](https://github.com/ClassicOldSong/Apollo)/[Sunshine](https://github.com/LizardByte/Sunshine).

Artemis Android will allow you to stream your collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Artemis is currently the best fork of Moonlight with loads of optimizations for office usage.

A more seamless experience with virtual display will be Artemis paired with [Apollo](https://github.com/ClassicOldSong/Apollo).

## Upstream features

If you switch back to the main stream version, you'll be missing the following awesome features which are very unlikely to be added there:

1. Custom virtual buttons with import and export support.
2. [Custom resolutions](https://github.com/moonlight-stream/moonlight-android/pull/1349).
3. Custom bitrates.
4. [Multiple mouse mode switching](https://github.com/moonlight-stream/moonlight-android/pull/1304) (normal mouse, [multi-touch](https://github.com/moonlight-stream/moonlight-android/pull/1364), touchpad, disabled, local cursor mode).
5. Optimized virtual gamepad skins and free joystick.
6. External monitor mode.
7. Joycon D-pad support.
8. Simplified performance information display.
9. [Game back menu](https://github.com/moonlight-stream/moonlight-android/pull/1171).
10. Custom shortcut commands.
11. Easy soft keyboard switching.
12. Portrait mode.
13. Display on top mode, useful for foldable phones.
14. [Virtual touchpad space and sensitivity adjustment](https://github.com/moonlight-stream/moonlight-android/issues/1348#issuecomment-2236344729) for playing right-click view games, such as Warcraft.
15. Force use device's own vibration motor (in case your gamepad's vibration is not effective).
16. Gamepad debugging page to view gamepad vibration and gyroscope information, as well as Android kernel version information.
17. Trackpad tap/scrolling support
18. Natural track pad mode with touch screen
19. Non-QWERTY keyboard layout support
20. Quick Meta key with physical BACK button
21. Frame rate lock fix for some devices
22. Video scale mode: Fit/Fill/Stretch
23. View pan/zoom support
24. Rotate screen in-game
25. Add option to quit app directly
26. Samsung DeX scrolling support
27. Proper click/scroll/right-click for trackpad on generic Android tablet when using local cursor
28. Virtual Display integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
29. Server Command integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
30. Clipboard sync (requires Apollo)

## Upstream disclaimer

This is the `go away` version of Moonlight Android.

I got kicked from Moonlight and Sunshine's Discord server literally for helping people out.

This is what I got for finding a bug, opened an issue, getting no response, troubleshoot myself, fixed the issue myself, shared it by PR to the main repo hoping my efforts can help someone else during the maintainance gap.

Yes, I'm going away. Fixes and improvements on this fork are not necessarily be merged to the main repo either. I have also started [a fork of Sunshine called Apollo](https://github.com/ClassicOldSong/Apollo) and will add useful features that will never get merged by the main repo shortly. [Apollo](https://github.com/ClassicOldSong/Apollo) and [Moonlight Noir](https://github.com/ClassicOldSong/moonlight-android) will no longer be compatible with OG Sunshine and OG Moonlight eventually, but they'll work even better with much more carefully designed features.

The main repo had stayed silent for 5 months, with nobody actually responding to issues, and people are getting totally no help besides the limited FAQ in their Discord server. I tried to answer issues and questions, solve problems within my ablilty but I got kicked out just for helping others.

**PRs for feature improvements are welcomed here unlike the main repo, your ideas are more likely to be appreciated and your efforts are actually being respected. We welcome people who can and willing to share their efforts, helping yourselves and other people in need.**

**Update**: They have contacted me and apologized for this incident, but the fact it **happened** still motivated me to start my own fork.

## Downloads
* [artemis-buffered releases](https://github.com/dwarfsawman/artemis-buffered/releases)
* [Upstream Artemis releases](https://github.com/ClassicOldSong/moonlight-android/releases)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
