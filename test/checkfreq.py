import subprocess,sys,numpy as np
f=sys.argv[1]
print('檢查',f)
# 影格數
n=subprocess.run(['ffprobe','-v','error','-count_frames','-select_streams','v:0',
    '-show_entries','stream=nb_read_frames','-of','csv=p=0',f],capture_output=True,text=True).stdout.strip()
print('  影像總格數:',n)
# 每個時間點取 0.5 秒聲音，算主頻
for label,t in sys.argv[2:] and [] or [('第1段中間',1.5),('第2段中間',5.0),('第3段中間',8.0)]:
    raw=subprocess.run(['ffmpeg','-v','error','-ss',str(t),'-t','0.5','-i',f,
        '-f','f32le','-ac','1','-ar','48000','-'],capture_output=True).stdout
    a=np.frombuffer(raw,dtype=np.float32)
    if a.size<1000:
        print(f'  {label} (t={t}s): 沒有聲音資料'); continue
    w=np.abs(np.fft.rfft(a*np.hanning(a.size)))
    freq=np.fft.rfftfreq(a.size,1/48000)[np.argmax(w)]
    rms=float(np.sqrt(np.mean(a**2)))
    print(f'  {label} (t={t}s): 主頻 {freq:.0f}Hz  音量 {rms:.4f}')
