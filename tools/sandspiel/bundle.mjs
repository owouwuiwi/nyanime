import fs from 'node:fs';
import path from 'node:path';
import esbuild from 'esbuild';
import glslify from 'glslify';
import {here,root,work,sha256} from './prepare.mjs';

const destination=path.join(root,'feature/secret-game/src/main/assets/secret-game/sandspiel');
fs.mkdirSync(destination,{recursive:true});
await esbuild.build({
  entryPoints:[path.join(work,'js/runtime.mjs')],
  outfile:path.join(destination,'game.js'),
  bundle:true,format:'esm',platform:'browser',target:'chrome105',minify:true,legalComments:'eof',
  nodePaths:[path.join(here,'node_modules')],
  plugins:[{name:'original-glsl',setup(build){
    build.onLoad({filter:/\.glsl$/},args=>({
      contents:glslify.compile(fs.readFileSync(args.path,'utf8'),{basedir:path.dirname(args.path),transform:[]}),
      loader:'text',resolveDir:path.dirname(args.path),
    }));
  }}],
});
for(const file of ['index.html','game.css']) fs.copyFileSync(path.join(here,file),path.join(destination,file));
fs.copyFileSync(path.join(work,'wasm/sandtable_bg.wasm'),path.join(destination,'sandtable_bg.wasm'));
fs.copyFileSync(path.join(work,'LICENSE'),path.join(destination,'Sandspiel-LICENSE.txt'));
fs.copyFileSync(path.join(work,'FLUID-LICENSE.txt'),path.join(destination,'Fluid-LICENSE.txt'));
fs.copyFileSync(path.join(work,'wasm/RUST-NOTICES.txt'),path.join(destination,'RUST-NOTICES.txt'));
const licenses=[];
for(const dependency of ['regl','glsl-noise','glsl-random']) {
  const folder=path.join(here,'node_modules',dependency);
  const license=fs.readdirSync(folder).find(file=>/^licen[sc]e(?:\.|$)/i.test(file));
  if(!license) throw new Error(`Missing license: ${dependency}`);
  licenses.push(`${dependency}\n${fs.readFileSync(path.join(folder,license),'utf8')}\n`);
}
fs.writeFileSync(path.join(destination,'DEPENDENCIES.txt'),licenses.join('\n'));
const source=JSON.parse(fs.readFileSync(path.join(work,'sources.json'),'utf8'));
const assets={};
for(const file of ['game.js','index.html','game.css','sandtable_bg.wasm','Sandspiel-LICENSE.txt','Fluid-LICENSE.txt','DEPENDENCIES.txt','RUST-NOTICES.txt']) assets[file]=sha256(fs.readFileSync(path.join(destination,file)));
const adapter={};
for(const file of ['prepare.mjs','bundle.mjs','build-wasm.sh','rust-notices.py','Cargo.toml','Cargo.lock','package.json','package-lock.json','runtime.mjs','state.mjs','wasm.mjs','hsv-rgb.glsl','index.html','game.css']) {
  adapter[file]=sha256(fs.readFileSync(path.join(here,file),'utf8').replaceAll('\r\n','\n'));
}
fs.writeFileSync(path.join(destination,'provenance.json'),JSON.stringify({v:1,...source,toolchain:fs.readFileSync(path.join(work,'wasm/toolchain.txt'),'utf8'),adapter,assets},null,2)+'\n');
console.log(`Built offline game: ${Object.keys(assets).length} assets; no community or telemetry runtime.`);
