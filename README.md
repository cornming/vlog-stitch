# 接片室 vlog-stitch

在手機瀏覽器裡把好幾段影片接成一支，燒上字幕，匯出成可以直接上傳 YouTube 的檔案。

單一個 `index.html`，沒有後端、沒有建置流程、沒有 npm 安裝。影片和聲音全程留在手機裡，不會上傳到任何伺服器。

---

## 怎麼開始用

**方式一：GitHub Pages（推薦，手機最方便）**

1. 這個 repo → Settings → Pages → Source 選 `Deploy from a branch`，分支選 `main`、資料夾選 `/ (root)`
2. 等一兩分鐘，會得到 `https://<你的帳號>.github.io/vlog-stitch/`
3. 手機用 Chrome 開這個網址，加到主畫面就跟 App 一樣

需要 HTTPS 的功能（螢幕恆亮、WebGPU 加速）在 Pages 上才會生效，所以不建議直接用 `file://` 開。

**方式二：本機起一個伺服器**

```bash
python3 -m http.server 8080
# 瀏覽器開 http://localhost:8080
```

## 支援環境

| 環境 | 合併／匯出 | 自動字幕 |
|---|---|---|
| Android Chrome | 可 | 可（建議 Wi-Fi） |
| 桌機 Chrome / Edge | 可 | 可 |
| iOS Safari 17+ | 大致可 | 記憶體吃緊，容易失敗 |
| Firefox | 匯出為 webm | 需開啟 Worker 模組設定 |

---

## 功能

**合併與裁切**
- 一次選多段影片，上下箭頭排順序
- 每段可以獨立裁掉頭尾（拖滑桿，或播到某個點按「目前位置設為起點」）
- 比例不一樣的素材可以選「留黑邊」或「裁切填滿」
- 輸出 720p / 1080p，直式或橫式

**字幕**
- 自動聽打：Whisper 模型直接在瀏覽器跑，支援中／英／日，跑在背景執行緒不卡畫面，可隨時停止
- 字幕列表可以逐句改字、改時間，全選、勾選刪除、清空
- 五種樣式：經典、綜藝、底條、細字、大字卡；位置上中下、大小 60%–180%
- 可以匯入／匯出 SRT，跟其他工具接得起來

**匯出**
- 輸出 mp4（瀏覽器支援時）或 webm，兩種 YouTube 都吃
- 手機支援的話可以直接用系統「分享」丟進 YouTube App

---

## 幾個一定要知道的限制

**匯出是照實際速度跑的。** 5 分鐘的成品就要錄 5 分鐘，中途不能切到別的 App、不能讓螢幕關掉，不然畫面會停住、成品會斷在那裡。工具會自動申請 Screen Wake Lock，但還是別把手機放著不管。

**這不是重新編碼，是「螢幕錄影」。** 底層用 Canvas 逐格畫 + MediaRecorder 錄下來，所以畫質會比原始素材差一點，接點也可能有幾格空白。要無損剪接得用 ffmpeg.wasm 或 WebCodecs，那是另一個量級的工程。

**webm 沒有總長度資訊。** MediaRecorder 產出的 webm 在部分播放器會顯示不出時間長度，這是已知行為，YouTube 上傳不受影響。

**自動聽打很吃資源。** 第一次要下載模型（tiny 約 40MB、base 約 80MB、small 約 250MB）。辨識跑在 Web Worker 裡，過程中畫面照樣能操作，也可以隨時按停止。素材很長的話建議先用 tiny 試。

---

## 程式架構

全部在 `index.html` 裡，分成幾塊獨立的模組物件：

```
state              單一狀態物件：clips[]、subs[]、樣式與輸出設定
├─ clips           片段管理：加入、探測 metadata、排序、裁切、產生播放用 video 元素
├─ subs            字幕資料：新增、勾選、刪除、SRT 匯入匯出、查詢某時間點的字幕
├─ engine          播放與匯出引擎（核心）
├─ asr             Whisper 自動聽打
└─ ui              所有畫面渲染與事件
```

**engine 的運作方式**

1. `outSize()` 依素材方向和設定算出輸出畫布尺寸
2. 開始前先把每段影片都 seek 到各自的起點，減少接點空白
3. 建立 `AudioContext`：每段影片一個 `MediaElementSource`，同時接到
   - `recDest`（`MediaStreamDestination`，錄進成品）
   - `monitor`（GainNode → 喇叭，可靜音）
4. `canvas.captureStream(30)` 拿視訊軌，加上 `recDest` 的音訊軌，餵給 `MediaRecorder`
5. 依序播放每段，`requestAnimationFrame` 逐格把畫面畫進 canvas，同時查當下時間該顯示哪句字幕、直接畫上去（燒錄字幕）
6. 播到 `trimEnd` 換下一段，全部跑完停止錄製，組成 Blob

字幕時間軸是**成品的時間軸**（全域），不是各段自己的時間。`clipOffset(i)` 負責換算。

**字幕繪製**

`tokenize()` 把中文拆成單字、英文拆成單詞，`wrapLines()` 再貪婪塞行，所以中英夾雜也能正確斷行。`drawSubtitle()` 依樣式畫描邊、底條或陰影，字級是畫布高度的比例，不同解析度看起來一樣大。

**自動聽打**

推論跑在 Web Worker 裡，主執行緒不會被佔住。Worker 的原始碼放在 `<script id="asrWorkerSrc" type="text/worker">` 區塊，瀏覽器不會執行它；主程式讀 `textContent` 包成 Blob URL，再用 `new Worker(url, {type:'module'})` 啟動，所以整包還是維持單一檔案。

分工是這樣：

- **主執行緒**負責取聲音。`asr.pcm()` 用 `decodeAudioData` 解碼，再用 `OfflineAudioContext` 重採樣成 16kHz 單聲道（Whisper 要求的格式），只取裁切範圍那一段。`AudioContext` 在 Worker 裡拿不到，所以這步必須留在主執行緒。
- **Worker** 負責推論。模型從 CDN 動態 `import()` 進來，有 WebGPU 就用 WebGPU，沒有就退回 WASM，載入一次之後所有片段共用。

兩邊用 `{id, type, payload}` 訊息溝通，`asr.jobs` 這個 Map 依 id 對應回各自的 Promise。PCM 用 transferable 傳過去（`postMessage(msg, [pcm.buffer])`），不會複製一份。

按停止就直接 `terminate()` 掉 Worker，當場中斷，這是搬進 Worker 之後才做得到的事。如果瀏覽器不支援 module worker（例如沒開設定的 Firefox），會自動退回主執行緒模式，只是畫面會頓、停止要等當前片段跑完。

---

## 之後可以做的

- [ ] 匯出改用 WebCodecs + mp4 muxer，可以比即時快很多，畫質也不會二次損失
- [ ] 修補 webm 的 duration metadata
- [ ] 轉場、背景音樂、靜音段自動偵測
- [ ] 用 IndexedDB 存草稿，關掉頁面不會全部重來

---

## 授權

MIT
