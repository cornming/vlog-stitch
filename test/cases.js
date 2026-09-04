const realMB=require('mediabunny');mb.load=async()=>realMB;mb.supported=()=>true;
const fs3=require('fs'),{execSync:ex3}=require('child_process'),p3=require('path');
function C(id,f,ts,te){const b=fs3.readFileSync(f);
  return{id,file:new File([b],p3.basename(f),{type:'video/mp4'}),name:p3.basename(f),url:'',
    dur:te,w:1080,h:1920,trimStart:ts,trimEnd:te,open:false,ready:true};}
let blob=null;ui.showResult=b=>{blob=b;};
const D=__dirname;
async function run(title,clips,mode,subs){
  blob=null;state.clips=clips;state.mode=mode;state.fps=30;state.res=1080;
  state.subs=subs||[];
  await xport.run();
  const ok=!!blob;
  let dur='—',streams='—';
  if(ok){
    fs3.writeFileSync('/tmp/o.mp4',Buffer.from(await blob.arrayBuffer()));
    dur=ex3('ffprobe -v error -show_entries format=duration -of csv=p=0 /tmp/o.mp4',{encoding:'utf8'}).trim();
    streams=ex3('ffprobe -v error -show_entries stream=codec_name -of csv=p=0 /tmp/o.mp4',{encoding:'utf8'}).trim().split('\n').join('+');
  }
  const used=dbg.lines.filter(l=>/實際使用模式/.test(l))[0]||'';
  console.log(`${ok?'✓':'✗'} ${title}`);
  console.log(`    模式=${used.replace(/^.*=== 實際使用模式：/,'').replace(' ===','')||'—'}  長度=${dur}  串流=${streams}`);
  if(!ok)console.log('    錯誤:',document.getElementById('expStatus').textContent);
  return ok;
}
(async()=>{
  const R=[];
  R.push(await run('快速接片・無裁切（預期 9.000）',
    [C(1,D+'/clip1.mp4',0,3),C(2,D+'/clip2.mp4',0,4),C(3,D+'/clip3.mp4',0,2)],'fast'));
  R.push(await run('重新編碼・無裁切（預期 9.000）',
    [C(1,D+'/clip1.mp4',0,3),C(2,D+'/clip2.mp4',0,4),C(3,D+'/clip3.mp4',0,2)],'encode'));
  R.push(await run('重新編碼・有裁切（預期 5.500）',
    [C(1,D+'/clip1.mp4',1,3),C(2,D+'/clip2.mp4',0,2),C(3,D+'/clip3.mp4',0.5,2)],'encode'));
  R.push(await run('快速接片・規格不一致 → 應自動改重新編碼（預期 5.000）',
    [C(1,D+'/clip1.mp4',0,3),C(9,D+'/odd.mp4',0,2)],'fast'));
  R.push(await run('單一片段（預期 3.000）',[C(1,D+'/clip1.mp4',0,3)],'fast'));
  R.push(await run('帶字幕的重新編碼（預期 9.000）',
    [C(1,D+'/clip1.mp4',0,3),C(2,D+'/clip2.mp4',0,4),C(3,D+'/clip3.mp4',0,2)],'encode',
    [{id:1,start:0.5,end:2,text:'測試字幕',sel:false}]));
  console.log('\n通過 '+R.filter(Boolean).length+' / '+R.length);
})();
