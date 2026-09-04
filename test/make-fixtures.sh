#!/bin/bash
# 產生測試素材：三段同規格（1080x1920 / 60fps / H.264 + AAC 48kHz 立體聲），
# 各用不同頻率的音調，方便用頻譜驗證接續順序與聲音同步。
# 外加一段規格不同的 odd.mp4，用來驗證自動退回重新編碼。
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
echo "測試素材已就緒"
