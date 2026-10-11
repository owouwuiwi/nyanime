import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import init,{Universe,Species} from '../.work/wasm/sandtable.js';
import {CELL_BYTES,decodeScene,encodeScene,pointInWorld} from '../state.mjs';

const wasm=await init({module_or_path:fs.readFileSync(new URL('../.work/wasm/sandtable_bg.wasm',import.meta.url))});
const bytes = world => new Uint8Array(wasm.memory.buffer,world.cells(),300*300*4);

test('original WASM sand falls and preserves the painted material',()=>{
  const world=Universe.new(300,300);
  try {
    world.paint(150,30,3,Species.Sand);
    // The fluid renderer encodes still air as (126,126); raw zero bytes are wind.
    const wind=new Uint8Array(wasm.memory.buffer,world.winds(),300*300*4);
    for(let i=0;i<wind.length;i+=4) {wind[i]=126;wind[i+1]=126;}
    const bottom=()=>{
      let y=-1; const data=bytes(world);
      for(let i=0;i<data.length;i+=4) if(data[i]===Species.Sand) y=Math.max(y,(i/4)%300);
      return y;
    };
    const first=bottom();
    for(let i=0;i<20;i++) world.tick();
    assert.ok(bottom()>first);
  } finally {world.free();}
});

test('original undo restores the creation and all declared elements can be painted',()=>{
  const world=Universe.new(300,300);
  try {
    for(const id of Object.values(Species).filter(Number.isInteger)) world.paint(5+id*12,10,2,id);
    const before=bytes(world).slice();
    world.push_undo();
    world.reset();
    world.pop_undo();
    assert.deepEqual(bytes(world),before);
  } finally {world.free();}
});

test('scene round trip retains composition, brush and selected material',()=>{
  const source=new Uint8Array(CELL_BYTES);
  source[800]=3; source[801]=130; source[803]=17;
  const restored=decodeScene(encodeScene(source,18,4));
  assert.equal(restored.material,18);
  assert.equal(restored.brush,4);
  assert.equal(restored.cells[800],3);
  assert.equal(restored.cells[801],130);
  assert.equal(restored.cells[803],0);
});

test('unknown WASM enum values and oversized or incomplete scenes are rejected',()=>{
  const source=new Uint8Array(CELL_BYTES);
  source[0]=255;
  assert.throws(()=>decodeScene(encodeScene(source,2,2)));
  assert.throws(()=>decodeScene({v:1,cells:'AAAA',material:2,brush:2}));
  assert.throws(()=>decodeScene(encodeScene(new Uint8Array(CELL_BYTES),2,5)));
});

test('touch coordinates follow the visible canvas after resize and clip at its bounds',()=>{
  assert.deepEqual(pointInWorld(160,90,{left:10,top:40,width:300,height:100}),{x:150,y:150});
  assert.deepEqual(pointInWorld(-200,999,{left:10,top:40,width:300,height:100}),{x:0,y:299});
  assert.equal(pointInWorld(1,1,{left:0,top:0,width:0,height:0}),null);
});

test('packaged runtime omits the upstream network services and remote resources',()=>{
  const folder=new URL('../../../feature/secret-game/src/main/assets/secret-game/sandspiel/',import.meta.url);
  const script=fs.readFileSync(new URL('game.js',folder),'utf8');
  assert.doesNotMatch(script,/sentry|firebase|adsbygoogle|googletagmanager|serviceWorker|WebSocket|XMLHttpRequest/);
  assert.doesNotMatch(script,/window\.UI|window\.paused/);
  const html=fs.readFileSync(new URL('index.html',folder),'utf8');
  assert.doesNotMatch(html,/(?:src|href)=["']https?:/);
  assert.match(html,/default-src 'none'/);
});
