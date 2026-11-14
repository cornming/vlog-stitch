"""在 JPEG 開頭塞一段 EXIF：拍攝時間，以及選填的旋轉方向。

ffmpeg 產生的測試照片沒有 EXIF，這支小工具把它補上，
才驗得到「依拍攝時間排序」和「照片自動轉正」。

用法：python3 exif.py 來源.jpg 輸出.jpg "2026:11:14 08:12:40" [旋轉方向 1-8]
"""
import struct
import sys

src, dst, when = sys.argv[1:4]
orient = int(sys.argv[4]) if len(sys.argv) > 4 else 0

entries = []                      # IFD0 的項目，要照 tag 由小到大排
if orient:
    entries.append(struct.pack('<HHIHH', 0x0112, 3, 1, orient, 0))   # Orientation
exif_at = 8 + 2 + 12 * (len(entries) + 1) + 4                        # Exif 子目錄的位置
entries.append(struct.pack('<HHII', 0x8769, 4, 1, exif_at))          # 指向 Exif 子目錄
date_at = exif_at + 2 + 12 + 4

tiff = b'II' + struct.pack('<HI', 42, 8)
tiff += struct.pack('<H', len(entries)) + b''.join(entries) + struct.pack('<I', 0)
tiff += struct.pack('<H', 1) + struct.pack('<HHII', 0x9003, 2, 20, date_at) + struct.pack('<I', 0)
tiff += when.encode('ascii')[:19].ljust(19) + b'\0'                  # DateTimeOriginal

app1 = b'Exif\0\0' + tiff
data = open(src, 'rb').read()
assert data[:2] == b'\xff\xd8', '來源不是 JPEG'
open(dst, 'wb').write(data[:2] + b'\xff\xe1' + struct.pack('>H', len(app1) + 2) + app1 + data[2:])
