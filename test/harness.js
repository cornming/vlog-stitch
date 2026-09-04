/* 用真的 mp4 檔跑 index.html 裡真正的匯出程式碼 */
const fs=require('fs'),path=require('path'),{execSync}=require('child_process');

// ---- 極簡 DOM stub ----
const ctxStub=new Proxy({},{get(t,k){
  if(k==='measureText')return s=>({width:String(s).length*10});
  if(k==='createLinearGradient')return()=>({addColorStop(){}});
  if(k==='canvas')return{width:1080,height:1920};
  return()=>{};},set(){return true;}});
function mkEl(id){return{id,value:'',textContent:'',innerHTML:'',checked:false,hidden:false,disabled:false,
  dataset:{},children:[],classList:{add(){},remove(){}},style:new Proxy({},{get:()=>'',set:()=>true}),
  width:1080,height:1920,scrollHeight:20,getContext:()=>ctxStub,addEventListener(){},appendChild(){},
  remove(){},click(){},focus(){},load(){},pause(){},play(){return Promise.resolve();},
  setAttribute(){},getAttribute(){return null;},querySelectorAll(){return[];},querySelector(){return null;},
  scrollIntoView(){},closest(){return null;},captureStream(){return{getVideoTracks:()=>[{}],getAudioTracks:()=>[]};}};}
const store={};
const napiCanvas=require('@napi-rs/canvas').createCanvas(1080,1920);
napiCanvas.style={};  // 讓瀏覽器程式碼設定 style 不會爆
napiCanvas.setAttribute=()=>{};napiCanvas.addEventListener=()=>{};
global.document={getElementById:id=>{
    if(id==='stage')return napiCanvas;
    return store[id]||(store[id]=mkEl(id));},createElement:t=>mkEl('n-'+t),
  querySelector:()=>null,querySelectorAll:()=>[],body:mkEl('body'),addEventListener(){}};
global.window={AudioContext:function(){},addEventListener(){}};
global.window.MediaRecorder=function(){};global.window.MediaRecorder.isTypeSupported=()=>false;
global.MediaRecorder=global.window.MediaRecorder;
const _napi=require('@napi-rs/canvas');
global.HTMLCanvasElement=_napi.Canvas;
{ // 讓 mediabunny 的 context 型別檢查通過
  const _c=_napi.createCanvas(2,2).getContext('2d');
  global.CanvasRenderingContext2D=_c.constructor;
  global.OffscreenCanvasRenderingContext2D=_c.constructor;
}
global.OffscreenCanvas=_napi.Canvas;
global.screen={width:1080,height:2400};
global.navigator={userAgent:'Node test harness',hardwareConcurrency:8};
global.localStorage={getItem:()=>null,setItem(){}};
global.requestAnimationFrame=()=>0;global.cancelAnimationFrame=()=>{};
global.AudioBuffer=class AudioBuffer{
  constructor(o){this.numberOfChannels=o.numberOfChannels||1;this.length=o.length;
    this.sampleRate=o.sampleRate;this.duration=o.length/o.sampleRate;
    this._d=[];for(let i=0;i<this.numberOfChannels;i++)this._d.push(new Float32Array(this.length));}
  getChannelData(i){return this._d[i];}
  copyFromChannel(dest,ch,start=0){dest.set(this._d[ch].subarray(start,start+dest.length));}
  copyToChannel(src,ch,start=0){this._d[ch].set(src,start);}
};
global.confirm=()=>true;
// 註冊 @mediabunny/server 提供的 Node 端編解碼器，並用 napi canvas 取代瀏覽器 canvas
try{
  const {registerMediabunnyServer}=require('@mediabunny/server');
  registerMediabunnyServer(require('mediabunny'));
  global.__serverCodecs=true;
}catch(e){global.__serverCodecs=false;console.log('server codecs 不可用:',e.message);}
const napi=require('@napi-rs/canvas');


global.window.VideoEncoder=global.VideoEncoder||function(){};
global.window.VideoDecoder=global.VideoDecoder||function(){};

