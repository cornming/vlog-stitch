# 簽章金鑰

`vlog-stitch.jks` 是這個 App 的固定簽章金鑰。

## 為什麼要有它

Android 規定：覆蓋安裝的 APK 必須跟已安裝的那一版**用同一把金鑰簽章**，
否則會被拒絕並顯示「未安裝應用程式」。

先前 Gradle 設定成用 debug 簽章，而 debug 金鑰是建置機器自動產生的。
GitHub Actions 的 runner 每次都是全新機器，等於每次建置都換一把金鑰，
所以每次更新都裝不起來。實測 v1.0.0 到 v1.3.0 的憑證指紋全部不同。

## 安全性取捨

這把金鑰放在公開 repo 裡，代表任何人都能簽出一個 Android 會認可為
「這個 App 的更新」的 APK。因為只有你自己會從自己的 Releases 頁安裝，
實務風險很低，但確實存在。

想改成不公開的話：

1. `base64 -w0 vlog-stitch.jks` 取得字串
2. 到 repo 的 Settings → Secrets and variables → Actions 新增
   `RELEASE_KEYSTORE_B64`、`RELEASE_STORE_PASSWORD`、
   `RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`
3. 把這個資料夾從版本庫刪掉

workflow 已經寫成「有 secret 就用 secret，沒有才用這裡的金鑰」，
所以做完上面三步不需要改任何程式。

## 換金鑰的代價

一旦換掉，就得先解除安裝舊版才裝得起來。所以能不換就不要換。
