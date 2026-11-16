// B站封面截图器：起一个静态服务 → 无头 Chromium 打开页面 → 存 PNG。
// 用法：node render.mjs [页面] [宽x高] [输出文件名]
//   node render.mjs square.html 1600x1200 cover-4x3.png    4:3  · 首页 / 分区列表
//   node render.mjs wide.html   1920x1080 cover-16x9.png   16:9 · 播放页 / 推荐流
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadChromium, launch, serve, openPage } from '/Users/panzhihe/.workbuddy/skills/motion-showreel/scripts/lib/browser.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');          // promo/ —— ../v3/... 的字体和截图都从这里取

const rel = process.argv[2] || 'square.html';
const [W, H] = (process.argv[3] || '1600x1200').split('x').map(Number);
const OUT = path.join(HERE, process.argv[4] || 'cover-4x3.png');

const chromium = await loadChromium(ROOT);
const browser = await launch(chromium);
const { server, port } = await serve(ROOT);

try {
  const page = await openPage(browser, { port, rel: 'cover/' + rel, w: W, h: H, dpr: 1 });
  await page.waitForFunction(() => window.READY === true, null, { timeout: 60000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: OUT, clip: { x: 0, y: 0, width: W, height: H } });
  console.log(OUT + '   ' + W + 'x' + H);
} finally {
  await browser.close();
  server.close();
}
