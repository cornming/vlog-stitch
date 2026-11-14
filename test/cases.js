const realMB=require('mediabunny');mb.load=async()=>realMB;mb.supported=()=>true;
const fs3=require('fs'),{execSync:ex3}=require('child_process'),p3=require('path'),os3=require('os');
const D=__dirname,OUT=p3.join(os3.tmpdir(),'vlog-stitch-test.mp4');

/* ---------- 素材 ---------- */
function C(id,f,ts,te){const b=fs3.readFileSync(f);
  return{id,kind:'video',file:new File([b],p3.basename(f),{type:'video/mp4'}),name:p3.basename(f),url:'',
    dur:te,w:1080,h:1920,trimStart:ts,trimEnd:te,open:false,ready:true,taken:0,thumb:''};}
/* 照片走真正的 photo.probe()，解碼、尺寸、縮圖、拍攝時間都是程式自己算的 */
async function P(id,f,dur,taken){
  const b=fs3.readFileSync(f);
  const file=new File([b],p3.basename(f),{type:/png$/.test(f)?'image/png':'image/jpeg'});
  const c={id,kind:'photo',file,name:p3.basename(f),url:'',dur:0,w:0,h:0,trimStart:0,trimEnd:0,
    open:false,ready:false,taken:0,thumb:''};
  await photo.probe(c);
  if(dur){c.dur=c.trimEnd=dur;}
  if(taken!==undefined)c.taken=taken;
  return c;
}

/* ---------- 量測成品 ---------- */
function probe(f){
  const dur=+ex3(`ffprobe -v error -show_entries format=duration -of csv=p=0 "${f}"`,{encoding:'utf8'}).trim();
  const streams=ex3(`ffprobe -v error -show_entries stream=codec_name -of csv=p=0 "${f}"`,{encoding:'utf8'}).trim().split('\n').join('+');
  return {dur,streams};
}
/* t 秒起 len 秒的聲音：總音量，以及指定頻率的振幅（Goertzel） */
function listen(f,t,freqs,len){
  const raw=ex3(`ffmpeg -v error -ss ${t} -t ${len||.4} -i "${f}" -f f32le -ac 1 -ar 48000 -`,{maxBuffer:1<<27});
  const n=Math.floor(raw.length/4);
  const a=new Float32Array(raw.buffer.slice(raw.byteOffset,raw.byteOffset+n*4));
  let sq=0;for(let i=0;i<n;i++)sq+=a[i]*a[i];
  const out={rms:n?Math.sqrt(sq/n):0};
  for(const hz of (freqs||[])){
    const w=2*Math.PI*hz/48000,c=2*Math.cos(w);let s1=0,s2=0;
    for(let i=0;i<n;i++){const s0=a[i]+c*s1-s2;s2=s1;s1=s0;}
    out[hz]=n?2*Math.sqrt(Math.max(0,s1*s1+s2*s2-c*s1*s2))/n:0;
  }
  return out;
}
/* t 秒那一格的平均顏色；crop 是 ffmpeg 的裁切參數，例如 'iw:ih*0.2:0:ih*0.75' */
function color(f,t,crop){
  const vf=(crop?'crop='+crop+',':'')+'scale=1:1:flags=area';
  const raw=ex3(`ffmpeg -v error -ss ${t} -i "${f}" -frames:v 1 -vf "${vf}" -f rawvideo -pix_fmt rgb24 -`);
  return [raw[0],raw[1],raw[2]];
}

/* ---------- 跑一次匯出 ---------- */
const DEFAULTS={mode:'fast',fps:30,res:1080,ori:'auto',fit:'contain',photoDur:3,
  motion:'none',trans:'none',look:'none',bgm:'none',bgmVol:.5,origVol:1,duck:true};
let blob=null;ui.showResult=b=>{blob=b;};
let pass=0,fail=0;
function ok(cond,msg){
  if(cond){pass++;return;}
  fail++;console.log('    ✗ 不符合預期：'+msg);
}
const near=(a,b,tol)=>Math.abs(a-b)<=tol;

async function run(title,clips_,opts,subs_){
  blob=null;
  Object.assign(state,DEFAULTS,opts||{});
  state.clips=clips_;state.subs=subs_||[];state.subsOk=true;
  subs.relink();
  await xport.run();
  const r={ok:!!blob,dur:0,streams:'—',mode:'—',file:OUT,log:dbg.lines.join('\n')};
  if(r.ok){
    fs3.writeFileSync(OUT,Buffer.from(await blob.arrayBuffer()));
    Object.assign(r,probe(OUT));
  }
  const used=dbg.lines.filter(l=>/實際使用模式/.test(l))[0]||'';
  r.mode=used.replace(/^.*=== 實際使用模式：/,'').replace(' ===','')||'—';
  console.log(`${r.ok?'✓':'✗'} ${title}`);
  console.log(`    模式=${r.mode}  長度=${r.dur?r.dur.toFixed(3):'—'}  串流=${r.streams}`);
  if(!r.ok){
    fail++;
    console.log('    錯誤:',document.getElementById('expStatus').textContent);
    console.log(dbg.lines.slice(-8).map(l=>'      '+l).join('\n'));
  }else pass++;
  return r;
}

/* ---------- 不需要匯出的單元檢查 ---------- */
function unit(title,fn){
  const before=fail;
  try{fn();}catch(e){fail++;console.log('    ✗ 丟出例外：'+e.message);}
  console.log(`${fail===before?'✓':'✗'} ${title}`);
}

/* 手工組一個帶 EXIF 拍攝時間的 JPEG 檔頭 */
function exifJpeg(dateStr,le){
  const tiff=Buffer.alloc(8+2+12+4+2+12+4+20);
  const w16=(o,v)=>le?tiff.writeUInt16LE(v,o):tiff.writeUInt16BE(v,o);
  const w32=(o,v)=>le?tiff.writeUInt32LE(v,o):tiff.writeUInt32BE(v,o);
  tiff.write(le?'II':'MM',0,'latin1');w16(2,42);w32(4,8);
  w16(8,1);w16(10,0x8769);w16(12,4);w32(14,1);w32(18,26);w32(22,0);     // IFD0：指向 Exif 子目錄
  w16(26,1);w16(28,0x9003);w16(30,2);w32(32,20);w32(36,44);w32(40,0);  // Exif：DateTimeOriginal
  tiff.write(dateStr+'\0',44,'latin1');
  const app1=Buffer.concat([Buffer.from('Exif\0\0','latin1'),tiff]);
  const head=Buffer.from([0xFF,0xD8,0xFF,0xE1,(app1.length+2)>>8,(app1.length+2)&255]);
  return new File([Buffer.concat([head,app1,Buffer.from([0xFF,0xDA,0,2])])],'x.jpg',{type:'image/jpeg'});
}

(async()=>{
  const v3=()=>[C(1,D+'/clip1.mp4',0,3),C(2,D+'/clip2.mp4',0,4),C(3,D+'/clip3.mp4',0,2)];
  let r;

  console.log('── 接影片（原有功能）──');
  r=await run('快速接片・無裁切（預期 9.000）',v3(),{mode:'fast'});
  ok(r.mode==='快速接片'&&near(r.dur,9,.01),'應該用快速接片、長度 9 秒');

  r=await run('重新編碼・無裁切（預期 9.000）',v3(),{mode:'encode'});
  ok(r.mode==='重新編碼'&&near(r.dur,9,.01),'長度 9 秒');
  ok(/直接複製原始封包/.test(r.log),'規格一致又沒有配樂時，聲音應該直接複製封包');
  const base300=r.ok?listen(r.file,1.5,[300])[300]:0;

  r=await run('重新編碼・有裁切（預期 5.500）',
    [C(1,D+'/clip1.mp4',1,3),C(2,D+'/clip2.mp4',0,2),C(3,D+'/clip3.mp4',0.5,2)],{mode:'encode'});
  ok(near(r.dur,5.5,.02),'長度 5.5 秒');

  r=await run('快速接片・規格不一致 → 應自動改重新編碼（預期約 5.0）',
    [C(1,D+'/clip1.mp4',0,3),C(9,D+'/odd.mp4',0,2)],{mode:'fast'});
  ok(r.mode==='重新編碼'&&near(r.dur,5,.06),'應該改用重新編碼、長度約 5 秒');

  r=await run('單一片段（預期 3.000）',[C(1,D+'/clip1.mp4',0,3)],{mode:'fast'});
  ok(near(r.dur,3,.01),'長度 3 秒');

  r=await run('帶字幕的重新編碼（預期 9.000）',v3(),{mode:'encode'},
    [{id:1,start:0.5,end:2,text:'測試字幕',sel:false}]);
  ok(near(r.dur,9,.01),'長度 9 秒');

  r=await run('快速接片・全影片但開了效果 → 維持快速接片，效果不套用',v3(),
    {mode:'fast',trans:'fade',look:'warm',bgm:'sunny'});
  ok(r.mode==='快速接片'&&xport.skipFx===true,'應該維持快速接片並標記效果沒有套用');

  console.log('\n── 照片、效果與配樂 ──');
  const red=()=>P(11,D+'/p_red.jpg',2),green=()=>P(12,D+'/p_green.jpg',2),blue=()=>P(13,D+'/p_blue.png',2);

  r=await run('純照片 3 張＋交叉淡化＋內建配樂（預期 6.000）',[await red(),await green(),await blue()],
    {mode:'fast',res:720,trans:'fade',bgm:'sunny',motion:'auto',fit:'cover'});
  ok(r.mode==='重新編碼','有照片時快速接片應該自動改成重新編碼');
  ok(near(r.dur,6,.05),'長度 6 秒，實際 '+r.dur);
  ok(r.streams==='h264+aac','應該有影像和聲音，實際 '+r.streams);
  if(r.ok){
    const a=color(r.file,1.2),b=color(r.file,3.2),c=color(r.file,5.0),m=color(r.file,2.25);
    console.log(`    顏色 1.2s=${a}  2.25s=${m}  3.2s=${b}  5.0s=${c}`);
    ok(a[0]>140&&a[1]<90&&a[2]<90,'1.2 秒應該是紅色照片');
    ok(b[1]>120&&b[0]<90&&b[2]<100,'3.2 秒應該是綠色照片');
    ok(c[2]>140&&c[0]<90&&c[1]<100,'5.0 秒應該是藍色照片');
    ok(m[0]>50&&m[1]>50&&m[2]<90,'2.25 秒正在交叉淡化，紅和綠應該都看得到');
    const mid=listen(r.file,3,[]).rms,head=listen(r.file,0,[],.08).rms,tail=listen(r.file,5.9,[],.08).rms;
    console.log(`    配樂音量 開頭=${head.toFixed(4)}  中段=${mid.toFixed(4)}  結尾=${tail.toFixed(4)}`);
    ok(mid>.02,'中段應該聽得到配樂');
    ok(head<mid*.5&&tail<mid*.5,'片頭要淡入、片尾要淡出');
  }

  r=await run('照片＋影片交錯＋配樂（預期 11.000）',
    [await red(),C(1,D+'/clip1.mp4',0,3),await green(),C(2,D+'/clip2.mp4',0,4)],
    {mode:'encode',res:720,trans:'fade',bgm:'cozy'});
  ok(near(r.dur,11,.05),'長度 11 秒，實際 '+r.dur);
  ok(/聲音處理方式：混音/.test(r.log),'有照片和配樂時應該走混音');
  if(r.ok){
    const a=listen(r.file,3.5,[300,600]),b=listen(r.file,8.5,[300,600]),p=listen(r.file,1,[300,600]);
    console.log(`    3.5s 300Hz=${a[300].toFixed(3)}  8.5s 600Hz=${b[600].toFixed(3)}  照片段 1.0s 音量=${p.rms.toFixed(4)}`);
    ok(a[300]>.08&&a[600]<.03,'3.5 秒是第一段影片的 300Hz');
    ok(b[600]>.08&&b[300]<.03,'8.5 秒是第二段影片的 600Hz');
    ok(p.rms>.01&&p[300]<.03,'照片段只有配樂，沒有影片原音');
    /* 音畫同步：300Hz 應該剛好在 2.0 秒進來、5.0 秒離開 */
    const e1=listen(r.file,1.90,[300],.06)[300],e2=listen(r.file,2.04,[300],.06)[300];
    const e3=listen(r.file,4.90,[300],.06)[300],e4=listen(r.file,5.04,[300],.06)[300];
    console.log(`    300Hz 邊界 1.90s=${e1.toFixed(3)} 2.04s=${e2.toFixed(3)} 4.90s=${e3.toFixed(3)} 5.04s=${e4.toFixed(3)}`);
    ok(e1<.03&&e2>.08,'影片原音應該在 2.0 秒準時進來');
    ok(e3>.08&&e4<.03,'影片原音應該在 5.0 秒準時結束');
    /* 壓低配樂：影片段的配樂比照片段小聲。用 300／600Hz 以外的能量粗估不準，改看記錄 */
    ok(bgm.gainAt(3.5)<bgm.gainAt(1)*.5,'影片有原音的段落，配樂應該被壓低');
  }

  r=await run('照片＋影片、不加配樂（預期 5.000）',[await red(),C(1,D+'/clip1.mp4',0,3)],{mode:'encode',res:720});
  ok(near(r.dur,5,.05)&&r.streams==='h264+aac','長度 5 秒、有聲音軌');
  if(r.ok){
    const p=listen(r.file,1,[300]),a=listen(r.file,3.5,[300]);
    console.log(`    照片段音量=${p.rms.toFixed(5)}  影片段 300Hz=${a[300].toFixed(3)}`);
    ok(p.rms<.002,'照片段應該是靜音');
    ok(a[300]>.08,'影片段應該有原音');
  }

  r=await run('全部是照片、不加配樂 → 成品沒有聲音軌（預期 4.000）',[await red(),await blue()],{mode:'encode',res:720});
  ok(near(r.dur,4,.05)&&r.streams==='h264','長度 4 秒、只有影像，實際 '+r.streams);

  r=await run('取樣率不同的影片＋配樂（預期剛好 5.000）',
    [C(1,D+'/clip1.mp4',0,3),C(9,D+'/odd.mp4',0,2)],{mode:'encode',bgm:'chill',bgmVol:.3});
  ok(near(r.dur,5,.03),'混音模式會把每段聲音對齊影像長度，實際 '+r.dur);
  if(r.ok){
    const a=listen(r.file,1.5,[300,1200]),b=listen(r.file,4,[300,1200]);
    console.log(`    1.5s 300Hz=${a[300].toFixed(3)}  4.0s 1200Hz=${b[1200].toFixed(3)}`);
    ok(a[300]>.08&&a[1200]<.03,'1.5 秒是 300Hz');
    ok(b[1200]>.08&&b[300]<.03,'4.0 秒是 1200Hz（44.1kHz 單聲道那段，經過換取樣率）');
  }

  r=await run('影片原音調成 50%（預期 3.000）',[C(1,D+'/clip1.mp4',0,3)],{mode:'encode',origVol:.5});
  if(r.ok){
    const a=listen(r.file,1.5,[300])[300];
    console.log(`    300Hz 振幅 原本=${base300.toFixed(3)}  調整後=${a.toFixed(3)}`);
    ok(near(a/base300,.5,.08),'音量應該是原本的一半');
  }

  r=await run('色調：黑白（預期 2.000）',[await red()],{mode:'encode',res:720,look:'mono',fit:'cover'});
  if(r.ok){
    const a=color(r.file,1);
    console.log(`    顏色 1.0s=${a}`);
    ok(Math.abs(a[0]-a[1])<22&&Math.abs(a[1]-a[2])<22,'紅色照片套黑白之後應該接近灰色');
  }

  r=await run('模糊填滿：直式照片放進橫式畫面（預期 4.000）',[await red(),await green()],{mode:'encode',res:720,fit:'blur'});
  if(r.ok){
    const side=color(r.file,3,'iw*0.12:ih:0:0'),mid=color(r.file,3,'iw*0.2:ih*0.5:iw*0.4:ih*0.25');
    console.log(`    3.0s 邊緣=${side}  中央=${mid}`);
    ok(mid[1]>120,'中央是綠色照片本身');
    ok(side[1]>30&&side[1]<mid[1]-20,'兩側是壓暗的模糊背景，不是黑邊');
  }

  r=await run('字幕燒在照片上（預期 4.000）',[await P(21,D+'/p_blue.png',2),await P(22,D+'/p_blue.png',2)],
    {mode:'encode',res:720,fit:'cover'},
    [{id:1,start:0,end:0,text:'ABC 今天',sel:false,clip:21,span:'full',auto:'draft'}]);
  if(r.ok){
    const crop='iw*0.5:ih*0.14:iw*0.25:ih*0.80';
    const a=color(r.file,1,crop),b=color(r.file,3,crop);
    console.log(`    字幕區 有字=${a}  沒字=${b}`);
    ok(Math.abs(a[0]-b[0])+Math.abs(a[1]-b[1])+Math.abs(a[2]-b[2])>12,'有字幕的那張，字幕區的顏色應該不一樣');
  }

  console.log('\n── 字幕、草稿與其他邏輯 ──');
  const day=(h,m,d)=>new Date(2026,10,d||14,h,m).getTime();
  const A=await P(31,D+'/p_red.jpg',3,day(8,30)),B=C(32,D+'/clip1.mp4',0,3),Cc=await P(33,D+'/p_green.jpg',3,day(15,5));
  B.taken=day(12,10);

  unit('照片：尺寸、縮圖與預設秒數',()=>{
    ok(A.w===1600&&A.h===1200,'照片尺寸應該是 1600x1200，實際 '+A.w+'x'+A.h);
    ok(/^data:image\/jpeg/.test(A.thumb),'應該產生縮圖');
    ok(A.ready&&A.trimEnd===3,'照片長度 3 秒');
  });

  unit('離線草稿：照拍攝時間寫',()=>{
    Object.assign(state,DEFAULTS);state.clips=[A,B,Cc];state.subs=[];
    draft.apply(draft.offline());
    const t=state.subs.map(s=>s.text);
    ok(t[0]==='11月14日 週六'&&t[1]==='中午 12:10'&&t[2]==='下午 3:05','草稿文字不對：'+JSON.stringify(t));
    ok(state.subsOk===false,'產生草稿之後應該變成待確認');
    ok(near(state.subs[0].start,.15,.001)&&near(state.subs[0].end,2.85,.001),'照片草稿應該涵蓋整張照片');
    ok(near(state.subs[1].start,3.2,.001)&&near(state.subs[1].end,5.95,.001),'影片草稿放在那段影片的開頭');
  });

  unit('字幕跟著素材走：換順序、改長度、移除',()=>{
    const id3=state.subs[2].id;
    state.clips=[Cc,A,B];subs.relink();
    const s3=state.subs.find(s=>s.id===id3);
    ok(near(s3.start,.15,.001),'綠色照片移到最前面，它的字幕也要跟到最前面');
    Cc.dur=Cc.trimEnd=5;subs.relink();
    ok(near(s3.end,4.85,.001),'照片改成 5 秒，字幕也要跟著變長');
    ok(near(state.subs.find(s=>s.clip===31).start,5.15,.001),'後面的字幕要整個往後移');
    state.clips=[A,B];subs.relink();
    ok(!state.subs.some(s=>s.id===id3)&&state.subs.length===2,'素材移除後，它的字幕也要拿掉');
    Cc.dur=Cc.trimEnd=3;
  });

  unit('聽打字幕：裁切後時間跟著原始影片走，裁掉的不顯示',()=>{
    state.clips=[A,B];
    state.subs=[{id:901,start:0,end:0,text:'嗨',sel:false,clip:32,ms:1.0,me:2.0,auto:'asr'},
                {id:902,start:0,end:0,text:'掰',sel:false,clip:32,ms:2.5,me:2.9,auto:'asr'}];
    subs.relink();
    ok(near(state.subs[0].start,4,.001)&&near(state.subs[0].end,5,.001),'影片從 3 秒開始，字幕應該在 4–5 秒');
    B.trimStart=.5;B.trimEnd=2.2;subs.relink();
    ok(near(state.subs[0].start,3.5,.001),'起點裁掉 0.5 秒，字幕提早 0.5 秒');
    ok(state.subs[1].off===true&&subs.activeAt(state.subs[1].start).every(s=>s.id!==902),'落在裁切範圍外的字幕不會顯示');
    draft.apply(draft.offline());
    ok(!state.subs.some(s=>s.auto==='draft'&&s.clip===32),'已經聽打過的影片不再寫草稿');
    B.trimStart=0;B.trimEnd=3;
  });

  unit('依拍攝時間排序',()=>{
    const X=Object.assign({},A,{id:41,taken:0});
    state.clips=[Cc,X,B,A];state.subs=[];clips.sortByTime(false);
    ok(state.clips.map(c=>c.id).join()==='31,32,33,41','應該是 早上→中午→下午→沒有時間的，實際 '+state.clips.map(c=>c.id).join());
  });

  await (async()=>{
    const before=fail;
    const t1=await exifTime(exifJpeg('2026:11:14 08:30:15',true));
    const t2=await exifTime(exifJpeg('2025:01:02 23:59:01',false));
    ok(t1===new Date(2026,10,14,8,30,15).getTime(),'EXIF（little-endian）拍攝時間讀錯');
    ok(t2===new Date(2025,0,2,23,59,1).getTime(),'EXIF（big-endian）拍攝時間讀錯');
    ok(await exifTime(new File([Buffer.from([0xFF,0xD8,0xFF,0xDA,0,2])],'n.jpg',{type:'image/jpeg'}))===0,'沒有 EXIF 應該回 0');
    console.log(`${fail===before?'✓':'✗'} EXIF 拍攝時間`);
  })();

  unit('AI 字幕：請求內容與回應解析',()=>{
    state.aiModel='claude-opus-5-5';
    const items=[{n:1,label:'第 1 張｜照片',data:'AAAA'},{n:2,label:'第 2 張｜照片',data:'BBBB'}];
    const q=ai.request(items,[],'去宜蘭',2);
    ok(q.model==='claude-opus-5-5'&&q.thinking.type==='adaptive','模型與思考設定');
    ok(q.output_config.format.type==='json_schema'&&q.output_config.effort==='medium','要用結構化輸出');
    ok(q.fallbacks==='default'&&q.betas[0]==='server-side-fallback-2026-07-01','Opus 5.5 要帶拒絕備援');
    const imgs=q.messages[0].content.filter(b=>b.type==='image');
    ok(imgs.length===2&&imgs[0].source.media_type==='image/jpeg'&&imgs[0].source.data==='AAAA','每張畫面一個 image 區塊');
    ok(/去宜蘭/.test(q.messages[0].content[0].text),'補充說明要帶進去');
    state.aiModel='claude-haiku-5-5';
    ok(ai.request(items,[],'',2).fallbacks===undefined,'Haiku 5.5 沒有伺服器端備援，不能帶 fallbacks');
    state.aiModel='claude-opus-5-5';
    const got=ai.parse({stop_reason:'end_turn',content:[{type:'thinking',thinking:''},
      {type:'text',text:'{"captions":[{"index":1,"text":" 早餐吃蛋餅 "},{"index":2,"text":"出門囉"}]}'}]});
    ok(got.get(1)==='早餐吃蛋餅'&&got.get(2)==='出門囉','回應解析');
    let threw=false;try{ai.parse({stop_reason:'refusal',content:[]});}catch(e){threw=true;}
    ok(threw,'模型拒絕時要丟出錯誤，不能當成空字幕');
  });

  const throws=fn=>{try{fn();}catch(e){return e.message;}return '';};
  const two=[{n:1,label:'第 1 張｜照片',data:'AAAA'},{n:2,label:'第 2 張｜照片',data:'BBBB'}];

  unit('AI 字幕：預設供應商與缺少的設定',()=>{
    ok(state.aiProvider==='gemini','預設供應商應該是 Gemini，實際 '+state.aiProvider);
    ai.keys={gemini:'',azure:'',anthropic:''};
    ok(/Gemini 的金鑰/.test(ai.missing()),'Gemini 沒填金鑰要說得出來：'+ai.missing());
    ai.keys.gemini='g-key';
    ok(ai.missing()===''&&ai.key()==='g-key','Gemini 填了金鑰就齊了');
    state.aiProvider='azure';state.azureEndpoint='';state.azureDeployment='';
    ok(/端點/.test(ai.missing()),'Azure 要先填端點');
    state.azureEndpoint='https://res.services.ai.azure.com';
    ok(/部署名稱/.test(ai.missing()),'Azure 要填部署名稱');
    state.azureDeployment='my-gpt';
    ok(/Azure Foundry 的金鑰/.test(ai.missing()),'Azure 要填金鑰（各家金鑰分開存）');
    ai.keys.azure='a-key';
    ok(ai.missing()===''&&ai.key()==='a-key','Azure 三項都填了就齊了');
    state.aiProvider='gemini';
  });

  unit('AI 字幕：Gemini 的請求與回應',()=>{
    state.geminiModel='models/gemini-3.8-flash';
    ok(ai.geminiUrl()==='https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent','網址：'+ai.geminiUrl());
    state.geminiModel='gemini-3.8-flash';
    const q=ai.geminiRequest(two,[{n:0,text:'前一句'}],'去宜蘭',2),parts=q.contents[0].parts;
    ok(q.system_instruction.parts[0].text===AI_SYSTEM,'系統提示放在 system_instruction');
    ok(parts.filter(p=>p.inline_data).length===2&&parts.find(p=>p.inline_data).inline_data.mime_type==='image/jpeg'
       &&parts.find(p=>p.inline_data).inline_data.data==='AAAA','每張畫面一個 inline_data');
    ok(/去宜蘭/.test(parts[0].text)&&/前一句/.test(parts[0].text),'補充說明和前面寫好的字幕要帶進去');
    ok(q.generationConfig.responseMimeType==='application/json'&&q.generationConfig.responseSchema.type==='OBJECT','要用結構化輸出');
    const got=ai.geminiParse({candidates:[{finishReason:'STOP',content:{role:'model',parts:[
      {thought:true,text:'（思考過程）'},{text:'{"captions":[{"index":1,"text":"早餐吃蛋餅"},'},{text:'{"index":2,"text":" 出門囉 "}]}'}]}}]});
    ok(got.get(1)==='早餐吃蛋餅'&&got.get(2)==='出門囉','回應解析（分段的文字要接起來，思考內容要略過）');
    ok(/擋下/.test(throws(()=>ai.geminiParse({promptFeedback:{blockReason:'SAFETY'}}))),'被擋下時要報錯');
    ok(/截斷/.test(throws(()=>ai.geminiParse({candidates:[{finishReason:'MAX_TOKENS',content:{parts:[{text:'{"cap'}]}}]}))),'被截斷時要報錯');
    ok(/沒有寫出字幕/.test(throws(()=>ai.geminiParse({candidates:[{finishReason:'SAFETY'}]}))),'沒有內容時要報錯，不能當成空字幕');
  });

  unit('AI 字幕：Azure Foundry 的端點、請求與回應',()=>{
    const want='https://res.services.ai.azure.com/openai/v1/chat/completions';
    for(const raw of ['https://res.services.ai.azure.com','https://res.services.ai.azure.com/','res.services.ai.azure.com',
        'https://res.services.ai.azure.com/api/projects/demo','https://res.services.ai.azure.com/openai/v1/','res'])
      ok(ai.azureUrl(raw)===want,'端點「'+raw+'」整理後應該是 '+want+'，實際 '+ai.azureUrl(raw));
    ok(ai.azureUrl('https://res.openai.azure.com/')==='https://res.openai.azure.com/openai/v1/chat/completions','openai.azure.com 的端點也接受');
    ok(/https/.test(throws(()=>ai.azureUrl('http://res.services.ai.azure.com'))),'不是 https 要擋下來');
    ok(/還沒填/.test(throws(()=>ai.azureUrl('  '))),'空的端點要擋下來');
    state.azureDeployment=' my-gpt ';
    const q=ai.azureRequest(two,[],'去宜蘭',2),user=q.messages[1].content;
    ok(q.model==='my-gpt','model 填部署名稱');
    ok(q.messages[0].role==='system'&&q.messages[0].content===AI_SYSTEM,'系統提示');
    ok(user.filter(p=>p.type==='image_url').length===2&&user.find(p=>p.type==='image_url').image_url.url==='data:image/jpeg;base64,AAAA','每張畫面一個 image_url（data URL）');
    ok(q.response_format.type==='json_schema'&&q.response_format.json_schema.strict===true
       &&q.response_format.json_schema.schema.additionalProperties===false,'要用嚴格的結構化輸出');
    const got=ai.azureParse({choices:[{finish_reason:'stop',message:{role:'assistant',content:'{"captions":[{"index":1,"text":"早餐吃蛋餅"},{"index":2,"text":"出門囉"}]}'}}]});
    ok(got.get(1)==='早餐吃蛋餅'&&got.get(2)==='出門囉','回應解析');
    ok(/內容篩選/.test(throws(()=>ai.azureParse({choices:[{finish_reason:'content_filter',message:{}}]}))),'被內容篩選擋下要報錯');
    ok(/拒絕/.test(throws(()=>ai.azureParse({choices:[{finish_reason:'stop',message:{refusal:'不行'}}]}))),'模型拒絕要報錯');
    ok(/截斷/.test(throws(()=>ai.azureParse({choices:[{finish_reason:'length',message:{content:'{'}}]}))),'被截斷要報錯');
  });

  unit('內建配樂：三首都算得出來、不爆音、長度是整數小節',()=>{
    for(const k of Object.keys(TRACKS)){
      const p=synth.render(k,48000);
      let peak=0,bad=0,sq=0;
      for(let i=0;i<p.n;i++){const v=p.L[i];if(!(v===v))bad++;if(Math.abs(v)>peak)peak=Math.abs(v);sq+=v*v;}
      const bars=p.n/48000/(240/TRACKS[k].bpm);
      console.log(`    ${TRACKS[k].label}：${(p.n/48000).toFixed(2)}s  峰值 ${peak.toFixed(2)}  平均音量 ${Math.sqrt(sq/p.n).toFixed(3)}`);
      ok(bad===0&&peak<=.801&&peak>.3,k+' 波形異常');
      ok(near(bars,8,.001),k+' 應該是 8 小節');
    }
  });

  unit('逐塊換取樣率：接縫連續、長度正確',()=>{
    const n=44100,x=new Float32Array(n);
    for(let i=0;i<n;i++)x[i]=Math.sin(2*Math.PI*440*i/44100);
    const rs=makeResampler(),sizes=[1024,1024,333,2048,4096,7],out=[];
    let pos=0,k=0;
    while(pos<n){
      const size=sizes[k++%sizes.length];
      for(const v of rs(x.subarray(pos,Math.min(n,pos+size)),44100,48000))out.push(v);
      pos+=size;
    }
    ok(Math.abs(out.length-48000)<=2,'一秒的 44.1kHz 應該變成約 48000 個樣本，實際 '+out.length);
    let err=0;
    for(let i=0;i<out.length;i++)err=Math.max(err,Math.abs(out[i]-Math.sin(2*Math.PI*440*i/48000)));
    ok(err<.01,'換完取樣率的波形應該還是同一個正弦波，最大誤差 '+err.toFixed(4));
  });

  console.log('\n通過 '+pass+' 項，失敗 '+fail+' 項');
  process.exit(fail?1:0);
})().catch(e=>{console.log('測試程式本身出錯：',e);process.exit(1);});
