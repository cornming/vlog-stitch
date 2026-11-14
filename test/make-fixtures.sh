#!/bin/bash
# 產生測試素材：三段同規格（1080x1920 / 60fps / H.264 + AAC 48kHz 立體聲），
# 各用不同頻率的音調，方便用頻譜驗證接續順序與聲音同步。
# 外加一段規格不同的 odd.mp4，用來驗證自動退回重新編碼；
# 以及三張純色照片，用來驗證照片、轉場、色調與配樂混音。
set -e
cd "$(dirname "$0")"
for i in 1 2 3; do
  case $i in 1) SEC=3;; 2) SEC=4;; 3) SEC=2;; esac
  ffmpeg -y -loglevel error \
    -f lavfi -i "testsrc2=size=1080x1920:rate=60:duration=$SEC" \
    -f lavfi -i "sine=frequency=$((300*i)):sample_rate=48000:duration=$SEC" \
    -c:v libx264 -profile:v high -pix_fmt yuv420p -g 120 -b:v 4M \
    -c:a aac -ar 48000 -ac 2 -b:a 128k -movflags +faststart clip$i.mp4
done
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=720x1280:rate=30:duration=2" \
  -f lavfi -i "sine=frequency=1200:sample_rate=44100:duration=2" \
  -c:v libx264 -pix_fmt yuv420p -b:v 2M -c:a aac -ar 44100 -ac 1 \
  -movflags +faststart odd.mp4
# 照片：三張純色、比例各不相同，用畫面顏色就能驗證順序、轉場和色調
ffmpeg -y -loglevel error -f lavfi -i "color=c=0xD02818:s=1600x1200" -frames:v 1 -q:v 3 p_red.jpg
ffmpeg -y -loglevel error -f lavfi -i "color=c=0x18B030:s=1200x1600" -frames:v 1 -q:v 3 p_green.jpg
ffmpeg -y -loglevel error -f lavfi -i "color=c=0x1830D0:s=1920x1080" -frames:v 1 p_blue.png

# ---- 以下給瀏覽器測試（browser.js）用，檔名都是 b_ 開頭 ----
# 帶 EXIF 拍攝時間的照片，檔案順序故意跟拍攝時間不一樣
photo(){ # 檔名 lavfi來源 拍攝時間 [旋轉方向]
  ffmpeg -y -loglevel error -f lavfi -i "$2" -frames:v 1 -q:v 3 raw.jpg
  python3 exif.py raw.jpg "b_$1.jpg" "$3" $4
  rm -f raw.jpg
}
photo breakfast "gradients=s=1600x1200:c0=0xF2C879:c1=0xD9703A:x0=0:y0=0:x1=1600:y1=1200" "2026:11:14 08:12:40"
photo street    "gradients=s=1200x1600:c0=0x6FB3E0:c1=0x2E5E8C:x0=0:y0=0:x1=0:y1=1600"    "2026:11:14 10:47:05"
photo lunch     "mandelbrot=s=1600x1200:rate=1"                                           "2026:11:14 12:31:19"
photo sunset    "gradients=s=1920x1080:c0=0xF28E4B:c1=0x3B2A5C:x0=0:y0=0:x1=0:y1=1080"    "2026:11:14 17:20:33"
# 畫素是橫的 1600x1200，EXIF 標示要轉 90 度，瀏覽器應該當成直式 1200x1600
photo rot       "gradients=s=1600x1200:c0=0x7FD4C1:c1=0x2B6E8F:x0=0:y0=0:x1=1600:y1=0"    "2026:11:13 21:40:00" 6
# VP9＋Opus 的影片（沒有授權編碼的 Chromium 也解得開），聲音是 440Hz
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=3" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=3" \
  -c:v libvpx-vp9 -b:v 1M -deadline realtime -cpu-used 8 -c:a libopus -b:a 96k b_walk.webm
# 「自己的音樂」：880Hz 的 mp3，混進成品之後用頻率就認得出來
ffmpeg -y -loglevel error -f lavfi -i "sine=frequency=880:sample_rate=44100:duration=6" -c:a libmp3lame -q:a 5 b_song.mp3
# 瀏覽器解不開的檔案，用來驗證錯誤提示
head -c 4096 /dev/urandom > b_fake.heic
echo "測試素材已就緒"
