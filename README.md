# TouringProjectV2_Android

TouringProjectV2 の Android アプリ（Kotlin・Jetpack Compose）。
インカムのボタンで質問を始め、回答を背面で待って読み上げ、ナビのアプリへ戻る。
プロジェクト全体の要件・方針・契約は [docs-parent/](docs-parent/README.md)（親リポジトリの写し。編集しない）。

## 構成

| 場所 | 中身 |
|---|---|
| `app/src/main/java/com/touringproject/v2/ask/` | 一巡の状態機械（`Flow.kt`）・司令塔（`AskController.kt`）・外との窓口（`AskPorts.kt`）・質問の記録の組み立て（`AskRecord.kt`）・ポーリング・会話・失敗の読み上げ文 |
| `.../api/` | Backend の API（`docs-parent/07a_openapi.yaml`）。`GET /records` で読み戻す記録の型は `Records.kt` |
| `.../audio/` | インカムの経路・録音・再生・端末の音声・合図 |
| `.../location/` | 質問の位置の監視と方位の材料の選び方・走行のルートの位置の頼み方と点のふるい分け（`Route.kt`） |
| `.../ride/` | 走行の型と計算（`Ride.kt`、純粋）・司令塔（`RideController.kt`）・外との窓口（`RidePorts.kt`） |
| `.../store/` | 端末の DB（Room）・質問の置き場（`RoomAskLog.kt`）・質問のメモと削除（`RoomAskEdits.kt`）・地点メモ（`RoomNotes.kt`）・送信の置き場（`RoomSyncStore.kt`）・記録の画面の読み出しと未送信の数（`RoomRecords.kt`）・クラウドの記録を1つのトランザクションで入れる係（`RoomTakeover.kt`）。スキーマは `app/schemas/` に版ごとに残す |
| `.../records/` | 記録一覧の並べ方と保存の状態（`Records.kt`、純粋）・SC-14 の数（`Saving.kt`）・メモの決まり（`Memos.kt`、純粋）・地点メモの型と位置の選び方（`Notes.kt`、純粋）・質問と地点メモを変える窓口（`AskEdits.kt`・`NoteEdits.kt`） |
| `.../map/` | 地図（MapLibre と国土地理院の淡色地図）。描くデータの組み立ては `MapData.kt`（純粋）、地図の部品は `RouteMap.kt`、スタイルは `app/src/main/assets/map/gsi-pale.json` |
| `.../sync/` | 走行・ルート・地点メモ・質問できなかった地点・メモ・削除の送信。何を送るかの判断（`SyncPlan.kt`、純粋）と送信の係（`RideSync.kt`）。クラウドの記録の読み込み: 突き合わせの決まり（`Merge.kt`、純粋）と読み込みの係（`Takeover.kt`） |
| `.../handsfree/` | 押下の重複・古い合図の除外・押下の行き先（試し録り中は試し録りへ、SC-19 を開いているあいだは声の入力へ） |
| `.../voice/` | SC-19 の声の入力（`VoiceInput.kt`）。録音・文字起こしの依頼・取りに行く処理・失敗の理由 |
| `.../net/` | 端末が通信できるか（`Connectivity.kt`。SC-19 の声の入力・質問の一巡・送信が使う） |
| `.../recordings/` | 録音の置き場（直近10件と付随情報）・試し録りの司令塔・録音の確認（SC-12）の状態 |
| `.../service/`・`.../nav/` | 前面サービス（走行の記録と一巡で1つ。種類の取りまとめは `ServiceCoordinator.kt`）・締め切りのアラームと自動終了の通知（`RideAlarms.kt`）・ナビを開く |
| `.../settings/` | 設定（DataStore）と接続キー（Keystore で暗号化）・最後に保存した時刻（`LastSaved.kt`） |
| `.../ui/` | 画面（Compose）。タブとバックスタックの組は `Tabs.kt`（Compose に依存しない）、タブの帯は `TabBar.kt`。記録の画面は `RecordsScreens.kt`（地図は差し替えられる枠で受ける）、その表示の組み立ては `RecordLabels.kt`（純粋）、読み出しとのつなぎは `RecordsRoutes.kt`。メモの編集（SC-08）は `MemoScreen.kt`（SC-19 と共通の編集部品 `MemoEditor` を含む）、地点メモ（SC-19）は `NoteScreen.kt`、削除の確認（DL-01）は `DeleteDialog.kt` |

## 大事な決まり

- 走行の記録中と一巡のあいだは、前面サービスを動かす（無いと、画面を消したあと位置・通信・再生が止まる）。種類は、記録中は `location`、一巡のあいだは `microphone|mediaPlayback` を足す。Android 14 以降、この2つはアプリが前面にあるときにしか始められず、足せない（理由は親の `adr/0006`）。
- 走行の状態を変えるのは `RideController` だけ。サービス・締め切りのアラーム・電源を切る知らせ・画面は、どれもこれを呼ぶ。起動したときは `load()` が先に、残っていた走行を片付ける（端末が止まっていれば終了、締め切りを過ぎていれば自動終了、それ以外は記録の再開）。最長時間を変えたときの締め切りの計算し直しも `maxRecordingChanged()` が行う（`AppGraph` が設定の変化を受けて呼ぶ）。名前の変更は `rename()`、走行のメモは `memo()`、終えた走行の削除は `delete()`（書き直し待ちの保存も捨てるので、消した走行が書き戻されない）。
- 質問を受けるのは走行の記録中だけ。`AskController.run` の入口で `RideNow`（記録中・一時停止中・記録していない）を見て、記録中でなければ録音を始めず、理由を読み上げる。画面の「押して話す」も押せない形にする（親の spec 2 §4.7・adr/0008）。
- Room のスキーマを変えるときは、版を上げて Migration を書き、`app/schemas/` に書き出された JSON もコミットする。破壊的な作り直しは使わない。スキーマの JSON は debug のビルドの資産にも入る（Robolectric の `MigrationTestHelper` が読むため。release には入らない）。Entity や Migration を変えた直後のテストは `--rerun` で確かめる。
- 走行とルートは `RideSync` が送る。契機は、走行の変更を書けたとき・このプロセスで点を300書くごと・アプリが前面に来たとき（`MainActivity.onStart`）・起動して設定を読んだあと・通信できるようになったとき（`reconnections`。プロセスが生きているあいだだけ）。契機では未送信を調べるだけで、送るものが無ければ通信しない。通信できないあいだは、契機が来ても通信を試さない。ルートは記録した順に300点ずつのかたまりで送り、番号 k には 300k 番目からの点が入る。記録中は満ちたかたまりだけ、一時停止と終了では端数も送り、点が増えたら同じ番号で送り直す。400 は送り直さない（走行は `rides.rejectedUpdatedAt`、ルートはそのかたまりの点の `route_points.refused` に残す。同じ番号のかたまりが受け付けられたら下りる。`rides.routeRejected` は版2の名残りで、もう書かない）。走行の `updatedAt` は変わるたびに必ず進める（Backend は新しい版だけを残す）。走行のメモはメタ情報の一部として送る。
- 1回の送信は、削除の印（`deletions`）→ 走行とルート → 地点メモ → 質問できなかった地点 → 会話のメモの順に送る（消したものの古い版を同じ回で置き直さないため。質問できなかった地点のメモが、Backend で質問を見つけられるため）。記録を消すときは、同じトランザクションで削除の印を書く。送れない削除の印（知らない種類）は、ログ `sync_unsendable` に出して消す（残すと、毎回の送信がそこで止まる）。410（Backend で消されている）は送れたものとして扱い、送り直さない。会話のメモへの 404 は断られたものとして扱う。端末で送る中身を組み立てられない（`SerializationException`）ものは、断られたものと同じに扱って次へ進む。断られた理由（Backend の `message`）はログ `sync_refused` に出る。接続キーかユーザー名を保存したときも送る（`credentialChanges`）。
- 地点メモ（SC-19）は、走行の記録中と一時停止中にホームの「📝 地点メモ」から開く。開いたときに、その走行の最後の点を位置に、質問と同じ選び方で方位の材料を決め（`noteDraft`）、保存するまで書かない。住所と方位は、最初に送ったときに Backend が決めて返し、端末はそれを行に書く（`notes.address`・`headingDegrees`）。地図のピンは緑で、質問とは別の通し番号。質問の一巡が終わったときに、その走行が消えていれば質問は書かない（`AskDao.keep`）。
- 質問できなかった地点（US-C-09）: 録音を終えた時点で通信できないとき、または `POST /asks` が通信の失敗（試し直しのあとの 5xx・流量の制限を含む）に終わったとき、質問の一巡は日時・位置・方位の材料を `asks` に `status = offline` で残す（識別子は端末で作る。会話の識別子は無い）。録音は捨て、SC-12 にも残さない。会話の文脈は進めない。接続キーの誤りなど、断られた失敗では残さない。書けたらすぐ送信の契機を呼ぶ（通信の失敗でも、既定のネットワークは残っていることがあるため）。`PUT /asks/{askId}/offline` で送り、応答の住所と方位を行に書く。409（同じ識別子の質問が Backend にある）は断られたものとして扱う。住所を決められずに受け付けられた地点メモと質問できなかった地点は、起動ごとに一度、版を進めて送り直す（Backend は新しい版でだけ住所を求め直す）。
- クラウドの記録を読み込むのは、SC-18 でユーザー名を変えるときと、SC-14 の「クラウドから読み直す」だけ（親の adr/0010）。`Takeover` が `GET /records` を最後のページまで読んでから、`RoomTakeover` が1つのトランザクションで突き合わせて入れる（`Merge.kt`: `updatedAt` が新しいほうを残す・クラウドの削除の跡で消す・端末の未送信の削除はクラウドの写しを入れない・ルートは点の多いほう・記録中のまま残っていた走行は終えて入れ、送り直す）。入れたものは送り済みの印を付ける。ユーザー名を変えるときは端末の記録を消してから入れ、そのあとで名前を保存する。読み始めから名前の保存までは `RideSync.withoutPasses` の中で、送信の回が走らない。走行の記録中・一時停止中は、どちらも行わない。ユーザー名は、未送信（SC-14 が数えるもの。断られた版と記録中の走行は数えない）があるあいだも変えられない。トランザクションの中でも確かめ直す。
- SC-19 の声の入力（`VoiceInput`）は、質問とは別の経路の切り替えと録音の部品を持つ（試し録りと同じ）。SC-19 を開いているあいだ（`attach`）は、インカムのボタンが質問を始めず、声の入力を始める・止める（質問が進んでいるときは質問のまま）。録音は60秒まで。`POST /transcriptions` に送り、質問と同じ間隔で `GET` し、120秒で打ち切る。聞き取れた文は入力欄の終わりに足す。通信できないときは画面のボタンを押せず、インカムのボタンは失敗の合図だけを鳴らす。SC-19 を離れると、録音も文字起こしの待ちも捨てる。前面サービスは使わない（SC-19 を見ながら使う前提）。
- インカムは音の出だしを落とす。合図は長さを指定できる音（`TONE_SUP_DIAL`・`TONE_DTMF_*`）で長めに鳴らし、開始の合図は録音が始まってから鳴らす。`TONE_PROP_*` は、長さを指定しても約0.16秒で止まる。
- インカムのボタンの経路では `setCommunicationDevice()` を呼ばない（`AudioRoute.kt` の冒頭のコメント）。
- 回答は音声の焦点（audio focus）を取らずに鳴らす（ナビの案内と重ねる）。
- `MainActivity` は `singleTask`。`ActivityScenario` はこれを追えないので、エミュレーターのテストは Activity を直接起動する。
- 録音の確認（SC-12）は、質問とは別の経路の切り替え・録音・再生を持つ。画面を離れても、読み上げ中の回答は止めない。
- 録音は `filesDir/recordings/` に `rec-<時刻>.pcm` と `rec-<時刻>.json` の組で直近10件を残す。片方しか無い組と読めない JSON は、次に残すときに消す。

## テスト

```sh
./gradlew testDebugUnitTest            # JVM（JUnit・Robolectric・Compose）。端末は要らない
./gradlew connectedDebugAndroidTest    # エミュレーター（下の「エミュレーター」）
```

Robolectric は SDK 35 で動かす（`app/src/test/resources/robolectric.properties`。SDK 36 には Java 21 が要る）。

`runTest` の `backgroundScope` で動かした処理は `advanceUntilIdle` で進まない。アプリの scope の代わりには `CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))` を使う（`RecordingCheckTest`）。実機が USB で繋がったままエミュレーターのテストを回すときは、`export ANDROID_SERIAL=emulator-5554` でエミュレーターだけを指す。

## エミュレーター

```sh
SDK=$HOME/Library/Android/sdk
$SDK/cmdline-tools/latest/bin/android --no-metrics emulator create medium_phone   # 初回だけ
$SDK/cmdline-tools/latest/bin/android --no-metrics emulator start medium_phone
./gradlew connectedDebugAndroidTest
$SDK/cmdline-tools/latest/bin/android --no-metrics emulator stop medium_phone
```

インカムの合図は `adb shell am start -a android.intent.action.VOICE_COMMAND -n com.touringproject.v2/.MainActivity` で送れる。

MapLibre は Robolectric では動かない（ネイティブのライブラリが要る）。地図の見た目とピンのタップは、エミュレーターの `RouteMapTest` で確かめる。記録の画面は地図を枠で受けるので、JVM のテストでは作り物の地図を渡す。`queryRenderedFeatures` は、地図が一度描き終える前に呼ぶとネイティブのコードが落ちる（`RouteMap` は、押した判定の受け口とテストへの `onMap` を、描き終えてから付ける）。

`RideRecordingTest` は、Fused の mock mode で作り物の位置を流す（テストが自分で、アプリを位置の作り物を出すアプリにする）。記録中の質問のテストは、`local.properties` に `trv2.apiBaseUrl` が無いと飛ばす。接続キーを入れて録音の途中で取り消し、最後にキーを消す。

## 実機で動かす

`local.properties`（追跡しない）に API の URL を書く。

```sh
URL=$(AWS_PROFILE=touring aws cloudformation describe-stacks --region ap-northeast-1 --stack-name stack-trv2-dev-backend \
  --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" --output text)
printf 'trv2.apiBaseUrl=%s\n' "$URL" >> local.properties
./gradlew installDebug
```

接続キーは、アプリの「設定 → 接続の設定」で入れる（Backend の README の手順で読む）。キーをどこにも表示せずに入れるときは、入力欄を開いた状態で `adb shell input text "$KEY"` で打ち込む。

ログは `adb logcat -s trv2.ask trv2.route trv2.ride trv2.sync trv2.recordings`。

## 走行用のビルド

開発用のビルドも USB を外して動くが、走行では release を入れる（最適化され、デバッグの印が付かない）。署名は開発用の鍵と同じなので、上書きしても設定・接続キー・録音は残る。

```sh
./gradlew assembleRelease
scripts/check-release-permissions.sh            # 許した一覧の外の権限があれば失敗する
adb install -r app/build/outputs/apk/release/app-release.apk
```

端末に2つのユーザーがあるときは、`adb install --user 0 -r ...` のようにユーザーを指す。

新しい plan の走行用のビルドを実機に入れる前に、`app/build.gradle.kts` の `versionName`（`0.2.0` → `0.3.0` のように）と `versionCode`（1つ上げる）を手で上げる。plan の外の修正（不具合の直しだけのブランチ）を実機に入れるときは、3つ目の数を上げる（`0.3.0` → `0.3.1`。`versionCode` も1つ上げる）。同じ plan や同じ修正の中の入れ直しでは上げない。入っている版は、アプリの「設定 → バージョン」で見る。コミットの番号はアプリに出さない。
