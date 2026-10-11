import {initialize, Universe, Species, memory} from './wasm.mjs';
import {startFluid} from './fluid.js';
import {startWebGL} from './render.js';
import {WIDTH,HEIGHT,BRUSHES,MATERIALS,pointInWorld,decodeScene,encodeScene} from './state.mjs';

const canvas=document.querySelector('#sand-canvas');
const fluidCanvas=document.querySelector('#fluid-canvas');
let universe,fluid,draw,initialized=false,configured=false,destroyed=false,failed=false;
let material=Species.Sand,brush=2,userPaused=true,suspended=false,frame=0,lastTick=0;
let activePointer=null,previousPoint=null,latestPoint=null,checkpointTimer=0,checkpointId=0;
let restoreParts=null,restoreLength=0,lastCheckpoint=0,frames=0,startedAt=0;
let startupStage='wasm';
const post = value => {
  if (!destroyed) window.SecretGameHost?.postMessage(JSON.stringify({v:1,...value}));
};
const cells = () => new Uint8Array(memory.buffer,universe.cells(),WIDTH*HEIGHT*4);
const emitState = () => post({type:'state',paused:userPaused,material,brush});
const stop = () => { cancelAnimationFrame(frame); frame=0; activePointer=null; previousPoint=null; latestPoint=null; };

function checkpoint(requestId=0) {
  clearTimeout(checkpointTimer);
  if (!initialized || !configured || destroyed || failed) return;
  const payload=JSON.stringify(encodeScene(cells(),material,brush));
  const id=++checkpointId,partCount=Math.ceil(payload.length/24000);
  post({type:'snapshotBegin',id,partCount});
  for (let index=0;index<partCount;index++) post({type:'snapshotPart',id,index,data:payload.slice(index*24000,(index+1)*24000)});
  post({type:'snapshotEnd',id,requestId});
  lastCheckpoint=performance.now();
}
function saveSoon() {
  clearTimeout(checkpointTimer);
  checkpointTimer=setTimeout(()=>checkpoint(),800);
}
function fail(error) {
  if (destroyed || failed) return;
  failed=true;
  stop();
  clearTimeout(checkpointTimer);
  post({type:'error',code:'GRAPHICS_UNAVAILABLE',stage:startupStage,
    reason:error instanceof Error ? `${error.name}: ${error.message}`.slice(0,240) : 'Graphics context unavailable'});
}
function stamp(point) {
  if (!point) return;
  universe.paint(point.x,point.y,BRUSHES[brush],material);
}
function paintLine(from,to) {
  const distance=Math.hypot(to.x-from.x,to.y-from.y);
  const steps=Math.max(1,Math.ceil(distance/Math.max(1,BRUSHES[brush]/3)));
  for(let i=1;i<=steps;i++) stamp({x:Math.round(from.x+(to.x-from.x)*i/steps),y:Math.round(from.y+(to.y-from.y)*i/steps)});
}
function schedule() {
  if (!frame && initialized && !destroyed && !failed && !suspended && !document.hidden && (!userPaused || activePointer!==null)) frame=requestAnimationFrame(tick);
}
function tick(now) {
  frame=0;
  if (destroyed || failed || suspended || document.hidden) return;
  try {
    if(now-lastTick>=1000/60-0.5) {
      if(activePointer!==null) stamp(latestPoint);
      if(!userPaused) { universe.tick(); fluid.update(); }
      draw();
      lastTick=now;
      frames++;
      if(now-lastCheckpoint>=15000) checkpoint();
    }
    schedule();
  } catch(error) { fail(error); }
}
function restore(value) {
  if(value) {
    const scene=decodeScene(value);
    universe.reset();
    cells().set(scene.cells);
    universe.flush_undos();
    material=scene.material;
    brush=scene.brush;
  }
  userPaused=true;
  configured=true;
  resetFluid();
  draw();
  emitState();
  post({type:'initialized'});
}
function resetFluid() {
  fluid.reset(); fluid.update();
  fluid.reset(); fluid.update();
}
function dispose() {
  if(destroyed) return;
  stop();
  clearTimeout(checkpointTimer);
  destroyed=true;
  draw?.destroy();
  fluid?.destroy();
  universe?.free();
}
function command(value) {
  if(!value || value.v!==1 || destroyed || failed) return;
  if(value.type==='dispose') { dispose(); return; }
  if(!initialized) return;
  switch(value.type) {
    case 'init': restore(null); break;
    case 'restoreBegin': restoreParts=[]; restoreLength=0; break;
    case 'restorePart':
      if(!restoreParts || typeof value.data!=='string' || value.data.length>24000 || value.index!==restoreParts.length || (restoreLength+=value.data.length)>500000) throw new Error('restore');
      restoreParts.push(value.data); break;
    case 'restoreEnd':
      if(!restoreParts) throw new Error('restore');
      restore(JSON.parse(restoreParts.join(''))); restoreParts=null; break;
    case 'material': if(MATERIALS.has(value.value)) { material=value.value; emitState(); saveSoon(); } break;
    case 'brush': if(Number.isInteger(value.value) && value.value>=0 && value.value<BRUSHES.length) { brush=value.value; emitState(); saveSoon(); } break;
    case 'pause': userPaused=true; stop(); checkpoint(); emitState(); break;
    case 'resume': userPaused=false; lastTick=0; emitState(); schedule(); break;
    case 'suspend': suspended=true; stop(); checkpoint(); break;
    case 'foreground': suspended=false; lastTick=0; schedule(); break;
    case 'checkpoint': checkpoint(value.requestId ?? 0); break;
    case 'undo': universe.pop_undo(); resetFluid(); draw(); checkpoint(); break;
    case 'clear': universe.push_undo(); universe.reset(); resetFluid(); draw(); checkpoint(); break;
    case 'stats': post({type:'stats',frames,elapsedMs:Math.round(performance.now()-startedAt),running:frame!==0}); break;
  }
}

window.addEventListener('message',event=>{
  if(event.origin && event.origin!==location.origin) return;
  if(typeof event.data!=='string' || event.data.length>24576) return;
  try { command(JSON.parse(event.data)); } catch { post({type:'error',code:'SCENE_INVALID'}); }
});
canvas.addEventListener('pointerdown',event=>{
  if(!initialized || failed || suspended || activePointer!==null) return;
  event.preventDefault();
  canvas.setPointerCapture(event.pointerId);
  activePointer=event.pointerId;
  universe.push_undo();
  previousPoint=latestPoint=pointInWorld(event.clientX,event.clientY,canvas.getBoundingClientRect());
  stamp(latestPoint); draw(); schedule();
});
canvas.addEventListener('pointermove',event=>{
  if(event.pointerId!==activePointer) return;
  event.preventDefault();
  const next=pointInWorld(event.clientX,event.clientY,canvas.getBoundingClientRect());
  if(next && previousPoint) paintLine(previousPoint,next);
  previousPoint=latestPoint=next;
  draw();
});
const finishPointer = event=>{
  if(event.pointerId!==activePointer) return;
  activePointer=null; previousPoint=latestPoint=null;
  if(userPaused) stop();
  saveSoon();
};
canvas.addEventListener('pointerup',finishPointer);
canvas.addEventListener('pointercancel',finishPointer);
canvas.addEventListener('lostpointercapture',finishPointer);
for(const view of [canvas,fluidCanvas]) view.addEventListener('webglcontextlost',event=>{ event.preventDefault(); fail(); });
document.addEventListener('visibilitychange',()=>{
  if(document.hidden) { stop(); checkpoint(); clearTimeout(checkpointTimer); }
  else schedule();
});
window.addEventListener('pagehide',()=>{ checkpoint(); dispose(); });

try {
  if(typeof WebAssembly!=='object') throw new Error('wasm');
  await initialize();
  startupStage='universe';
  universe=Universe.new(WIDTH,HEIGHT);
  const dpi=Math.min(2,Math.max(1,window.devicePixelRatio));
  canvas.width=Math.round(WIDTH*dpi); canvas.height=Math.round(HEIGHT*dpi);
  startupStage='fluid';
  fluid=startFluid({universe,controls:()=>({material,radius:BRUSHES[brush],paused:userPaused})});
  startupStage='renderer';
  draw=startWebGL({canvas,universe});
  initialized=true;
  startedAt=lastCheckpoint=performance.now();
  draw();
  startupStage='running';
  post({type:'ready'});
} catch(error) { fail(error); }
