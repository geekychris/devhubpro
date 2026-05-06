// Probe the running app for element bounding boxes — used to pick callout coordinates.
// Output is consumed by hand to write annotations.json.
import { chromium } from 'playwright';

const APP = process.env.PORTAL_URL ?? 'http://localhost:5173';
const VIEWPORT = { width: 1440, height: 900 };

async function bbox(page, selector, root = null) {
  const target = root ? root.locator(selector).first() : page.locator(selector).first();
  if (!(await target.count())) return null;
  return await target.boundingBox();
}

async function center(page, selector, opts = {}) {
  const b = await bbox(page, selector);
  if (!b) return null;
  return { x: Math.round(b.x + b.width / 2), y: Math.round(b.y + b.height / 2), w: Math.round(b.width), h: Math.round(b.height) };
}

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 2 });
const page = await ctx.newPage();

async function probe(label, fn) {
  const out = await fn();
  console.log(`---- ${label} ----`);
  for (const [k, v] of Object.entries(out)) console.log(`  ${k}: ${v ? JSON.stringify(v) : 'null'}`);
}

await probe('01-dashboard', async () => {
  await page.goto(APP + '/dashboard', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  return {
    headline: await center(page, 'h1:has-text("Running services")'),
    hideOffToggle: await center(page, 'label:has-text("Hide off")'),
    leftStartBtn: await center(page, 'section:has-text("hitorro-spring-boot") button:has-text("Start")'),
    rightStartBtn: await center(page, 'section:has-text("enterprise_social_platform") button:has-text("Start")'),
    rightStopBtn: await center(page, 'section:has-text("enterprise_social_platform") button:has-text("Stop")'),
    rightStarBtn: await center(page, 'section:has-text("enterprise_social_platform") button[title*="dashboard"]'),
    rightLiveChip: await center(page, 'section:has-text("enterprise_social_platform") span:text-is("live")'),
    leftStoppedChip: await center(page, 'section:has-text("hitorro-spring-boot") span:text-is("stopped")'),
    webUiButton: await center(page, 'section:has-text("enterprise_social_platform") a[title*="frontend"]'),
    swaggerLink: await center(page, 'a:has-text("Swagger")'),
    credChip: await center(page, 'a:has-text("show-accounts"), a:has-text("seed-tenants")'),
  };
});

await probe('02-assets-list', async () => {
  await page.goto(APP + '/assets', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  return {
    topFavorites: await center(page, 'section:has-text("TOP FAVORITES")'),
    searchInput: await center(page, 'input[placeholder*="multiple words"]'),
    typeFilter: await center(page, 'select'),
    favoritesOnly: await center(page, 'label:has-text("Favorites only")'),
    firstStarBar: await center(page, 'li:has-text("hitorro-spring-boot") .text-yellow-400, li:has-text("hitorro-spring-boot") svg'),
    tags: await center(page, 'li:has-text("hitorro-spring-boot") span:has-text("infra")'),
  };
});

await probe('03-search', async () => {
  await page.goto(APP + '/assets', { waitUntil: 'networkidle' });
  const input = page.locator('input[placeholder*="Search" i]').first();
  await input.click();
  await input.fill('redis');
  await page.waitForTimeout(700);
  return {
    searchInput: await center(page, 'header input'),
    assetsLabel: await center(page, '*:text-is("ASSETS")'),
    docsLabel: await center(page, '*:text-is("DOCS")'),
  };
});

await probe('04-overview', async () => {
  await page.goto(APP + '/assets/enterprise-social-platform', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  return {
    tabsRow: await center(page, 'button:has-text("dependencies")'),
    favoriteBar: await center(page, 'button:has-text("Add to favorites")'),
    pinBar: await center(page, 'button:has-text("Pinned to dashboard"), button:has-text("Pin to dashboard")'),
    askClaude: await center(page, 'button:has-text("Ask Claude"), a:has-text("Ask Claude")'),
    tagsRow: await center(page, 'input[placeholder*="Add a tag"]'),
    githubCard: await center(page, '*:text-is("GitHub")'),
  };
});

await probe('06-graph', async () => {
  await page.goto(APP + '/assets/hitorro-text-core', { waitUntil: 'networkidle' });
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('devportal:goto-tab', { detail: 'graph' })));
  await page.waitForTimeout(2500);
  await page.locator('.react-flow__controls-fitview').first().click();
  await page.waitForTimeout(800);
  return {
    directionSel: await center(page, 'select:near(:text("Direction"))'),
    producerDepth: await center(page, 'select:near(:text("Producer depth"))'),
    layoutBtn: await center(page, 'button:has-text("producers right")'),
    fullscreen: await center(page, 'button:has-text("Fullscreen")'),
    fitViewBtn: await center(page, '.react-flow__controls-fitview'),
  };
});

await probe('07-fixtures', async () => {
  await page.goto(APP + '/assets/enterprise-social-platform', { waitUntil: 'networkidle' });
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('devportal:goto-tab', { detail: 'fixtures' })));
  await page.waitForTimeout(1500);
  return {
    fixtureName: await center(page, '*:text-is("show-accounts")'),
    runBtn: await center(page, 'button:has-text("Run again"), button:has-text("Run")'),
    credentialsTable: await center(page, 'table'),
    loginPageLink: await center(page, 'a:has-text("Login page")'),
    succeededBadge: await center(page, '*:text-is("succeeded")'),
  };
});

await probe('09-runtime', async () => {
  await page.goto(APP + '/assets/enterprise-social-platform', { waitUntil: 'networkidle' });
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('devportal:goto-tab', { detail: 'runtime' })));
  await page.waitForTimeout(1500);
  return {
    portsHeader: await center(page, 'h2:has-text("Port reservations"), h3:has-text("Port reservations")'),
    allocateLocal: await center(page, 'button:has-text("Allocate local")'),
    allocateNodePort: await center(page, 'button:has-text("Allocate k8s NodePort")'),
    dockerHeader: await center(page, 'h2:has-text("Docker"), h3:has-text("Docker")'),
    plusDeps: await center(page, 'button:has-text("+ dependents")'),
    runContainer: await center(page, 'button:has-text("Run container")'),
  };
});

await probe('10-ports', async () => {
  await page.goto(APP + '/ports', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  return {
    title: await center(page, 'h1:has-text("Port registry")'),
    filter: await center(page, 'input[placeholder*="filter" i]'),
    groupToggle: await center(page, 'button:has-text("scope")'),
    localSection: await center(page, '*:text-is("Local docker (host port)")'),
    k8sSection: await center(page, '*:text-matches("Kubernetes NodePort", "i")'),
  };
});

await browser.close();
