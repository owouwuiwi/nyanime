import init, {Universe, Species} from '../wasm/sandtable.js';
export {Universe, Species};
export let memory;
export async function initialize() {
  const instance = await init({module_or_path:new URL('sandtable_bg.wasm',import.meta.url)});
  memory = instance.memory;
}
