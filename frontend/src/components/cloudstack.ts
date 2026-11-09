import { useQuery } from '@tanstack/react-query';
import { api, type CloudStackActivity, type CloudStackReasonCode, type CloudStackRun } from '../api';

/** One query for the tab and the header button (the backend caches cloud-stack answers ~30 s). */
export function useAssetCloudStack(assetId: string, refetch = false) {
  return useQuery<CloudStackActivity>({
    queryKey: ['asset-cloudstack', assetId],
    queryFn: () => api.getAssetCloudStack(assetId),
    enabled: !!assetId,
    staleTime: 30_000,
    refetchInterval: refetch ? 30_000 : false,
    retry: false,
  });
}

type Light = { dot: string; text: string; label: string };

const GREEN = { dot: 'bg-green-500', text: 'text-green-700' };
const RED = { dot: 'bg-red-500', text: 'text-red-700' };
const AMBER = { dot: 'bg-amber-500', text: 'text-amber-700' };
const SKY = { dot: 'bg-sky-400', text: 'text-sky-700' };
const GRAY = { dot: 'bg-gray-300', text: 'text-gray-500' };

/** PR check lights: passing | failing | pending | none. */
export function checksLight(checks: string | null | undefined): Light {
  const c = (checks ?? 'none').toLowerCase();
  if (c === 'passing') return { ...GREEN, label: 'checks passing' };
  if (c === 'failing') return { ...RED, label: 'checks failing' };
  if (c === 'pending') return { ...AMBER, label: 'checks pending' };
  if (c === 'none') return { ...GRAY, label: 'no checks' };
  return { ...GRAY, label: c };
}

/** Jenkins lights: succeeded | failed | running | queued | none. */
export function jenkinsLight(status: string | null | undefined): Light {
  const s = (status ?? 'none').toLowerCase();
  if (s === 'succeeded') return { ...GREEN, label: 'succeeded' };
  if (s === 'failed') return { ...RED, label: 'failed' };
  if (s === 'running') return { ...SKY, label: 'running' };
  if (s === 'queued') return { ...AMBER, label: 'queued' };
  if (s === 'none') return { ...GRAY, label: 'no build' };
  return { ...GRAY, label: s };
}

const TERMINAL = new Set(['succeeded', 'success', 'completed', 'complete', 'done', 'failed', 'failure', 'error',
  'cancelled', 'canceled', 'rejected', 'aborted', 'delivered', 'merged', 'closed']);

export function isActiveRun(r: CloudStackRun): boolean {
  return !TERMINAL.has((r.state ?? '').toLowerCase());
}

export function runLight(state: string | null | undefined): Light {
  const s = (state ?? '').toLowerCase();
  const label = s || 'unknown';
  if (['succeeded', 'success', 'completed', 'complete', 'done', 'delivered', 'merged'].includes(s)) return { ...GREEN, label };
  if (['failed', 'failure', 'error', 'aborted'].includes(s)) return { ...RED, label };
  if (['cancelled', 'canceled', 'rejected', 'closed'].includes(s)) return { ...GRAY, label };
  if (s.includes('wait') || s.includes('approval') || s.includes('blocked') || s === 'queued' || s === 'pending') {
    return { ...AMBER, label };
  }
  return { ...SKY, label };
}

/** Short explanation for the panel when cloud-stack activity is unavailable. */
export const REASON_TITLE: Record<CloudStackReasonCode, string> = {
  'not-configured': 'cloud-stack is not configured',
  'no-github-repo': 'This repo is not on GitHub',
  unreachable: 'cloud-stack is unreachable',
  unauthorized: 'cloud-stack refused the request',
  'bad-response': 'cloud-stack sent an unexpected answer',
};

/** Only http(s) links are rendered (the backend filters too). */
export function httpUrl(u: string | null | undefined): string | undefined {
  return u && /^https?:\/\//i.test(u.trim()) ? u.trim() : undefined;
}
