/* 用真的瀏覽器把整個操作流程走一遍：選檔 → 快速成片 → 確認字幕 → 轉檔。
   Node 那套測試（run.sh）驗的是匯出引擎；這裡驗的是畫面流程，還有 Node 裡沒有的東西：
   <video> 解碼、Web Audio、WebCodecs、從 CDN 載入的程式庫。

   需要 Playwright：npm install playwright && npx playwright install chromium
   執行：node browser.js（素材不在的話先跑 ./make-fixtures.sh）
   截圖和成品影格會放在 shots/，可以直接打開來看。 */
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),http=require('http'),os=require('os'),{execSync}=require('child_process');
const D=__dirname,SHOT=path.join(D,'shots'),TMP=fs.mkdtempSync(path.join(os.tmpdir(),'vlog-stitch-'));
fs.mkdirSync(SHOT,{recursive:true});
const f=n=>path.join(D,n);

let pass=0,fail=0;
const ok=(c,msg)=>{if(c){pass++;console.log('  ✓ '+msg);}else{fail++;console.log('  ✗ '+msg);}};

/* ---------- 量測成品 ---------- */
const probe=file=>({
  dur:+execSync(`ffprobe -v error -show_entries format=duration -of csv=p=0 "${file}"`,{encoding:'utf8'}).trim(),
  streams:execSync(`ffprobe -v error -show_entries stream=codec_name -of csv=p=0 "${file}"`,{encoding:'utf8'}).trim().split('\n').join('+')
});
/* t 秒起一小段聲音的總音量，以及指定頻率的振幅 */
function listen(file,t,freqs,len){
  const raw=execSync(`ffmpeg -v error -ss ${t} -t ${len||.4} -i "${file}" -f f32le -ac 1 -ar 48000 -`,{maxBuffer:1<<27});
  const n=Math.floor(raw.length/4),a=new Float32Array(raw.buffer.slice(raw.byteOffset,raw.byteOffset+n*4));
  let sq=0;for(let i=0;i<n;i++)sq+=a[i]*a[i];
  const out={rms:n?Math.sqrt(sq/n):0};
  for(const hz of freqs||[]){
    const w=2*Math.PI*hz/48000,c=2*Math.cos(w);let s1=0,s2=0;
    for(let i=0;i<n;i++){const s0=a[i]+c*s1-s2;s2=s1;s1=s0;}
    out[hz]=n?2*Math.sqrt(Math.max(0,s1*s1+s2*s2-c*s1*s2))/n:0;
  }
  return out;
}
const frame=(file,t,name)=>execSync(`ffmpeg -y -v error -ss ${t} -i "${file}" -frames:v 1 "${path.join(SHOT,name)}"`);

/* ---------- 只服務 index.html 的小伺服器（WebCodecs 要 localhost 或 HTTPS 才會開） ---------- */
function serve(){
  return new Promise(res=>{
    const srv=http.createServer((rq,rs)=>{
      if(rq.url.split('?')[0]!=='/index.html'){rs.writeHead(404);rs.end();return;}
      rs.writeHead(200,{'content-type':'text/html; charset=utf-8'});
      rs.end(fs.readFileSync(path.join(D,'..','index.html')));
    }).listen(0,'127.0.0.1',()=>res(srv));
  });
}

(async()=>{
  for(const n of ['b_breakfast.jpg','b_street.jpg','b_lunch.jpg','b_sunset.jpg','b_rot.jpg','b_walk.webm','b_song.mp3','b_fake.heic','clip1.mp4'])
    if(!fs.existsSync(f(n))){console.log('缺少測試素材 '+n+'，先跑 ./make-fixtures.sh');process.exit(1);}
  /* 影片沒有 EXIF，排序看的是檔案時間，這裡固定成當天晚上 */
  {const t=new Date('2026-11-14T19:05:00+08:00');fs.utimesSync(f('b_walk.webm'),t,t);}

  const srv=await serve(),URL_='http://localhost:'+srv.address().port+'/index.html';
  const browser=await chromium.launch({args:['--autoplay-policy=no-user-gesture-required']});
  const open=async()=>{
    const ctx=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:2,
      locale:'zh-TW',timezoneId:'Asia/Taipei',isMobile:true,hasTouch:true});
    const page=await ctx.newPage();
    page.errs=[];page.dialogs=[];
    page.on('console',m=>{if(m.type()==='error')page.errs.push(m.text());});
    page.on('pageerror',e=>page.errs.push('未處理的例外：'+e.message));
    page.on('dialog',d=>{page.dialogs.push(d.message());d.accept();});
    await page.goto(URL_);
    return page;
  };
  const shot=(page,n)=>page.screenshot({path:path.join(SHOT,n+'.png')});
  const save=async(page,name)=>{
    const b64=await page.evaluate(async()=>{
      const a=document.querySelector('#result a[download]');
      const buf=new Uint8Array(await (await fetch(a.href)).arrayBuffer());
      let s='';for(let i=0;i<buf.length;i+=32768)s+=String.fromCharCode.apply(null,buf.subarray(i,i+32768));
      return btoa(s);
    });
    const out=path.join(TMP,name);fs.writeFileSync(out,Buffer.from(b64,'base64'));return out;
  };
  const waitExport=async page=>{
    await page.waitForFunction(()=>!xport.busy&&!engine.running&&/完成|失敗|出問題|已取消/.test($('expStatus').textContent),null,{timeout:240000});
    return page.evaluate(()=>$('expStatus').textContent);
  };
  const toasts=page=>page.evaluate(()=>[...document.body.children].filter(e=>e.style&&e.style.zIndex==='99').map(e=>e.textContent));
  const clean=page=>ok(!page.errs.length,'頁面沒有錯誤'+(page.errs.length?'：'+page.errs.join(' / '):''));
  let page,st,msg;

  /* ================= 一、主要流程 ================= */
  console.log('1. 選照片和影片（故意不照時間順序）');
  page=await open();
  await shot(page,'01-empty');
  ok(await page.evaluate(()=>mb.supported()),'瀏覽器有 WebCodecs');
  ok(await page.evaluate(()=>$('clipTools').offsetParent===null&&$('quickCard').offsetParent===null),'還沒有素材時，工具列和快速成片都不顯示');
  await page.setInputFiles('#filePick',[f('b_sunset.jpg'),f('b_breakfast.jpg'),f('b_walk.webm'),f('b_lunch.jpg'),f('b_street.jpg')]);
  await page.waitForFunction(()=>state.clips.length===5&&state.clips.every(c=>c.ready||c.err),null,{timeout:60000});
  await page.waitForFunction(()=>state.clips.every(c=>c.thumb),null,{timeout:20000}).catch(()=>{});
  st=await page.evaluate(()=>({errs:state.clips.map(c=>c.err||''),dims:state.clips.map(c=>c.w+'x'+c.h),
    thumbs:state.clips.filter(c=>c.thumb).length,motion:state.motion,trans:state.trans,bgm:state.bgm,
    fit:state.fit,mode:state.mode,total:totalLen()}));
  ok(st.errs.every(e=>!e),'五個素材都讀得進來 '+JSON.stringify(st.dims));
  ok(st.thumbs===5,'每個素材都有縮圖');
  ok(st.motion==='auto'&&st.trans==='fade'&&st.bgm==='sunny'&&st.fit==='blur'&&st.mode==='encode',
     '加入照片後自動套用照片動態、轉場、配樂、模糊填滿，並改成重新編碼');
  ok(Math.abs(st.total-15)<.2,'照片每張 3 秒＋影片 3 秒，總長 15 秒');
  await page.waitForTimeout(2800);
  await shot(page,'02-clips');

  console.log('2. 快速成片：排序、寫草稿，然後停在字幕確認');
  await page.click('#quickBtn');
  await page.waitForSelector('#review:not([hidden])',{timeout:20000});
  st=await page.evaluate(()=>({names:state.clips.map(c=>c.name),texts:state.subs.map(s=>s.text),okFlag:state.subsOk,busy:xport.busy}));
  ok(st.names.join()==='b_breakfast.jpg,b_street.jpg,b_lunch.jpg,b_sunset.jpg,b_walk.webm','依拍攝時間排好順序：'+st.names.join(' → '));
  ok(st.texts.join('|')==='11月14日 週六|早上 10:47|中午 12:31|傍晚 5:20|晚上 7:05','離線草稿照拍攝時間寫：'+JSON.stringify(st.texts));
  ok(st.okFlag===false&&st.busy===false,'字幕待確認，還沒有開始轉檔');
  await shot(page,'03-review');

  console.log('3. 在確認畫面改字幕，按確認之後才開始轉檔');
  const lines=['早安，今天從早餐開始','出門散步去','午餐吃這個','看到很美的夕陽'];
  for(let i=0;i<lines.length;i++)await page.fill('#reviewList textarea >> nth='+i,lines[i]);
  await page.click('#reviewGo');
  await page.waitForFunction(()=>xport.busy,null,{timeout:10000});
  ok(true,'按下確認之後開始轉檔');
  msg=await waitExport(page);
  console.log('   '+msg);
  ok(/^完成/.test(msg),'轉檔完成');
  await shot(page,'04-done');
  if(/^完成/.test(msg)){
    const out=await save(page,'quick.mp4'),p=probe(out);
    ok(Math.abs(p.dur-15)<.25,'成品 15 秒（'+p.dur.toFixed(2)+'s，'+p.streams+'）');
    ok(/\+/.test(p.streams),'有影像也有聲音');
    const a=listen(out,13.5,[440]),b=listen(out,4,[440]);
    ok(a[440]>.05,'影片段聽得到原音（440Hz 振幅 '+a[440].toFixed(3)+'）');
    ok(b.rms>.01&&b[440]<.02,'照片段有配樂、沒有影片原音（音量 '+b.rms.toFixed(3)+'）');
    [1.5,3.25,7.5,10.5,13.5].forEach((t,i)=>frame(out,t,'frame-'+(i+1)+'.jpg'));
  }

  console.log('4. 確認過、字幕沒再動 → 再按匯出不會再問一次');
  await page.click('#dock button.primary');
  await page.waitForTimeout(600);
  ok(await page.evaluate(()=>$('review').hidden&&xport.busy),'直接開始轉檔');
  ok(/^完成/.test(await waitExport(page)),'第二次轉檔完成');

  console.log('5. 改了字幕 → 匯出會先停在確認畫面；按「回去修改」不會轉檔');
  await page.click('#tab-subs');
  await page.fill('#subList .txt >> nth=1','出門散步，天氣很好');
  ok(await page.evaluate(()=>state.subsOk===false&&$('subState').textContent==='待確認'),'字幕狀態變回待確認');
  await shot(page,'05-subs');
  await page.click('#tab-export');
  await page.click('#dock button.primary');
  await page.waitForSelector('#review:not([hidden])',{timeout:5000});
  ok(await page.evaluate(()=>!xport.busy),'確認畫面出現，轉檔沒有開始');
  await page.click('#review .sheet-foot .btn >> nth=0');
  await page.waitForTimeout(400);
  ok(await page.evaluate(()=>$('review').hidden&&!xport.busy&&state.subsOk===false),'回去修改：沒有轉檔，仍是待確認');

  console.log('6. 效果頁：換色調、轉場，改用自己的音樂');
  await page.click('#tab-fx');
  await page.click('#lookPills button[data-v="film"]');
  await page.click('#transPills button[data-v="slide"]');
  await page.waitForTimeout(600);
  await shot(page,'06-fx');
  await page.setInputFiles('#bgmPick',f('b_song.mp3'));
  await page.waitForFunction(()=>state.bgm==='file'&&!!bgm.fileBuf,null,{timeout:15000});
  ok(true,'自己的音樂檔解碼成功');
  await page.click('#tab-export');
  await page.click('#dock button.primary');
  await page.waitForSelector('#review:not([hidden])');
  await page.click('#reviewGo');
  msg=await waitExport(page);
  ok(/^完成/.test(msg),'換成自己的音樂、滑動轉場、底片色調後轉檔完成');
  if(/^完成/.test(msg)){
    const out=await save(page,'ownmusic.mp4'),a=listen(out,4,[880]);
    ok(a[880]>.03,'照片段聽得到自己的音樂（880Hz 振幅 '+a[880].toFixed(3)+'）');
    frame(out,3.2,'frame-slide.jpg');
  }

  console.log('7. AI 看圖寫字幕（API 用假的回應頂替，檢查實際送出去的請求）');
  const sent=[];
  const cors={'access-control-allow-origin':'*','access-control-allow-headers':'*','access-control-allow-methods':'*'};
  await page.route('https://api.anthropic.com/**',async route=>{
    const rq=route.request();
    if(rq.method()==='OPTIONS')return route.fulfill({status:204,headers:cors});
    const body=JSON.parse(rq.postData()||'{}');
    sent.push({url:rq.url(),headers:rq.headers(),body});
    const nums=body.messages[0].content.filter(b=>b.type==='text'&&/^第 \d+ 張/.test(b.text)).map(b=>+/^第 (\d+) 張/.exec(b.text)[1]);
    return route.fulfill({status:200,contentType:'application/json',headers:cors,body:JSON.stringify({
      id:'msg_test',type:'message',role:'assistant',model:body.model,stop_reason:'end_turn',stop_sequence:null,
      content:[{type:'thinking',thinking:'',signature:'x'},
        {type:'text',text:JSON.stringify({captions:nums.map(n=>({index:n,text:'AI 寫的第 '+n+' 句'}))})}],
      usage:{input_tokens:100,output_tokens:20}})});
  });
  await page.click('#tab-subs');
  await page.click('#draftSrcPills button[data-v="ai"]');
  await page.fill('#aiKey','sk-ant-test-0000');
  await page.fill('#aiHint','週六的日常');
  await shot(page,'07-ai');
  await page.click('#draftBtn');
  await page.waitForFunction(()=>!draft.busy&&$('draftStatus').textContent.length>0,null,{timeout:90000});
  msg=await page.evaluate(()=>$('draftStatus').textContent);
  ok(/AI 寫好 5 句/.test(msg),'AI 草稿產生：'+msg);
  ok(page.dialogs.some(d=>/重新產生草稿/.test(d)),'改過的草稿要被換掉之前有先問');
  if(sent.length){
    const h=sent[0].headers,b=sent[0].body,imgs=b.messages[0].content.filter(x=>x.type==='image');
    ok(/\/v1\/messages/.test(sent[0].url),'打到 Messages API');
    /* SDK 1.x 把 API key 放在 Authorization: Bearer，舊版才是 x-api-key */
    ok(h['authorization']==='Bearer sk-ant-test-0000'||h['x-api-key']==='sk-ant-test-0000','帶了使用者的金鑰');
    ok(h['anthropic-dangerous-direct-browser-access']==='true','SDK 以瀏覽器直連模式送出');
    ok(/server-side-fallback-2026-07-01/.test(h['anthropic-beta']||''),'帶了拒絕備援的 beta 標頭');
    ok(b.model==='claude-opus-5-5'&&b.fallbacks==='default'&&b.betas===undefined,'模型與備援設定正確');
    ok(b.thinking&&b.thinking.type==='adaptive'&&b.output_config.format.type==='json_schema','adaptive thinking＋結構化輸出');
    ok(imgs.length===5&&imgs.every(i=>i.source.media_type==='image/jpeg'),
       '五張縮小後的 JPEG（各約 '+imgs.map(i=>Math.round(i.source.data.length*3/4/1024)).join('、')+' KB）');
    ok(/週六的日常/.test(b.messages[0].content[0].text),'補充說明有帶進去');
  }else ok(false,'沒有攔到送往 API 的請求');
  st=await page.evaluate(()=>state.subs.map(s=>s.text));
  ok(st.length===5&&st.every((t,i)=>t==='AI 寫的第 '+(i+1)+' 句'),'AI 字幕依序套到每段素材');
  await page.unroute('https://api.anthropic.com/**');

  console.log('8. 預覽與即時錄製（縮成兩張照片比較快）');
  await page.evaluate(()=>{while(state.clips.length>2)clips.remove(state.clips[state.clips.length-1].id);
    state.clips.forEach(c=>clips.setDur(c.id,2));state.bgm='chill';ui.syncPills();});
  await page.click('#tab-export');
  await page.click('#dock button >> nth=0');
  await page.waitForTimeout(1300);
  await shot(page,'08-preview');
  await page.waitForFunction(()=>!engine.running,null,{timeout:30000});
  ok(/預覽播完了/.test(await page.evaluate(()=>$('expStatus').textContent)),'預覽可以播完');
  await page.click('#modePills button[data-v="record"]');
  await page.click('#dock button.primary');
  await page.waitForSelector('#review:not([hidden])');
  await page.click('#reviewGo');
  ok(/^完成/.test(await waitExport(page)),'即時錄製模式完成');
  clean(page);
  await page.context().close();

  /* ================= 二、邊界情況 ================= */
  console.log('9. 有旋轉資訊的照片、瀏覽器解不開的檔案');
  page=await open();
  await page.setInputFiles('#filePick',[f('b_rot.jpg'),f('b_fake.heic')]);
  await page.waitForFunction(()=>state.clips.length===2&&state.clips.every(c=>c.ready||c.err),null,{timeout:30000});
  st=await page.evaluate(()=>state.clips.map(c=>({w:c.w,h:c.h,err:c.err||'',taken:ui.takenText(c)})));
  ok(st[0].w===1200&&st[0].h===1600,'EXIF 標示轉 90 度的 1600x1200 照片，以直式 1200x1600 處理');
  ok(st[0].taken==='2026/11/13 21:40','拍攝時間讀得到（'+st[0].taken+'）');
  ok(/打不開/.test(st[1].err),'解不開的檔案會標示出來');
  await page.click('#tab-export');
  await page.click('#dock button.primary');
  await page.waitForTimeout(300);
  ok((await toasts(page)).some(t=>/先把它移除/.test(t))&&!(await page.evaluate(()=>xport.busy)),'有讀不到的素材時不會開始轉檔，並提示要先移除');
  await page.evaluate(()=>clips.remove(state.clips[1].id));
  ok(await page.evaluate(()=>state.clips.length===1&&state.subs.length===0&&$('review').hidden),'移除之後恢復正常');

  console.log('10. 有照片但沒有字幕 → 匯出前問一次要不要自動寫草稿');
  await page.setInputFiles('#filePick',[f('b_breakfast.jpg')]);
  await page.waitForFunction(()=>state.clips.length===2&&state.clips.every(c=>c.ready));
  ok(await page.evaluate(()=>!$('photoDurBox').hidden),'有照片時顯示「照片每張幾秒」');
  await page.click('#dock button.primary');
  await page.waitForSelector('#review:not([hidden])',{timeout:10000});
  ok(page.dialogs.some(d=>/還沒有字幕/.test(d)),'有先問要不要自動寫草稿');
  ok(await page.evaluate(()=>state.subs.length===2&&!xport.busy),'草稿寫好後停在確認畫面');
  await page.click('#reviewGo');
  msg=await waitExport(page);
  ok(/^完成/.test(msg),'確認後轉檔完成');
  if(/^完成/.test(msg)){
    const out=await save(page,'blur.mp4');
    ok(Math.abs(probe(out).dur-6)<.2,'兩張照片共 6 秒');
    frame(out,4.5,'frame-blur.jpg');
  }
  clean(page);
  await page.context().close();

  console.log('11. 只有影片：預設仍是快速接片，不會被照片功能影響');
  page=await open();
  await page.setInputFiles('#filePick',[f('clip1.mp4'),f('clip1.mp4')]);
  await page.waitForFunction(()=>state.clips.length===2&&state.clips.every(c=>c.ready||c.err),null,{timeout:30000});
  st=await page.evaluate(()=>({mode:state.mode,trans:state.trans,bgm:state.bgm,fit:state.fit,
    errs:state.clips.map(c=>c.err||''),photoDur:!$('photoDurBox').hidden}));
  if(st.errs.some(Boolean))console.log('   這個瀏覽器解不開 H.264，略過這一段');
  else{
    ok(st.mode==='fast'&&st.trans==='none'&&st.bgm==='none'&&st.fit==='contain','不會自動套效果，匯出方式維持快速接片');
    ok(!st.photoDur,'沒有照片時不顯示照片秒數');
    await page.click('#tab-fx');
    await page.click('#transPills button[data-v="fade"]');
    ok(await page.evaluate(()=>!$('fxNote').hidden),'效果頁提醒：快速接片不會套用效果');
    await page.click('#tab-export');
    await page.click('#dock button.primary');
    msg=await waitExport(page);
    ok(/^完成/.test(msg)&&await page.evaluate(()=>xport.ranMode==='fast'),'快速接片完成');
    ok(/效果和配樂沒有套用/.test(msg),'完成訊息有說明效果沒有套用');
    if(/^完成/.test(msg)){const p=probe(await save(page,'fast.mp4'));ok(Math.abs(p.dur-6)<.1&&p.streams==='h264+aac','成品 6 秒，原封不動的 h264+aac');}
    await page.click('#tab-fx');
    await page.click('#fxNote button');
    await page.click('#bgmPills button[data-v="cozy"]');
    await page.click('#tab-export');
    await page.click('#dock button.primary');
    msg=await waitExport(page);
    ok(/^完成/.test(msg)&&await page.evaluate(()=>xport.ranMode==='encode'),'改成重新編碼後，轉場和配樂套得上去');
  }
  clean(page);
  await page.context().close();

  if(process.env.SKIP_ASR)console.log('12. 影片聽打：已用 SKIP_ASR 略過');
  else{
    console.log('12. 影片聽打（實際下載 Whisper tiny 跑一次，約 40MB）');
    page=await open();
    await page.setInputFiles('#filePick',[f('b_breakfast.jpg'),f('b_walk.webm')]);
    await page.waitForFunction(()=>state.clips.length===2&&state.clips.every(c=>c.ready));
    await page.click('#tab-subs');
    await page.click('#draftBtn');
    await page.waitForFunction(()=>state.subs.length===2);
    await page.selectOption('#asrModel','Xenova/whisper-tiny');
    await page.click('#asrBtn');
    let done=true;
    await page.waitForFunction(()=>!asr.busy&&$('asrStatus').textContent.length>0,null,{timeout:300000}).catch(()=>{done=false;});
    msg=await page.evaluate(()=>$('asrStatus').textContent);
    /* 素材的聲音只是一個單音，辨識到什麼不重要，重點是流程跑得完 */
    ok(done&&/辨識完成|沒有辨識到內容/.test(msg),'聽打流程跑得完：'+msg);
    ok(await page.evaluate(()=>state.subs.some(s=>s.auto==='draft'&&s.text==='11月14日 週六')),'照片的草稿沒有被聽打蓋掉');
    ok(!page.errs.some(e=>/未處理的例外/.test(e)),'頁面沒有未處理的例外');
    await page.context().close();
  }

  console.log('\n通過 '+pass+' 項，失敗 '+fail+' 項　（截圖在 '+SHOT+'）');
  await browser.close();srv.close();
  process.exit(fail?1:0);
})().catch(e=>{console.log('測試程式出錯：',e);process.exit(1);});
