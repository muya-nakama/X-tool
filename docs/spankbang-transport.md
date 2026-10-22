# SpankBangの追加通信部品

SpankBangに限って別プロセス・別Python環境でブラウザー互換通信を試します。一般サイトの取得処理、旧版、アカウント保存は従来のままです。拒否の回避や保存成功を保証する機能ではありません。

## 出典

- 上流の提案: https://github.com/yausername/youtubedl-android/pull/361
- 同梱元: https://github.com/hussain-najeb/youtubedl-android/tree/35f2609d80ce26b6683a0b2b6bc52f32efe4c81c
- 上流ライセンス: GPL-3.0。Python、curl_cffi、cffi、OpenSSL等の各ライセンスも適用されます。パッケージ内のライセンスを保持しています。
- Python 3.14 / curl_cffi 0.16.2 / cffi 2.1.1。上流の未統合コミットに固定した試験機能です。
- `scripts/prepare_spankbang_runtime.py`に取得元・各ABIのSHA-256を固定。ビルド時とアプリ展開時に検証します。端末から実行ファイルをダウンロードする機能はありません。

- 専用取得エンジンは公式yt-dlp 2026.10.21のZIP実行ファイル（SHA-256: `b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14`）。通信部品との互換性のためAPKに同梱し、従来の共通エンジンを置換しません。

## 検証範囲

Android x86_64エミュレーター上でPythonと通信部品を起動し、example.comに通常の証明書検証でHTTPS通信するテストを用意しています。動画、成人サイト、アカウント情報はCIで扱いません。ARM64端末でのSpankBang取得・動画保存は別途実機検証が必要です。

キャンセル時は専用Pythonが作ったプロセスグループを停止します。URLや通信エラー原文はメモリー内だけで処理し、永続的なログには残しません。Python環境はアプリ専用・バックアップ対象外の場所で管理します。
