# TouringProjectV2_Android

TouringProjectV2 の Android アプリ（Kotlin・Jetpack Compose）。
インカムのボタンで質問を始め、回答を背面で待って読み上げ、ナビのアプリへ戻る。
プロジェクト全体の要件・方針・契約は [docs-parent/](docs-parent/README.md)（親リポジトリの写し。編集しない）。

## 構成

| 場所 | 中身 |
|---|---|
| `app/src/main/java/com/touringproject/v2/ask/` | 一巡の状態機械（`Flow.kt`）・司令塔（`AskController.kt`）・外との窓口（`AskPorts.kt`）・ポーリング・会話・失敗の読み上げ文 |
| `.../api/` | Backend の API（`Backend/openapi.yaml`） |
| `.../audio/` | インカムの経路・録音・再生・端末の音声・合図 |
| `.../location/` | 質問の位置の監視と方位の材料の選び方・走行のルートの位置の頼み方と点のふるい分け（`Route.kt`） |
| `.../ride/` | 走行の型と計算（`Ride.kt`、純粋）・司令塔（`RideController.kt`）・外との窓口（`RidePorts.kt`） |
| `.../store/` | 端末の DB（Room）。スキーマは `app/schemas/` に版ごとに残す |
| `.../handsfree/` | 押下の重複・古い合図の除外・押下の行き先（試し録り中は試し録りへ） |
| `.../recordings/` | 録音の置き場（直近10件と付随情報）・試し録りの司令塔・録音の確認（SC-12）の状態 |
| `.../service/`・`.../nav/` | 前面サービス（走行の記録と一巡で1つ。種類の取りまとめは `ServiceCoordinator.kt`）・締め切りのアラームと自動終了の通知（`RideAlarms.kt`）・ナビを開く |
| `.../settings/` | 設定（DataStore）と接続キー（Keystore で暗号化） |
| `.../ui/` | 画面（Compose） |

## 大事な決まり

- 走行の記録中と一巡のあいだは、前面サービスを動かす（無いと、画面を消したあと位置・通信・再生が止まる）。種類は、記録中は `location`、一巡のあいだは `microphone|mediaPlayback` を足す。Android 14 以降、この2つはアプリが前面にあるときにしか始められず、足せない（理由は親の `adr/0006`）。
- 走行の状態を変えるのは `RideController` だけ。サービス・締め切りのアラーム・電源を切る知らせ・画面は、どれもこれを呼ぶ。起動したときは `load()` が先に、残っていた走行を片付ける（端末が止まっていれば終了、締め切りを過ぎていれば自動終了、それ以外は記録の再開）。
- Room のスキーマを変えるときは、版を上げて Migration を書き、`app/schemas/` に書き出された JSON もコミットする。破壊的な作り直しは使わない。
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

ログは `adb logcat -s trv2.ask trv2.route trv2.ride trv2.recordings`。

## 走行用のビルド

開発用のビルドも USB を外して動くが、走行では release を入れる（最適化され、デバッグの印が付かない）。署名は開発用の鍵と同じなので、上書きしても設定・接続キー・録音は残る。

```sh
./gradlew assembleRelease
scripts/check-release-permissions.sh            # 許した一覧の外の権限があれば失敗する
adb install -r app/build/outputs/apk/release/app-release.apk
```

端末に2つのユーザーがあるときは、`adb install --user 0 -r ...` のようにユーザーを指す。
