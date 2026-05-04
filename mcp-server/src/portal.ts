/**
 * Thin REST client wrapping the dev_portal backend.
 * The backend URL defaults to http://localhost:8081 — override with DEVPORTAL_URL env var.
 */
const BASE = process.env.DEVPORTAL_URL ?? 'http://localhost:8081';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText} on ${path}: ${text}`);
  }
  if (res.status === 204) return undefined as T;
  const ctype = res.headers.get('content-type') ?? '';
  if (ctype.startsWith('application/json')) return (await res.json()) as T;
  return (await res.text()) as unknown as T;
}

export const portal = {
  health: () => request<{ status: string; service: string; time: string }>('/api/health'),

  listAssets: (q?: string, type?: string, lifecycle?: string) => {
    const params = new URLSearchParams();
    if (q) params.set('q', q);
    if (type) params.set('type', type);
    if (lifecycle) params.set('lifecycle', lifecycle);
    return request<unknown[]>(`/api/assets${params.toString() ? `?${params}` : ''}`);
  },
  getAsset: (id: string) => request(`/api/assets/${id}`),
  registerFromGitHub: (fullName: string, overrideId?: string) =>
    request('/api/assets/from-github', {
      method: 'POST',
      body: JSON.stringify({ fullName, overrideId }),
    }),
  deleteAsset: (id: string) => request(`/api/assets/${id}`, { method: 'DELETE' }),

  addDependency: (consumerId: string, producerId: string, versionRef = 'main', kind = 'build') =>
    request(`/api/assets/${consumerId}/dependencies`, {
      method: 'POST',
      body: JSON.stringify({ producerId, versionRef, kind }),
    }),
  getGraph: (id: string) => request(`/api/assets/${id}/graph`),

  kickBuild: (
    assetId: string,
    body: { mode?: string; commandName?: string; commandLine?: string; ref?: string } = {}
  ) =>
    request(`/api/assets/${assetId}/builds`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  listBuilds: (assetId: string) => request(`/api/assets/${assetId}/builds`),
  getBuild: (id: number) => request(`/api/builds/${id}`),
  getBuildLog: (id: number) => request<string>(`/api/builds/${id}/log`),

  allocatePorts: (assetId: string, scope = 'local', reallocate = false) =>
    request(`/api/assets/${assetId}/ports/allocate?scope=${scope}&reallocate=${reallocate}`, {
      method: 'POST',
    }),
  listPorts: (assetId: string) => request(`/api/assets/${assetId}/ports`),

  listMetaAssets: () => request('/api/meta-assets'),
  createMetaAsset: (body: {
    id: string;
    name: string;
    kind: string;
    config?: Record<string, unknown>;
    provisionedByPortal?: boolean;
  }) => request('/api/meta-assets', { method: 'POST', body: JSON.stringify(body) }),
  attachConsumes: (assetId: string, metaAssetId: string, role?: string) =>
    request(`/api/assets/${assetId}/consumes`, {
      method: 'POST',
      body: JSON.stringify({ metaAssetId, role }),
    }),

  audit: (assetId: string) => request(`/api/assets/${assetId}/audit`),

  stateExport: () => request('/api/state/export', { method: 'POST' }),
  stateGitSync: (message: string) =>
    request(`/api/state/git-sync?message=${encodeURIComponent(message)}`, { method: 'POST' }),
};
