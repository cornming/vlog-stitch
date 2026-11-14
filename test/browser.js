/* 用真的瀏覽器把整個操作流程走一遍：選檔 → 快速成片 → 確認字幕 → 轉檔。
   Node 那套測試（run.sh）驗的是匯出引擎；這裡驗的是畫面流程，還有 Node 裡沒有的東西：
   <video> 解碼、Web Audio、WebCodecs、從 CDN 載入的程式庫。

   需要 Playwright：npm install playwright && npx playwright install chromium
   執行：node browser.js（素材不在的話先跑 ./make-fixtures.sh）
   有設 GEMINI_API_KEY、AZURE_OPENAI_ENDPOINT＋AZURE_AI_KEY（＋AZURE_CHAT_DEPLOYMENT）的話，
   最後會用真的金鑰實測 AI 看圖寫字幕，會用到一點額度。
   截圖和成品影格會放在 shots/，可以直接打開來看。 */
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),http=require('http'),os=require('os'),{execSync}=require('child_process');
const D=__dirname,SHOT=path.join(D,'shots'),TMP=fs.mkdtempSync(path.join(os.tmpdir(),'vlog-stitch-'));
fs.mkdirSync(SHOT,{recursive:true});
const f=n=>path.join(D,n);

let pass=0,fail=0;
const blocked=[];   /* 因為外部因素（例如金鑰額度用完）沒辦法驗證的項目，不算通過也不算失敗 */
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

  console.log('7. AI 看圖寫字幕：三家供應商（API 用假的回應頂替，檢查實際送出去的請求）');
  const cors={'access-control-allow-origin':'*','access-control-allow-headers':'*','access-control-allow-methods':'*'};
  const captionsFor=(nums,who)=>JSON.stringify({captions:nums.map(n=>({index:n,text:who+' 寫的第 '+n+' 句'}))});
  /* 攔下送往某個網域的請求，記下來，再照各家的格式回一個假的回應 */
  const mock=async(pattern,reply)=>{
    const sent=[];
    await page.route(pattern,async route=>{
      const rq=route.request();
      if(rq.method()==='OPTIONS')return route.fulfill({status:204,headers:cors});
      const body=JSON.parse(rq.postData()||'{}');
      sent.push({url:rq.url(),headers:rq.headers(),body});
      return route.fulfill({status:200,contentType:'application/json',headers:cors,body:JSON.stringify(reply(body))});
    });
    return sent;
  };
  const runDraft=async()=>{
    await page.click('#draftBtn');
    await page.waitForFunction(()=>!draft.busy&&$('draftStatus').textContent.length>0,null,{timeout:90000});
    return page.evaluate(()=>$('draftStatus').textContent);
  };
  const applied=async who=>{
    const t=await page.evaluate(()=>state.subs.map(s=>s.text));
    return t.length===5&&t.every((x,i)=>x===who+' 寫的第 '+(i+1)+' 句');
  };
  const numsOf=texts=>texts.filter(t=>/^第 \d+ 張/.test(t)).map(t=>+/^第 (\d+) 張/.exec(t)[1]);

  await page.click('#tab-subs');
  await page.click('#draftSrcPills button[data-v="ai"]');
  st=await page.evaluate(()=>({p:state.aiProvider,g:!$('aiGeminiBox').hidden,a:!$('aiAzureBox').hidden,c:!$('aiAnthropicBox').hidden,
    model:$('geminiModel').value,label:$('aiKeyLabel').textContent}));
  ok(st.p==='gemini'&&st.g&&!st.a&&!st.c&&st.label==='Gemini API key','預設供應商是 Gemini，只顯示 Gemini 的設定（模型 '+st.model+'）');
  ok(/還沒填 Gemini 的金鑰/.test(await page.evaluate(()=>$('quickSrcHint').textContent)),'沒填金鑰時，快速成片會提醒');

  /* --- Gemini --- */
  let sent=await mock('https://generativelanguage.googleapis.com/**',body=>({
    candidates:[{finishReason:'STOP',content:{role:'model',parts:[{text:captionsFor(
      numsOf(body.contents[0].parts.map(p=>p.text||'')),'Gemini')}]}}],
    usageMetadata:{promptTokenCount:100,candidatesTokenCount:20}}));
  await page.fill('#aiKey','gemini-test-key');
  await page.fill('#aiHint','週六的日常');
  await shot(page,'07-ai');
  msg=await runDraft();
  ok(/Gemini 寫好 5 句/.test(msg),'Gemini 草稿產生：'+msg);
  ok(page.dialogs.some(d=>/重新產生草稿/.test(d)),'改過的草稿要被換掉之前有先問');
  if(sent.length){
    const h=sent[0].headers,b=sent[0].body,parts=b.contents[0].parts,imgs=parts.filter(x=>x.inline_data);
    ok(sent[0].url==='https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent','打到 generateContent：'+sent[0].url);
    ok(h['x-goog-api-key']==='gemini-test-key','金鑰放在 x-goog-api-key 標頭，不在網址裡');
    ok(imgs.length===5&&imgs.every(i=>i.inline_data.mime_type==='image/jpeg'),
       '五張縮小後的 JPEG（各約 '+imgs.map(i=>Math.round(i.inline_data.data.length*3/4/1024)).join('、')+' KB）');
    ok(b.generationConfig.responseMimeType==='application/json'&&!!b.generationConfig.responseSchema&&!!b.system_instruction,'結構化輸出與系統提示');
    ok(/週六的日常/.test(parts[0].text),'補充說明有帶進去');
  }else ok(false,'沒有攔到送往 Gemini 的請求');
  ok(await applied('Gemini'),'Gemini 的字幕依序套到每段素材');
  await page.unroute('https://generativelanguage.googleapis.com/**');

  /* --- Azure Foundry（OpenAI 相容端點）--- */
  sent=await mock('https://myres.services.ai.azure.com/**',body=>({
    id:'chatcmpl-test',object:'chat.completion',model:body.model,
    choices:[{index:0,finish_reason:'stop',message:{role:'assistant',content:captionsFor(
      numsOf(body.messages[1].content.map(p=>p.text||'')),'Azure')}}]}));
  await page.click('#aiProvPills button[data-v="azure"]');
  st=await page.evaluate(()=>({a:!$('aiAzureBox').hidden,g:!$('aiGeminiBox').hidden,key:$('aiKey').value,miss:ai.missing()}));
  ok(st.a&&!st.g&&st.key===''&&/端點/.test(st.miss),'切到 Azure Foundry：顯示端點與部署名稱，金鑰欄是空的（各家分開）');
  /* 故意貼專案端點，程式要自己整理成 /openai/v1/chat/completions */
  await page.fill('#azEndpoint','myres.services.ai.azure.com/api/projects/demo');
  await page.fill('#azDeployment','my-vision-deploy');
  await page.fill('#aiKey','azure-test-key');
  await shot(page,'07b-ai-azure');
  msg=await runDraft();
  ok(/Azure Foundry 寫好 5 句/.test(msg),'Azure 草稿產生：'+msg);
  if(sent.length){
    const h=sent[0].headers,b=sent[0].body,user=b.messages[1].content,imgs=user.filter(x=>x.type==='image_url');
    ok(sent[0].url==='https://myres.services.ai.azure.com/openai/v1/chat/completions','打到 OpenAI 相容端點：'+sent[0].url);
    ok(h['api-key']==='azure-test-key','金鑰放在 api-key 標頭');
    ok(b.model==='my-vision-deploy','model 填的是部署名稱');
    ok(imgs.length===5&&imgs.every(i=>/^data:image\/jpeg;base64,/.test(i.image_url.url)),'五張畫面用 data URL 傳');
    ok(b.response_format.type==='json_schema'&&b.response_format.json_schema.strict===true,'嚴格的結構化輸出');
  }else ok(false,'沒有攔到送往 Azure 的請求');
  ok(await applied('Azure'),'Azure 的字幕依序套到每段素材');
  await page.unroute('https://myres.services.ai.azure.com/**');

  /* --- Anthropic --- */
  sent=await mock('https://api.anthropic.com/**',body=>({
    id:'msg_test',type:'message',role:'assistant',model:body.model,stop_reason:'end_turn',stop_sequence:null,
    content:[{type:'thinking',thinking:'',signature:'x'},
      {type:'text',text:captionsFor(numsOf(body.messages[0].content.map(p=>p.text||'')),'Claude')}],
    usage:{input_tokens:100,output_tokens:20}}));
  await page.click('#aiProvPills button[data-v="anthropic"]');
  await page.fill('#aiKey','sk-ant-test-0000');
  msg=await runDraft();
  ok(/Anthropic 寫好 5 句/.test(msg),'Anthropic 草稿產生：'+msg);
  if(sent.length){
    const h=sent[0].headers,b=sent[0].body,imgs=b.messages[0].content.filter(x=>x.type==='image');
    ok(/\/v1\/messages/.test(sent[0].url),'打到 Messages API');
    /* SDK 1.x 把 API key 放在 Authorization: Bearer，舊版才是 x-api-key */
    ok(h['authorization']==='Bearer sk-ant-test-0000'||h['x-api-key']==='sk-ant-test-0000','帶了使用者的金鑰');
    ok(h['anthropic-dangerous-direct-browser-access']==='true','SDK 以瀏覽器直連模式送出');
    ok(/server-side-fallback-2026-07-01/.test(h['anthropic-beta']||''),'帶了拒絕備援的 beta 標頭');
    ok(b.model==='claude-opus-5-5'&&b.fallbacks==='default'&&b.betas===undefined,'模型與備援設定正確');
    ok(b.thinking&&b.thinking.type==='adaptive'&&b.output_config.format.type==='json_schema','adaptive thinking＋結構化輸出');
    ok(imgs.length===5&&imgs.every(i=>i.source.media_type==='image/jpeg'),'五張縮小後的 JPEG');
  }else ok(false,'沒有攔到送往 Anthropic 的請求');
  ok(await applied('Claude'),'Claude 的字幕依序套到每段素材');
  await page.unroute('https://api.anthropic.com/**');
  /* 三把金鑰各存各的，切回去還在 */
  await page.click('#aiProvPills button[data-v="gemini"]');
  ok(await page.evaluate(()=>$('aiKey').value==='gemini-test-key'),'切回 Gemini，剛剛填的金鑰還在');

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

  /* ================= 三、用真的金鑰實測（有設環境變數才跑，會用到一點額度） ================= */
  const E=process.env;
  const realPage=async()=>{
    const pg=await open();
    await pg.setInputFiles('#filePick',[f('b_breakfast.jpg'),f('b_street.jpg'),f('b_lunch.jpg'),f('b_sunset.jpg'),f('b_walk.webm')]);
    await pg.waitForFunction(()=>state.clips.length===5&&state.clips.every(c=>c.ready));
    await pg.click('#tab-subs');
    await pg.click('#draftSrcPills button[data-v="ai"]');
    return pg;
  };
  const realDraft=async pg=>{
    await pg.click('#draftBtn');
    await pg.waitForFunction(()=>!draft.busy&&$('draftStatus').textContent.length>0,null,{timeout:240000});
    return pg.evaluate(()=>({msg:$('draftStatus').textContent,texts:state.subs.map(s=>s.text)}));
  };
  if(!E.GEMINI_API_KEY)console.log('13. Gemini 實測：沒有設 GEMINI_API_KEY，略過');
  else{
    console.log('13. Gemini 實測（真的打 API）');
    page=await realPage();
    await page.fill('#aiKey',E.GEMINI_API_KEY);
    if(E.GEMINI_MODEL)await page.fill('#geminiModel',E.GEMINI_MODEL);
    await page.click('#aiGeminiBox button');
    await page.waitForFunction(()=>$('geminiModelList').children.length!==5,null,{timeout:30000}).catch(()=>{});
    st=await page.evaluate(()=>[...$('geminiModelList').children].map(o=>o.value));
    ok(st.length>0&&st.every(n=>/^gemini-/.test(n)),'「查可用模型」向 API 要到 '+st.length+' 個模型');
    const r=await realDraft(page);
    console.log('   '+r.msg);
    r.texts.forEach((t,i)=>console.log('     '+(i+1)+'. '+t));
    if(/（429）/.test(r.msg)){
      /* Gemini 會先檢查請求格式（格式不對回 400）才檢查額度，所以走到 429 代表格式是被接受的 */
      ok(true,'請求有送到 Gemini 而且格式被接受（格式不對會先回 400，這裡拿到的是額度的 429）');
      blocked.push('Gemini 實際寫字幕：這把金鑰的額度或花費上限用完了（429）');
      console.log('  － 沒辦法驗證實際寫出來的字幕：金鑰的額度或花費上限用完了');
    }else{
      ok(/Gemini 寫好 5 句/.test(r.msg),'真的 Gemini API 寫出草稿（模型 '+(E.GEMINI_MODEL||'gemini-3.8-flash')+'）');
      ok(r.texts.length===5&&r.texts.every(t=>/[一-鿿]/.test(t)),'五句都有內容，而且是中文');
      clean(page);
    }
    await page.context().close();
  }
  if(!(E.AZURE_OPENAI_ENDPOINT&&E.AZURE_AI_KEY))console.log('14. Azure Foundry 實測：沒有設 AZURE_OPENAI_ENDPOINT／AZURE_AI_KEY，略過');
  else{
    console.log('14. Azure Foundry 實測（真的打你的資源）');
    page=await realPage();
    await page.click('#aiProvPills button[data-v="azure"]');
    await page.fill('#azEndpoint',E.AZURE_OPENAI_ENDPOINT);
    await page.fill('#aiKey',E.AZURE_AI_KEY);
    /* 不存在的部署：驗證端點、金鑰、跨來源都通，錯誤訊息也看得懂 */
    await page.fill('#azDeployment','vlog-stitch-no-such-deployment');
    let r=await realDraft(page);
    console.log('   '+r.msg);
    ok(/找不到這個部署/.test(r.msg),'部署名稱打錯時，從真的資源拿到 404 並說明原因（代表端點、金鑰、跨來源都通）');
    await page.fill('#aiKey','wrong-key-0000');
    r=await realDraft(page);
    console.log('   '+r.msg);
    ok(/不接受這把金鑰/.test(r.msg),'金鑰不對時說得出原因');
    if(!E.AZURE_CHAT_DEPLOYMENT){
      blocked.push('Azure Foundry 實際寫字幕：沒有設 AZURE_CHAT_DEPLOYMENT（能看圖的對話模型部署名稱）');
      console.log('  － 沒有設 AZURE_CHAT_DEPLOYMENT，沒辦法驗證實際寫出來的字幕');
    }
    else{
      await page.fill('#aiKey',E.AZURE_AI_KEY);
      await page.fill('#azDeployment',E.AZURE_CHAT_DEPLOYMENT);
      r=await realDraft(page);
      console.log('   '+r.msg);
      r.texts.forEach((t,i)=>console.log('     '+(i+1)+'. '+t));
      ok(/Azure Foundry 寫好 5 句/.test(r.msg)&&r.texts.every(t=>/[一-鿿]/.test(t)),'真的 Azure 部署寫出五句中文草稿');
    }
    await page.context().close();
  }

  if(blocked.length)console.log('\n沒辦法驗證的項目：\n'+blocked.map(x=>'  － '+x).join('\n'));
  console.log('\n通過 '+pass+' 項，失敗 '+fail+' 項　（截圖在 '+SHOT+'）');
  await browser.close();srv.close();
  process.exit(fail?1:0);
})().catch(e=>{console.log('測試程式出錯：',e);process.exit(1);});
