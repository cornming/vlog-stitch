#!/bin/bash
# 把 index.html 裡真正的程式抽出來，接上 Node 版 mediabunny，用真影片跑一遍
set -e
cd "$(dirname "$0")"
[ -f clip1.mp4 ] || ./make-fixtures.sh
python3 -c "
import re
s=open('../index.html',encoding='utf-8').read()
m=re.search(r'<script>\n(/\* =+\n   接片室.*?)\n</script>', s, re.S)
open('app.js','w',encoding='utf-8').write(m.group(1))
"
cat harness.js app.js cases.js > runner.js
node runner.js 2>&1 | grep -vE "畫不出來|WARNING|Mediabunny was loaded|CUDA|VAAPI|Vulkan|OpenCL|AMF|libcuda|garbage collected"
