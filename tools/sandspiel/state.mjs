export const WIDTH = 300;
export const HEIGHT = 300;
export const CELL_BYTES = WIDTH * HEIGHT * 4;
export const BRUSHES = [1, 3, 7, 19, 39];
export const MATERIALS = new Set([0,1,2,3,4,5,6,7,8,9,11,12,13,14,15,16,17,18,19]);

export function pointInWorld(x,y,rect) {
  if (!(rect.width > 0 && rect.height > 0)) return null;
  return {
    x:Math.max(0,Math.min(WIDTH-1,Math.floor((x-rect.left)*WIDTH/rect.width))),
    y:Math.max(0,Math.min(HEIGHT-1,Math.floor((y-rect.top)*HEIGHT/rect.height))),
  };
}

export function decodeScene(value) {
  if (!value || value.v !== 1 || typeof value.cells !== 'string' || value.cells.length !== CELL_BYTES/3*4) throw new Error('scene');
  if (!MATERIALS.has(value.material) || !Number.isInteger(value.brush) || value.brush < 0 || value.brush >= BRUSHES.length) throw new Error('scene');
  const text = atob(value.cells);
  if (text.length !== CELL_BYTES) throw new Error('scene');
  const cells = Uint8Array.from(text, char=>char.charCodeAt(0));
  for (let i=0;i<cells.length;i+=4) {
    if (!MATERIALS.has(cells[i])) throw new Error('scene');
    // The restored world begins at generation zero, with a fresh transient fluid field.
    cells[i+3]=0;
  }
  return {cells,material:value.material,brush:value.brush};
}

export function encodeScene(cells,material,brush) {
  let binary='';
  for (let i=0;i<cells.length;i+=8192) binary+=String.fromCharCode(...cells.subarray(i,i+8192));
  return {v:1,cells:btoa(binary),material,brush};
}
