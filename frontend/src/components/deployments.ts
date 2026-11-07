import type { Deployment, DeploymentStatus, DeploymentUrl } from '../api';

export const STATUS_ORDER: DeploymentStatus[] = ['down', 'degraded', 'unknown', 'reported', 'healthy', 'retired'];

export const STATUS_STYLE: Record<DeploymentStatus, { dot: string; text: string; label: string; hint: string }> = {
  healthy: { dot: 'bg-green-500', text: 'text-green-700', label: 'healthy', hint: 'The last health check answered' },
  degraded: { dot: 'bg-amber-500', text: 'text-amber-700', label: 'degraded', hint: 'It answered with an error status' },
  down: { dot: 'bg-red-500', text: 'text-red-700', label: 'down', hint: 'The last health check got no answer (or a 502/503/504)' },
  unknown: { dot: 'bg-gray-400', text: 'text-gray-600', label: 'unknown', hint: 'Not checked yet' },
  reported: { dot: 'bg-sky-400', text: 'text-sky-700', label: 'reported', hint: 'Reported, with nothing to health-check' },
  retired: { dot: 'bg-gray-300', text: 'text-gray-400', label: 'retired', hint: 'No longer reported' },
};

export const TARGET_KIND_LABEL: Record<string, string> = {
  'k8s-cluster': 'Kubernetes',
  host: 'host',
  faas: 'FaaS',
  docker: 'docker',
  mac: 'Mac',
  other: '',
};

/** Group deployments by target, targets in name order, rows by asset then instance. */
export function groupByTarget(ds: Deployment[]): { target: string; kind: string; rows: Deployment[] }[] {
  const m = new Map<string, Deployment[]>();
  for (const d of ds) {
    const list = m.get(d.target) ?? [];
    list.push(d);
    m.set(d.target, list);
  }
  return [...m.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([target, rows]) => ({
      target,
      kind: mostCommon(rows.map((r) => r.targetKind)),
      rows: rows.sort(
        (a, b) =>
          a.assetName.localeCompare(b.assetName) ||
          a.namespace.localeCompare(b.namespace) ||
          a.name.localeCompare(b.name)
      ),
    }));
}

function mostCommon(xs: string[]): string {
  const c = new Map<string, number>();
  for (const x of xs) c.set(x, (c.get(x) ?? 0) + 1);
  let best = '';
  let n = -1;
  for (const [k, v] of c) if (v > n) [best, n] = [k, v];
  return best;
}

/** Primary URL first, then the rest in reported order. */
export function orderedUrls(urls: DeploymentUrl[]): DeploymentUrl[] {
  return [...urls.filter((u) => u.primary), ...urls.filter((u) => !u.primary)];
}

export function relTime(iso: string | null, now = Date.now()): string {
  if (!iso) return '—';
  const s = Math.round((now - new Date(iso).getTime()) / 1000);
  if (s < 0) return 'just now';
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}

export function versionText(d: Deployment): string {
  const parts = [d.version, d.ref, d.commit ? d.commit.slice(0, 10) : null].filter(Boolean);
  return parts.length ? parts.join(' · ') : '—';
}
