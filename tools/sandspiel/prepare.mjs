import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

export const here = path.dirname(fileURLToPath(import.meta.url));
export const root = path.resolve(here, '../..');
export const upstream = path.join(root, 'vendor/sandspiel');
export const work = path.join(here, '.work');
export const revision = '9936e06b07a583aedfba9589c0cead2917f405fb';
export const sha256 = data => crypto.createHash('sha256').update(data).digest('hex');
const sourceBytes = relative => execFileSync('git', ['-C', upstream, 'show', `HEAD:${relative}`]);

function replaceOnce(text, before, after) {
  if (!text.includes(before) || text.indexOf(before) !== text.lastIndexOf(before)) {
    throw new Error(`Upstream adaptation no longer matches: ${before.slice(0, 90)}`);
  }
  return text.replace(before, after);
}

export function prepare() {
  const actual = execFileSync('git', ['-C', upstream, 'rev-parse', 'HEAD'], {encoding:'utf8'}).trim();
  if (actual !== revision) throw new Error(`Sandspiel ${revision} required; got ${actual}`);
  if (execFileSync('git', ['-C', upstream, 'status', '--porcelain'], {encoding:'utf8'}).trim()) {
    throw new Error('Sandspiel submodule must remain pristine. Adapt only the generated copy.');
  }
  fs.mkdirSync(work, {recursive:true});
  const sources = {};
  for (const directory of ['crate/src', 'js/glsl']) {
    for (const item of fs.readdirSync(path.join(upstream, directory))) {
      const relative = `${directory}/${item}`;
      const target = path.join(work, relative);
      fs.mkdirSync(path.dirname(target), {recursive:true});
      const bytes = sourceBytes(relative);
      sources[relative] = sha256(bytes);
      fs.writeFileSync(target, bytes);
    }
  }
  for (const file of ['js/render.js', 'js/fluid.js', 'js/fluidShaders.js', 'LICENSE']) {
    const bytes = sourceBytes(file);
    sources[file] = sha256(bytes);
    fs.mkdirSync(path.dirname(path.join(work,file)), {recursive:true});
    fs.writeFileSync(path.join(work,file),bytes);
  }
  fs.copyFileSync(path.join(here, 'Cargo.toml'), path.join(work, 'crate/Cargo.toml'));
  const cargoLock = path.join(here, 'Cargo.lock');
  if (fs.existsSync(cargoLock)) fs.copyFileSync(cargoLock, path.join(work, 'crate/Cargo.lock'));

  let render = fs.readFileSync(path.join(work,'js/render.js'),'utf8');
  render = replaceOnce(render, 'import * as wasm from "../crate/pkg/sandtable_bg.wasm";', 'import * as wasm from "./wasm.mjs";');
  render = replaceOnce(render, 'import { Species } from "../crate/pkg/sandtable";', 'import { Species } from "./wasm.mjs";');
  render = replaceOnce(render, 'import { Universe } from "../crate/pkg";', 'import { Universe } from "./wasm.mjs";');
  render = replaceOnce(render, 'const memory = wasm.memory;', '');
  render = render.replaceAll('memory.buffer', 'wasm.memory.buffer');
  render = replaceOnce(render, '  return () => {\n    regl.poll();\n    drawSand();\n  };', '  const draw = () => { regl.poll(); drawSand(); };\n  draw.destroy = () => regl.destroy();\n  return draw;');
  fs.writeFileSync(path.join(work,'js/render.js'),render);

  let fluid = fs.readFileSync(path.join(work,'js/fluid.js'),'utf8');
  const license = fluid.slice(0, fluid.indexOf('"use strict";')).replace(/^\/\/ ?/gm,'');
  fs.writeFileSync(path.join(work,'FLUID-LICENSE.txt'),license);
  fluid = replaceOnce(fluid, 'import * as dat from "dat.gui";', '');
  fluid = replaceOnce(fluid, 'import * as wasm from "../crate/pkg/sandtable_bg.wasm";', 'import * as wasm from "./wasm.mjs";');
  fluid = replaceOnce(fluid, 'const memory = wasm.memory;', '');
  fluid = fluid.replaceAll('memory.buffer', 'wasm.memory.buffer');
  // The upstream fluid input reads its React UI singleton only after a drag.
  // Native controls own that state here; never recreate a global UI shim.
  fluid = replaceOnce(fluid, 'function startFluid({ universe }) {', 'function startFluid({ universe, controls }) {');
  fluid = replaceOnce(fluid, 'window.UI.state.selectedElement', 'controls().material');
  fluid = replaceOnce(fluid, '(window.UI.state.size + 1) / 600', 'controls().radius / 600');
  fluid = replaceOnce(fluid, '!window.paused', '!controls().paused');
  fluid = replaceOnce(fluid, '  startGUI();', '');
  const guiStart = fluid.indexOf('  function startGUI() {');
  const guiEnd = fluid.indexOf('  function pointerPrototype',guiStart);
  if (guiStart < 0 || guiEnd < 0) throw new Error('Cannot isolate the upstream debug GUI');
  fluid = fluid.slice(0,guiStart) + fluid.slice(guiEnd);
  fluid = replaceOnce(fluid, 'sandCanvas.width /\n      Math.ceil(window.devicePixelRatio) /\n      boundingRect.width', 'universe.width() / boundingRect.width');
  fluid = replaceOnce(fluid, 'sandCanvas.height /\n      Math.ceil(window.devicePixelRatio) /\n      boundingRect.height', 'universe.height() / boundingRect.height');
  fluid = replaceOnce(fluid, '  return { update, reset };', '  return { update, reset, destroy: () => { gl.getExtension("WEBGL_lose_context")?.loseContext(); } };');
  fs.writeFileSync(path.join(work,'js/fluid.js'),fluid);
  for (const file of ['runtime.mjs','state.mjs','wasm.mjs']) fs.copyFileSync(path.join(here,file),path.join(work,'js',file));
  // The upstream HSV package does not declare a license. Use our sector-based
  // implementation of the standard HSV transform, with the same input/output space.
  const shaderPath=path.join(work,'js/glsl/sand.glsl');
  fs.writeFileSync(shaderPath,replaceOnce(fs.readFileSync(shaderPath,'utf8'),
    "#pragma glslify: hsv2rgb = require('glsl-hsv2rgb')",
    "#pragma glslify: hsv2rgb = require('./hsv-rgb.glsl')"));
  fs.copyFileSync(path.join(here,'hsv-rgb.glsl'),path.join(work,'js/glsl/hsv-rgb.glsl'));
  fs.writeFileSync(path.join(work,'sources.json'),JSON.stringify({revision,sources},null,2)+'\n');
  console.log(`Prepared pristine Sandspiel ${revision}; ${Object.keys(sources).length} source hashes recorded.`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) prepare();
