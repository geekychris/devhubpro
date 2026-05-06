/**
 * Capture screenshots of the running dev portal. Driven by the SHOTS array — each entry names
 * a route, optional setup (e.g. dispatch the goto-tab event), and a wait condition.
 *
 * Usage (with frontend on :5173 + backend on :8081):
 *   pnpm tsx scripts/screenshots.ts            # writes PNGs into ../docs/img/
 *   pnpm tsx scripts/screenshots.ts dashboard  # only the named shot
 */
import { chromium, type Page } from 'playwright';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const OUT = resolve(HERE, '..', '..', 'docs', 'img');
const APP = process.env.PORTAL_URL ?? 'http://localhost:5173';
const VIEWPORT = { width: 1440, height: 900 };

type Shot = {
  name: string;
  path: string;
  /** dispatch this to switch to a specific tab on the asset detail page */
  tab?: string;
  /** extra wait — runs in browser after navigation but before screenshot */
  setup?: (page: Page) => Promise<void>;
  /** seconds to wait after setup, before screenshot */
  settleSeconds?: number;
  /** capture full page (default: true) */
  fullPage?: boolean;
  /** crop to a CSS selector */
  selector?: string;
};

const SHOTS: Shot[] = [
  {
    name: '01-dashboard',
    path: '/dashboard',
    settleSeconds: 2,
    fullPage: true,
  },
  {
    name: '02-assets-list',
    path: '/assets',
    settleSeconds: 2,
    fullPage: false,
  },
  {
    name: '03-search-results',
    path: '/assets',
    setup: async (page) => {
      const input = page.locator('input[placeholder*="Search" i]').first();
      await input.click();
      await input.fill('redis');
      await page.waitForTimeout(500);
    },
    settleSeconds: 1,
    fullPage: false,
  },
  {
    name: '04-asset-overview',
    path: '/assets/enterprise-social-platform',
    settleSeconds: 2,
    fullPage: true,
  },
  {
    name: '05-asset-panels',
    path: '/assets/enterprise-social-platform',
    tab: 'panels',
    settleSeconds: 4,
    fullPage: true,
  },
  {
    name: '06-asset-graph',
    path: '/assets/hitorro-text-core',
    tab: 'graph',
    settleSeconds: 2,
    setup: async (page) => {
      // ReactFlow renders with default zoom; click fit-view so the whole graph is visible.
      await page.waitForTimeout(2000);
      const fit = page.locator('.react-flow__controls-fitview').first();
      if (await fit.count()) await fit.click();
      await page.waitForTimeout(800);
    },
    fullPage: false,
  },
  {
    name: '07-asset-fixtures',
    path: '/assets/enterprise-social-platform',
    tab: 'fixtures',
    settleSeconds: 2,
    fullPage: false,
  },
  {
    name: '08-asset-docs',
    path: '/assets/enterprise-social-platform',
    tab: 'docs',
    setup: async (page) => {
      // Click into a doc with Mermaid diagrams and wait until the SVG actually renders.
      await page.waitForTimeout(2000);
      const link = page.locator('button, a').filter({ hasText: 'docs/ARCHITECTURE.md' }).first();
      if (await link.count()) {
        await link.click();
        // Mermaid is dynamic-imported; wait for the first rendered diagram to appear.
        await page.waitForSelector('.mermaid-rendered svg', { timeout: 30_000 });
        await page.waitForTimeout(1500); // let remaining diagrams render
      }
      // The markdown viewer caps at max-h-[70vh] for in-app scrolling. For screenshots we want
      // the entire article to stretch so full-page captures the diagrams below the fold.
      await page.evaluate(() => {
        document.querySelectorAll('.markdown').forEach((el) => {
          (el as HTMLElement).style.maxHeight = 'none';
          (el as HTMLElement).style.overflow = 'visible';
        });
      });
      await page.waitForTimeout(500);
    },
    settleSeconds: 1,
    fullPage: true,
  },
  {
    name: '09-asset-runtime',
    path: '/assets/enterprise-social-platform',
    tab: 'runtime',
    settleSeconds: 2,
    fullPage: false,
  },
  {
    name: '10-ports',
    path: '/ports',
    settleSeconds: 2,
    fullPage: false,
  },
];

async function main() {
  const filter = process.argv[2];
  const targets = filter ? SHOTS.filter((s) => s.name.includes(filter)) : SHOTS;
  if (targets.length === 0) {
    console.error(`no shots match "${filter}"`);
    process.exit(1);
  }
  await mkdir(OUT, { recursive: true });

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  // Surface frontend errors so a broken tab doesn't silently render an empty shot.
  page.on('pageerror', (err) => console.warn('  pageerror:', err.message));

  for (const shot of targets) {
    const url = APP + shot.path;
    process.stdout.write(`→ ${shot.name}: ${url}${shot.tab ? ' #' + shot.tab : ''} … `);
    await page.goto(url, { waitUntil: 'networkidle', timeout: 30_000 });
    if (shot.tab) {
      await page.evaluate(
        (tab) => window.dispatchEvent(new CustomEvent('devportal:goto-tab', { detail: tab })),
        shot.tab,
      );
    }
    if (shot.setup) await shot.setup(page);
    await page.waitForTimeout((shot.settleSeconds ?? 1) * 1000);

    const file = resolve(OUT, `${shot.name}.png`);
    if (shot.selector) {
      const loc = page.locator(shot.selector).first();
      await loc.screenshot({ path: file });
    } else {
      await page.screenshot({ path: file, fullPage: shot.fullPage ?? false });
    }
    console.log('saved');
  }

  await browser.close();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
