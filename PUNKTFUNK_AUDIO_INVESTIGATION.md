**punktfunk の音声バッファは、この Android フォークへ部分移植できる。推奨は、パケットごとの連続伸縮を置き換え、通常の音声を加工せずに再生する適応バッファへ移行すること。**

2026-10-02〜03 調査。対象は Artemis `2b5f7f296aacb59679942a84babd3c06e41fdcb5` と、10 月 2 日に Git から取得した punktfunk `d016f73683b6b96ca64da99679ca5f4005846726`（workspace version 0.42.0）。Android クライアントと共有音声コードを比較した。この資料の「現行」は調査時点の旧方式を指す。後続のbuffered.21では、通常PCMの無加工再生・低頻度のクロスフェード削除・条件付き再蓄積・XRun対処をCへ移植した。厳密なA/V同期と新しい未到着時PLCは含めていない。初期目標は40 ms、基本目標は25 ms、適応目標上限は90 msとした。

「洗練されている」という印象には、実装上の根拠がある。punktfunk は到着ジッター、長期的なクロックずれ、端末の出力バッファ不足、映像との同期、パケット欠損を別々の条件で扱う。現行フォークは、バッファ目標との差を毎パケットの伸縮率に変換するため、ネットワークが多少揺れるだけでも音声を繰り返し加工し得る。ただし punktfunk も短い区間の削除・複製や欠損補完を行うので、すべての条件で無加工・無音切れになるわけではない。

**現行処理に音色を変える要因を確認した。**

実際の再生経路は、Opus decode → `ArtemisAaudioRendererWriteDecoded()` → `writeFrames()` → `stretchFrames()` → SPSC PCM リング → AAudio callback。AAudio 有効時には PCM が native 内を通り、arm64 + hardware AES では受信スレッドから直接デコードする。Java の `WsolaTimeStretcher` と `AdaptiveAudioBufferController` は参照モデルであり、実機の主処理は [調査時のaaudio_renderer.c](https://github.com/dwarfsawman/artemis-buffered/blob/2b5f7f296aacb59679942a84babd3c06e41fdcb5/app/src/main/jni/moonlight-core/aaudio_renderer.c) と [callbacks.c](app/src/main/jni/moonlight-core/callbacks.c) にある。

`updateAdaptiveController()`（aaudio_renderer.c:1022）は、到着間隔のずれを 1/16 EWMA で平均し、目標を 20〜80 ms にする。リング量と目標との差が 4 ms を超えると、補正は即座に約 1% から始まり、最大 3% になる。枯渇を検出すると目標を 80 ms に引き上げるが、実際の蓄積を戻す手段は遅い再生である。3% の補正でも 40 ms の蓄積を増やすにはおおむね 1 秒以上かかり、その間も音声を加工する。

`stretchFrames()`（同:976）は、入力パケットだけで伸縮を完結する。48 kHz・5 ms なら入力は 240 サンプルフレーム。0.97 倍で 247、1.03 倍で 233 にするため、7 フレームを繰り返すか削除し、約 1 ms の overlap-add でつなぐ。`findBestSplice()`（同:832）が検索するのは継ぎ目の**場所**であり、繰り返し・削除する長さは既に固定されている。前後のパケットを使う履歴や、音の周期に合わせて変位を選ぶ処理は、この経路にはない。

このため、相関の検索があっても周期音の位相を合わせられるとは限らない。例えば 3 kHz は 48 kHz の 16 フレームで一周する。7 フレームの変位は 157.5°の位相差を作り、混合区間で音が打ち消し合う。補正中の 5 ms パケットなら、これを毎秒 200 回繰り返すことになる。「WSOLA型」という説明だけでは音質の保証にならない。

[調査スクリプト](research/audio-buffer-probe.py) で既存の Java 参照実装そのものをコンパイルし、連続した 3 kHz の単音を 5 ms 単位で処理した。これは native の同じ伸縮方式を調べる試験であり、Galaxy の AAudio 出力を収録した試験ではない。ARM NEON の混合計算は実行していない。

| 処理 | 1 パケットの出力フレーム数 | 最も強い周波数成分 | その成分以外のパワー | 最小 1 ms RMS の原音比 |
| --- | ---: | ---: | ---: | ---: |
| 無加工・1.00 倍 | 240 | 3000.00 Hz | 約 0% | 0.00 dB |
| 現行参照実装・0.97 倍 | 247 | 2914.98 Hz | 43.33% | −4.47 dB |
| 現行参照実装・1.03 倍 | 233 | 3090.13 Hz | 43.06% | −4.50 dB |

0.97 倍では 3109.31 Hz にも全パワーの約 33.75%、1.03 倍では 2884.12 Hz に約 33.92% が現れた。単音が複数の強い成分に分かれる結果で、音色の変化は小さくない。この割合は THD や一般の音声品質スコアではなく、**この合成音・一定補正率における一周期 DFT の結果**である。入力 400 パケットの出力が同一周期で繰り返されることを確認してから DFT を計算しているので、FFT 窓の漏れをこの数値に含めていない。実際の会話・音楽で同じ割合になるとは主張しない。

現在の [調査時のWsolaTimeStretcherTest.java](https://github.com/dwarfsawman/artemis-buffered/blob/2b5f7f296aacb59679942a84babd3c06e41fdcb5/app/src/test/java/com/limelight/binding/audio/WsolaTimeStretcherTest.java) は長さ、チャンネル整列、440 Hz のサンプル間の急な段差を確認する。[調査時のAudioBufferNetworkSimulationTest.java](https://github.com/dwarfsawman/artemis-buffered/blob/2b5f7f296aacb59679942a84babd3c06e41fdcb5/app/src/test/java/com/limelight/binding/audio/AudioBufferNetworkSimulationTest.java) は PCM を生成せず、`queueMs += PACKET_MS / rate` で理想化した蓄積量を試験する。これらが通っていても、上記の音色変化を検出することはできない。

この再現結果は、報告された聴感の悪さを説明できる要因である。実機の症状のすべてを伸縮だけに帰属させるには、固定バッファとの比較と XRun 計測が必要になる。

**punktfunk は補正を低頻度にし、バッファ不足の種類に応じて対処している。**

主要なコードは、共有の [jitter.rs](https://git.unom.io/unom/punktfunk/src/commit/d016f73683b6b96ca64da99679ca5f4005846726/crates/punktfunk-core/src/audio/jitter.rs)、Android の [audio.rs](https://git.unom.io/unom/punktfunk/src/commit/d016f73683b6b96ca64da99679ca5f4005846726/clients/android/native/src/audio.rs)、同期の [sync.rs](https://git.unom.io/unom/punktfunk/src/commit/d016f73683b6b96ca64da99679ca5f4005846726/crates/punktfunk-core/src/audio/sync.rs)、欠損補完の [recovery.rs](https://git.unom.io/unom/punktfunk/src/commit/d016f73683b6b96ca64da99679ca5f4005846726/crates/punktfunk-core/src/audio/recovery.rs)。以下はこの commit の実装値である。

| 項目 | 現行 Artemis の適応モード | punktfunk Android |
| --- | --- | --- |
| 基本目標 | 初期 40 ms、20〜80 ms | 基本 25 ms、適応目標は最大 90 ms |
| 通常の再生 | 誤差 4 ms 超でパケットごとに 1〜3% 伸縮 | PCM をそのまま再生 |
| バッファ量の判断 | パケット到着時のリング量 | callback で、約 1 秒の時定数の平均量を観測 |
| 過剰蓄積の補正 | 毎パケットを短縮 | 平均が目標 +20 ms を超えた状態が 2 秒続くと、1 音声パケットを約 2 ms のクロスフェード付きで削除 |
| 蓄積を増やす補正 | 毎パケットを伸長 | A/V 同期が深いリングを要求し、平均不足が 2 秒続いた場合に 1 音声パケットをクロスフェード付きで複製 |
| 目標の増加 | 枯渇で直ちに 80 ms | 5 秒の窓内に 3 回の枯渇で +10 ms。再生後の余裕が 1 パケット未満の near-miss でも増やす |
| 目標の減少 | 原則 1 ms/秒 | 問題なく 30 秒経過すると −10 ms。同期が浅いリングを要求中は条件付きで 5 秒 |
| 減少後の失敗 | 5 秒の保護 | 5 秒の試行中に枯渇・near-miss があれば前の目標へ戻す。同期による再試行を 60 秒から最大 480 秒まで抑制 |
| 深い到着バースト | 定常上限を超えた新しい PCM を捨てる | 一時的なバーストは平均で判断し保持。通常の headroom は目標 +40 ms、瞬時の hard cap は 240 ms |
| 長時間の不足 | 不足分を無音にし、再蓄積はしない | 持続した不足や、平均量が適応目標に追い付いていない状態での不足では再蓄積する |
| AAudio 出力バッファ | 初期 2 burst。XRun を記録 | 初期 3 burst。128 callback ごとに確認し、新たな XRun で 1 burst 増やす |

表の「フレーム削除・複製」は、Opus の通常の 5 ms 音声パケット一個を意味する。PCM のサンプルフレーム一個ではない。削除・複製にも聴こえる変化は起こり得るが、毎パケットの加工を長期的な偏りへの補正へ限定できる点が、今回の用途では有望である。A/V 同期が無効のとき、punktfunk のポリシーは自発的な複製をしない。

25/90/240 ms は、基本目標・適応目標上限・緊急時の蓄積上限という別の数値である。240 ms を通常の待ち時間にしているわけではない。また、再生開始の最低量は `max(適応目標, callback が要求する量 + 1 音声パケット)` であり、大きな出力量を要求する HDMI 等で開始直後から不足するのを防ぐ。

再蓄積には条件がある。通常は不足が合計 60 ms 続き、かつ少なくとも 2 callback になったときに再蓄積へ移る。平均量が適応目標より 10 ms を超えて不足している状態（`hollow`）で枯渇したときは、早く再蓄積へ移る。したがって「一度でも空になったら 25〜90 ms 待つ」という設計ではない。

端末側の XRun とアプリのリング不足は別問題である。ネットワーク用リングを深くしても、callback が端末の deadline に間に合わなければ音は切れる。punktfunk の XRun に応じた出力バッファ拡大は、[Android 公式のバッファ調整例](https://developer.android.com/ndk/guides/audio/aaudio/aaudio#optimizing_buffer_size)にも対応する対策で、現行コードへ独立して取り込める。

punktfunk にはリングが減っているのにパケットが来ない場合の `DroughtConceal` もある。通常の Opus では、2 パケット分の期間が過ぎ、リングが約 10 ms 以下の場合に、decoder の PLC から一パケットずつ補完する。Android の既定では連続補完を最大 120 ms に制限し、後で判明した欠損と二重に補完しないよう数を管理する。これは Deep PLC を必須にする仕組みではない。

現行 Moonlight 側にも、RTP/FEC で回復できなかったパケットに空のエントリを作り、`opus_multistream_decode(NULL, ...)` に渡す PLC がある（[RtpAudioQueue.c](app/src/main/jni/moonlight-core/moonlight-common-c/src/RtpAudioQueue.c):651、[AudioStream.c](app/src/main/jni/moonlight-core/moonlight-common-c/src/AudioStream.c):161）。punktfunk の seq 欠損処理を追加で呼ぶと二重補完になるため、既存の FEC/PLC 経路と統合する必要がある。

**取り込む範囲と実装案。**

最初の移植対象は `JitterPolicy` のバッファ判断、低頻度のクロスフェード削除、条件付き再蓄積、XRun への対処とする。いずれもクライアントだけで実装でき、Apollo/Sunshine の変更は不要。Rust のクライアント全体を導入せず、既存の native AAudio renderer に C のポリシーとして移植できる。

1. `writeFrames()` は無加工 PCM の投入を担当する。現行のパケットごとの `stretchFrames()` と 1〜3% の補正率制御を適応モードから外す。
2. `dataCallback()` に、出力時間を基準に動く平均量・過剰蓄積・near-miss・適応目標・再蓄積の状態を持たせる。目標の増減と実際のリング量を別々に記録する。
3. read cursor を所有する callback が古い区間を削除し、事前確保した作業領域でクロスフェードを出力する。SPSC の producer から read cursor を動かさない。複製を実装する場合も、producer の write cursor を callback から動かす方式は避ける。ステレオ・5.1・7.1 のチャンネル境界と各チャンネル共通のフェード重みを守る。
4. 再蓄積中も AAudio は稼働させる。stop/flush/reopen を伴う経路にせず、無音の callback 中にリングを蓄積する。単発の不足に毎回反応させない。PCM と無音の境界にも短いフェードを加える設計を検証する。
5. 既存の XRun API と出力バッファ設定 API を使い、容量まで burst 単位で増やす。リング不足・端末 XRun・再蓄積回数・削除量を独立して診断する。
6. 共用 Wi-Fi を想定したこのフォークでは、初期の候補は 40 ms 開始とする。punktfunk の 25 ms 設定も比較対象にし、実機で安全に下げられる量を決める。固定 40/60/80/120 ms モードと同じ音源・回線で比較できるようにする。

音声が来ないときの PLC は次の段階に分ける。現在は direct submit の条件下で受信スレッドが decoder を所有するため、AAudio callback や別タイマーから同じ Opus decoder を呼ぶと並行アクセスになる。補完の deadline、RTP の順序、既存の欠損 placeholder を一つの decoder 所有者にまとめる設計が先に必要である。これを処理せずに `DroughtConceal` だけを追加する案は採らない。

A/V 同期の全面移植は、さらに時刻情報の整備が必要になる。punktfunk は音声 packet のホスト capture `pts_ns`、ホストとの時計差、確定した映像表示時刻、AAudio timestamp から求めた端末出力遅延を使う。約 2 秒の平均、100 観測の準備、10 ms の deadband を経て目標を要求し、連続再生のための最低バッファ量を優先する。Android の映像表示が確定できない場合には、同期ループは動かさない。

現行の [AudioRendererDecodeAndPlaySample](app/src/main/jni/moonlight-core/moonlight-common-c/src/Limelight.h):353 は Opus bytes と長さだけを受け取る。音声 RTP timestamp 自体は受信側に存在するが、renderer に seq や capture PTS を渡していない。映像の `presentationTimeMs` もそのまま punktfunk のホスト時刻ではない。音声・映像の共通の時刻基準を定義してから同期する必要があり、`AvSync` の式だけを移しても成立しない。まずバッファ改善を成立させ、同期は独立した段階で検証する。

punktfunk 独自の redundant audio plane（`0xD2`）、lossless PCM plane、ホスト側のキャプチャ改善も今回のクライアント移植だけでは使えない。一方、既存の Moonlight 音声 FEC は維持して使える。

ソースは MIT OR Apache-2.0 の選択ライセンス。MIT 側には unom - Enrico Bühler の著作権表示と許諾文の保持条件がある。C 移植時には対象ソースの由来と固定 commit を示し、[LICENSE-MIT](https://git.unom.io/unom/punktfunk/src/commit/d016f73683b6b96ca64da99679ca5f4005846726/LICENSE-MIT)を同梱する。移植するコードの個別の表示も確認する。

**実施済みの検証と、実機で確認すべき点。**

punktfunk の `jitter.rs`・`sync.rs`・`recovery.rs` を変更せず、元のサンプル数変換 helper を取り出した独立した Rust harness に組み込み、各ファイル内の unit test を実行した。**68 passed / 0 failed**。クロックずれ、到着の偏り、near-miss、再蓄積、縮小試行の失敗、同期、欠損補完が含まれる。これはポリシーの単体試験であり、Android クライアント全体や native C 移植後の動作を検証した結果ではない。

Java 参照モデルでは、440 Hz と 3 kHz の原音および ±3% 補正の WAV を生成した。数値・ソース hash は [保存した結果](research/audio-buffer-probe-results-2026-10-02.json)、生の test log と WAV は `out/punktfunk-audio-research/` にある。例えば [440 Hz 原音](out/punktfunk-audio-research/tone_440_rate_1.0.wav)、[440 Hz・0.97 倍](out/punktfunk-audio-research/tone_440_rate_0.97.wav)、[3 kHz 原音](out/punktfunk-audio-research/tone_3000_rate_1.0.wav)、[3 kHz・0.97 倍](out/punktfunk-audio-research/tone_3000_rate_0.97.wav) を比較できる。

再現には Python 標準ライブラリ、JDK、Rust が必要。追加の Python パッケージは不要。`--punktfunk` に上記 commit のソース checkout を指定する。

```powershell
python research/audio-buffer-probe.py --punktfunk C:\path\to\punktfunk
```

次の実装では、まず加工不要の条件で PCM がそのまま出力されることを確認する。バッファの unit test は短い Wi-Fi 揺らぎだけでなく、25〜100 ms の到着バースト、50〜200 ms の停止、±50〜500 ppm のクロックずれ、異なる callback サイズ、長時間での遅延増加を扱う。縮小に失敗した場合に繰り返し再試行して音を切らないことも必要になる。

Galaxy S26 では、固定バッファ・現行適応・移植版を同じ声／音楽／持続音で比較し、音質、実際の音声遅延、リング不足、XRun、再蓄積、削除・補完の回数を同時に測る。特に曲の持続音に周期的なうねりが出ないことと、短い通信停止の後に細かい音切れが続かないことを確認する。pkt の 25 ms という数値だけを性能目標にせず、聴感が良好で安定している量を採用する。

コード検索 graph は index status・search・coverage 確認がいずれも `Transport closed` で利用できなかったため、呼び出し箇所と対象ソースを直接読んで確認した。公開サイトのブラウザ取得も一部制限されたが、Git の取得は成功しており、旧版の記事だけから最新実装を推測した調査ではない。今回の数値試験では Android build、端末接続、聴感による実機判定は行っていない。
