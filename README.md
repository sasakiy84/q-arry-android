# Q-arry

Android端末内にフィードを登録し、記事をキューごとに読めるRSS・Atomリーダーです。

- 「フィードを追加」にサイトやフィードのHTTPS URLを入力します。サイトが公開するRSS・Atomへのリンクと、よく使われるフィードURLを確認します。
- 「フィード」の「インポート」からOPMLファイルを選び、HTTPSのRSS・Atomをまとめて登録できます。登録済みのURLはスキップします。
- RSS・Atomが見つからない場合は、HTMLの繰り返しから記事一覧を探します。登録前に記事URLと、見つかったタイトル・説明文・サムネイルURLを確認できます。定期更新には登録時の抽出ルールを使います。
- 「キュー」で分類を作り、「フィード」から所属先を選びます。一つのフィードを複数のキューに登録できます。
- 「記事」はキューや未読で絞り込めます。フィードに含まれる本文を読み、ブラウザで配信元の記事を開けます。
- 更新ボタンで手動取得できます。ネットワークに接続できるときは、Androidが約1時間間隔を目安にバックグラウンド更新を実行します。

## 開発

JDK 21、Android SDK 36、Build Tools 36.0.0を使用します。最低対応OSはAndroid 16です。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

## 配布

`vMAJOR.MINOR.PATCH` タグでGitHub Actionsが起動します。ビルドジョブでテストとLintを実行し、別のジョブで署名済みAPK・AABをGitHub Releasesに公開します。

Q-arryのGitHubリポジトリで **Settings → Secrets and variables → Actions → Secrets** を開き、次の2件を登録します。

| 名前 | 値 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | リリース用キーストアをBase64でエンコードした文字列 |
| `ANDROID_KEYSTORE_PASSWORD` | キーストアと秘密鍵に共通のパスワード |

署名鍵のエイリアスは `qarry-release` で、RSA鍵を使います。APKとAABには同じ鍵を使うため、アップデートに備えてキーストアを保管してください。ワークフローを含むコミットに `v0.1.0` のようなタグを付けてGitHubにプッシュすると、リリースが開始します。
