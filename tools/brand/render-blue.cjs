// Run with sharp available on NODE_PATH. Sources are retained alongside their provenance.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const sharp = require('sharp');
const root = path.resolve(__dirname, '../..');
const sources = path.join(root, 'artwork/brand/blue-stars');
const resources = path.join(root, 'app/src/main/res');

(async () => {
  const report = [];
  for (const [source, prefix, width, origin, counters] of [
    ['wordmark.svg', 'nyanime_wordmark_blue', 640, [143.3099,73.9020,1764.4405], [[920,365],[1780,333]]],
    ['mark.svg', 'nyanime_app_icon_blue', 512, [62.65,18.68,600], [[300,440],[425,210]]],
  ]) {
    const svg = await fs.readFile(path.join(sources, source));
    for (const theme of ['dark','light']) {
      const target = path.join(resources, 'drawable-nodpi', `${prefix}_${theme}.webp`);
      await fs.mkdir(path.dirname(target), {recursive:true});
      let render = sharp(svg, {density:180}).resize({width});
      if (theme === 'dark') render = render.modulate({brightness:prefix.includes('wordmark')?1.45:1.1,saturation:.95});
      await render.webp({lossless:true,effort:6}).toFile(target);
      const {data,info} = await sharp(target).ensureAlpha().raw().toBuffer({resolveWithObject:true});
      const scale = info.width/origin[2];
      for (const [x,y] of counters) {
        const ix=Math.round((x-origin[0])*scale),iy=Math.round((y-origin[1])*scale);
        assert.equal(data[(iy*info.width+ix)*4+3],0,'Letter counters must be transparent');
      }
      for (const i of [0,info.width-1,(info.height-1)*info.width,info.width*info.height-1]) assert.equal(data[i*4+3],0);
      report.push({file:path.relative(root,target).replaceAll('\\','/'),width:info.width,height:info.height,
        sha256:crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex')});
    }
  }
  // Launcher artwork has its own high-contrast sky-blue palette. Do not brighten
  // the app's wordmark or derive this from the dark in-app mark again.
  const launcherTarget = path.join(resources,'drawable-nodpi/nyanime_launcher_foreground_blue.webp');
  await sharp(path.join(sources,'launcher.svg'), {density:180}).resize({width:512})
    .webp({lossless:true,effort:6}).toFile(launcherTarget);
  const mark = await fs.readFile(launcherTarget);
  const launcherInfo = await sharp(mark).metadata();
  const launcherPixels=await sharp(mark).ensureAlpha().raw().toBuffer();
  const transparentAt=(x,y)=>assert.equal(launcherPixels[(y*512+x)*4+3],0,
    'Launcher corners/counters must be transparent');
  for (const [x,y] of [[0,0],[511,0],[0,511],[511,511]]) transparentAt(x,y);
  for (const [x,y] of [[300,440],[425,210]]) {
    transparentAt(Math.round((x-62.65)*512/600),Math.round((y-18.68)*512/600));
  }
  report.push({file:path.relative(root,launcherTarget).replaceAll('\\','/'),
    width:launcherInfo.width,height:launcherInfo.height,
    sha256:crypto.createHash('sha256').update(mark).digest('hex')});
  for (const [density,size] of [['mdpi',48],['hdpi',72],['xhdpi',96],['xxhdpi',144],['xxxhdpi',192]]) {
    const directory=path.join(resources,`mipmap-${density}`);
    await fs.mkdir(directory,{recursive:true});
    const foreground=await sharp(mark).resize({width:Math.round(size*.86)}).png().toBuffer();
    const target=path.join(directory,'ic_launcher_blue.png');
    await sharp({create:{width:size,height:size,channels:4,background:'#080f29'}})
      .composite([{input:foreground,gravity:'centre'}]).png().toFile(target);
    report.push({file:path.relative(root,target).replaceAll('\\','/'),width:size,height:size,
      sha256:crypto.createHash('sha256').update(await fs.readFile(target)).digest('hex')});
  }
  await fs.writeFile(path.join(sources,'generated.json'),JSON.stringify({format:'lossless WebP / PNG',stars:'static',transparentCounters:true,launcherPalette:'sky-blue on midnight',assets:report},null,2)+'\n');
  console.log('Rendered five transparent brand resources and five launcher sizes.');
})().catch(error=>{console.error(error);process.exit(1)});
