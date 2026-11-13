// B站封面截图器：起一个静态服务 → 无头 Chromium 打开 cover/index.html → 存 1920×1200 PNG。
// 复用 motion-showreel 的浏览器封装（Playwright 由那个 skill 提供）。
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadChromium, launch, serve, openPage } from '/Users/panzhihe/.workbuddy/skills/motion-showreel/scripts/lib/browser.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');          // promo/ —— 这样 ../v3/... 的字体和截图都能取到
const OUT = process.argv[2] || path.join(HERE, 'cover-bilibili.png');
const W = 1920, H = 1200;

const chromium = await loadChromium(ROOT);
const browser = await launch(chromium);
const { server, port } = await serve(ROOT);

try {
  const page = await openPage(browser, { port, rel: 'cover/index.html', w: W, h: H, dpr: 1 });
  await page.waitForFunction(() => window.READY === true, null, { timeout: 60000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: OUT, clip: { x: 0, y: 0, width: W, height: H } });
  console.log(OUT);
} finally {
  await browser.close();
  server.close();
}
