/**
 * Reads scripts/annotations.json and writes one annotated SVG per shot into ../docs/img/.
 * Each SVG <image>-references the PNG (relative href so the SVG works when embedded in markdown
 * served from docs/) and overlays numbered callouts + a legend underneath.
 */
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

type Callout = {
  /** The number shown in the marker (auto-assigned by index if omitted). */
  n?: number;
  /** Pixel coordinates in the *logical* image space (physical / 2 for deviceScaleFactor=2). */
  x: number;
  y: number;
  /** Optional bounding box [x, y, w, h] in logical px to outline the highlighted region. */
  rect?: [number, number, number, number];
  /** One-line legend entry. */
  label: string;
};
type Shot = {
  file: string;            // PNG basename in docs/img/
  title: string;           // shown as <h2> above the image
  blurb?: string;          // optional one-line subtitle below the title
  /** Logical width of the PNG in px. Used to lay out callouts. Default 1440. */
  imgW?: number;
  imgH: number;
  callouts: Callout[];
};
type Spec = { shots: Shot[] };

const HERE = fileURLToPath(new URL('.', import.meta.url));
const OUT = resolve(HERE, '..', '..', 'docs', 'img');

const LEGEND_LINE_H = 26;
const LEGEND_PAD_TOP = 24;
const LEGEND_PAD_BOTTOM = 16;
const LEGEND_PAD_X = 24;
const TITLE_H = 56;
const MARKER_R = 18;

function escapeXml(s: string): string {
  return s.replace(/[<>&"']/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' }[c]!));
}

function svgFor(shot: Shot): string {
  const imgW = shot.imgW ?? 1440;
  const imgH = shot.imgH;
  const legendH = LEGEND_PAD_TOP + LEGEND_PAD_BOTTOM + shot.callouts.length * LEGEND_LINE_H;
  const totalH = TITLE_H + imgH + legendH;
  const totalW = imgW;

  const callouts = shot.callouts.map((c, i) => ({ ...c, n: c.n ?? i + 1 }));

  const rects = callouts
    .filter((c) => c.rect)
    .map(
      (c) =>
        `<rect x="${c.rect![0]}" y="${c.rect![1]}" width="${c.rect![2]}" height="${c.rect![3]}" fill="none" stroke="#ef4444" stroke-width="3" rx="4" ry="4" />`,
    )
    .join('\n  ');

  const markers = callouts
    .map(
      (c) => `
  <circle cx="${c.x}" cy="${c.y}" r="${MARKER_R + 2}" fill="white" />
  <circle cx="${c.x}" cy="${c.y}" r="${MARKER_R}" fill="#ef4444" />
  <text x="${c.x}" y="${c.y + 6}" font-family="Inter, system-ui, sans-serif" font-size="20" font-weight="700" fill="white" text-anchor="middle">${c.n}</text>`,
    )
    .join('');

  const legend = callouts
    .map((c, i) => {
      const y = TITLE_H + imgH + LEGEND_PAD_TOP + i * LEGEND_LINE_H + 14;
      return `
  <circle cx="${LEGEND_PAD_X + 14}" cy="${y - 6}" r="13" fill="#ef4444" />
  <text x="${LEGEND_PAD_X + 14}" y="${y - 0.5}" font-family="Inter, system-ui, sans-serif" font-size="14" font-weight="700" fill="white" text-anchor="middle">${c.n}</text>
  <text x="${LEGEND_PAD_X + 38}" y="${y}" font-family="Inter, system-ui, sans-serif" font-size="15" fill="#1f2937">${escapeXml(c.label)}</text>`;
    })
    .join('');

  const blurb = shot.blurb
    ? `<text x="${LEGEND_PAD_X}" y="46" font-family="Inter, system-ui, sans-serif" font-size="14" fill="#6b7280">${escapeXml(shot.blurb)}</text>`
    : '';

  return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${totalW}" height="${totalH}" viewBox="0 0 ${totalW} ${totalH}">
  <rect width="100%" height="100%" fill="#ffffff" />
  <text x="${LEGEND_PAD_X}" y="28" font-family="Inter, system-ui, sans-serif" font-size="20" font-weight="600" fill="#0f172a">${escapeXml(shot.title)}</text>
  ${blurb}
  <image href="${shot.file}" x="0" y="${TITLE_H}" width="${imgW}" height="${imgH}" preserveAspectRatio="xMidYMin meet" />
  <g transform="translate(0, ${TITLE_H})">
  ${rects}
  ${markers}
  </g>
  <rect x="0" y="${TITLE_H + imgH}" width="${totalW}" height="${legendH}" fill="#f9fafb" stroke="#e5e7eb" />
  ${legend}
</svg>
`;
}

async function main() {
  const specPath = resolve(HERE, 'annotations.json');
  const spec: Spec = JSON.parse(await readFile(specPath, 'utf8'));
  for (const shot of spec.shots) {
    const svg = svgFor(shot);
    const outName = shot.file.replace(/\.png$/, '.svg');
    const outPath = resolve(OUT, outName);
    await writeFile(outPath, svg, 'utf8');
    console.log(`wrote ${outName}  (${shot.callouts.length} callouts)`);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
