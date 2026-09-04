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
- 輸出 720p / 1080p，直式或橫式，幀率可壓到 30fps（60fps 素材的編碼量直接減半）

**字幕**
- 自動聽打：Whisper 模型直接在瀏覽器跑，支援中／英／日，跑在背景執行緒不卡畫面，可隨時停止
- 字幕列表可以逐句改字、改時間，全選、勾選刪除、清空
- 五種樣式：經典、綜藝、底條、細字、大字卡；位置上中下、大小 60%–180%
- 可以匯入／匯出 SRT，跟其他工具接得起來

**匯出**　三種方式，在匯出頁自己選：

| 方式 | 速度 | 字幕 | 用在什麼時候 |
|---|---|---|---|
| 快速接片 | 幾秒 | 另存 SRT | 片段編碼、解析度都一樣時的最佳選擇，畫質零損失 |
| 重新編碼 | 比即時快數倍 | 燒進畫面 | 要硬字幕，或素材規格不一致 |
| 即時錄製 | 跟影片一樣長 | 燒進畫面 | 瀏覽器沒有 WebCodecs 時的相容退路 |

- 輸出 mp4，可以直接上傳 YouTube
- 手機支援的話可以直接用系統「分享」丟進 YouTube App
- 有可以連線的話會寫進 OPFS 檔案而不是記憶體，長片才不會爆掉
- 匯出頁有**診斷記錄**，逐段列出編碼、解析度、可否解碼、封包數與出錯位置，可複製或存檔

---

## 幾個一定要知道的限制

**快速接片有前提。** 所有片段的影像編碼、解析度、編碼參數必須一致，聲音的編碼、取樣率、聲道數也要一致。同一支手機用同一個 App 拍的通常沒問題，混到不同來源就不行。不符合時會自動改用重新編碼，並在畫面上說明原因。

**快速接片的裁切點會對齊關鍵影格。** 因為完全沒有重新編碼，只能從關鍵影格開始切，實際起點可能比你設定的早一兩秒。匯出後會告訴你有幾段被移動過。需要精準裁切就用重新編碼模式。

**聲音解碼有可能失敗。** 實測在 Android 上遇過 `canDecode()` 回報 true、但 WebCodecs 的 `AudioDecoder` 建立時仍丟 `EncodingError: Decoding error.`。重新編碼模式因此採三層退路：WebCodecs → Web Audio 的 `decodeAudioData` → 等長靜音。任何一層成功就繼續，不會讓整趟匯出白跑，實際走到哪一層記錄裡都寫得清楚。

**手機不一定解得開 HEVC。** 不少 Android 手機預設用 H.265 錄影，但 Chrome 的 WebCodecs 對 HEVC 支援看裝置。重新編碼模式開始前會先用 `canDecode()` 檢查，解不開會直接擋下並指出是哪一段，不會跑到一半才失敗。快速接片不需要解碼，所以不受影響。

**只有即時錄製模式會跑滿影片長度。** 那是 MediaRecorder 錄 canvas 的舊做法，留著當退路。匯出中都不要切到別的 App。

**自動聽打很吃資源。** 第一次要下載模型（tiny 約 40MB、base 約 80MB、small 約 250MB）。辨識跑在 Web Worker 裡，過程中畫面照樣能操作，也可以隨時按停止。素材很長的話建議先用 tiny 試。

---

## 程式架構

全部在 `index.html` 裡，分成幾塊獨立的模組物件：

```
state              單一狀態物件：clips[]、subs[]、樣式與輸出設定
├─ clips           片段管理：加入、探測 metadata、排序、裁切、產生播放用 video 元素
├─ subs            字幕資料：新增、勾選、刪除、SRT 匯入匯出、查詢某時間點的字幕
├─ dbg             診斷記錄，出問題時唯一能看的東西
├─ mb / xport      WebCodecs 匯出引擎（核心，用 mediabunny）
├─ engine          即時預覽與 MediaRecorder 退路
├─ asr             Whisper 自動聽打
└─ ui              所有畫面渲染與事件
```

**xport：兩條快速路徑**

兩條都建立在 [mediabunny](https://mediabunny.dev) 上，它把 WebCodecs 包成好用的 API，並補上 WebCodecs 沒有的 mux／demux。從 CDN 動態 `import()`，不進版本庫。

*快速接片（`xport.remux`）*　完全不碰畫素。用 `EncodedPacketSink` 把每段的封包讀出來，`packet.clone({timestamp})` 位移時間戳，再用 `EncodedVideoPacketSource` / `EncodedAudioPacketSource` 寫進同一個 mp4。第一個封包要附上 `decoderConfig`，後面就不用。裁切用 `getKeyPacket(trimStart)` 找關鍵影格起點，所以會有對齊誤差。開跑前先用 `checkCompat()` 比對所有片段的編碼參數，包含 `description` 的位元組，不一致就擋下來改走重新編碼。

*重新編碼（`xport.encode`）*　**每段先處理聲音再處理影像**。聲音便宜又最容易失敗，先跑才能在花好幾秒編完影像之前就知道出事。影像用 `VideoSampleSink.samples(start, end)` 逐格取出解碼後的畫面，`drawWithFit()` 依「留黑邊／裁切填滿」畫進 canvas，疊上該時間點的字幕，再交給 `CanvasSource.add(timestamp, duration)` 硬體編碼。聲音走 `AudioSampleSink` → `toAudioBuffer()` → `AudioBufferSource.add()`；`AudioBufferSource` 會把每個 buffer 依序接在前一個後面，所以不用自己算時間戳。**沒有聲音軌的片段會補一段等長靜音**，否則後面所有片段的聲音都會提前，畫面對不上嘴。

**匯出前的預檢**

`xport.preflight()` 在動工前先把每段的規格全部寫進診斷記錄：編碼字串、coded 尺寸、旋轉、fps、`description` 長度、聲音參數，以及最重要的 `canDecode()`。重新編碼模式下只要有一段解不開就直接擋下，訊息會指名是第幾段、什麼編碼，不會跑到一半才丟一句 `Decoding error`。

**位元組比對的坑**

`decoderConfig.description` 是 TypedArray 視圖，直接讀 `.buffer` 會拿到整個底層緩衝區（實測是幾萬位元組，而 hvcC 其實只有幾百）。這會讓相容性判斷永遠誤判成「參數不同」，把本來可以秒出的素材推去重新編碼。`toBytes()` 用 `byteOffset` / `byteLength` 取出真正的內容，記錄裡也會印出長度和開頭幾個位元組方便核對。

**輸出目的地**

預設寫進 OPFS（`navigator.storage.getDirectory()` → `createWritable()` → mediabunny 的 `StreamTarget`），完成後用 `getFileHandle().getFile()` 取回。這樣成品不會整個堆在 JS heap 裡——43 分鐘的 1080p 光是緩衝就好幾 GB，手機一定撐不住。環境不支援時才退回 `BufferTarget`，記錄裡會註明用的是哪一種。

**engine 的運作方式（即時錄製退路）**

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

- [ ] 把 xport 也搬進 Worker，重新編碼時畫面就不會頓
- [ ] 快速接片支援關鍵影格之間的精準裁切（頭尾局部重編碼）
- [ ] 轉場、背景音樂、靜音段自動偵測
- [ ] 用 IndexedDB 存草稿，關掉頁面不會全部重來

---

## 授權

本專案採 MIT。

執行時會從 CDN 載入兩個函式庫，都沒有打包進版本庫：

- [mediabunny](https://github.com/Vanilagy/mediabunny)（MPL-2.0）— 影片讀寫與轉換
- [transformers.js](https://github.com/huggingface/transformers.js)（Apache-2.0）— Whisper 自動聽打
