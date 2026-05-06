import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type DashboardEntry } from '../api';

/**
 * Dashboard of pinned assets + assets with a live runtime surface. One card per asset with
 * Start/Stop buttons that drive `kubectl apply --include=runtime` / `kubectl delete`. Off cards
 * collapse to a single row; the user can also hide them entirely.
 */
export function DashboardPage() {
  const [hideOff, setHideOff] = useState(false);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const q = useQuery({
    queryKey: ['dashboard-running'],
    queryFn: () => api.dashboardRunning(),
    refetchInterval: 8_000,
  });

  if (q.isLoading) return <p className="text-gray-500">Loading…</p>;
  if (q.error) return <p className="text-red-600">{(q.error as Error).message}</p>;
  const all = q.data ?? [];
  const visible = hideOff ? all.filter((e) => e.live) : all;
  const liveCount = all.filter((e) => e.live).length;
  const offCount = all.length - liveCount;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-gray-900">Running services</h1>
        <div className="flex items-center gap-3 text-xs text-gray-500">
          <span>
            {liveCount} live · {offCount} off · {all.length} total
          </span>
          <label className="flex items-center gap-1">
            <input
              type="checkbox"
              checked={hideOff}
              onChange={(e) => setHideOff(e.target.checked)}
            />
            Hide off
          </label>
        </div>
      </div>
      {all.length === 0 && (
        <section className="rounded border border-gray-200 bg-white p-6 text-sm text-gray-600">
          <p>
            No assets pinned to the dashboard yet. On any asset's detail page, click "Pin to
            dashboard" to keep it here even when stopped.
          </p>
        </section>
      )}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {visible.map((e) => (
          <AssetCard
            key={e.asset.id}
            entry={e}
            collapsed={collapsed[e.asset.id] ?? !e.live}
            onToggleCollapse={() =>
              setCollapsed((prev) => ({
                ...prev,
                [e.asset.id]: !(prev[e.asset.id] ?? !e.live),
              }))
            }
          />
        ))}
      </div>
    </div>
  );
}

function AssetCard({
  entry: e,
  collapsed,
  onToggleCollapse,
}: {
  entry: DashboardEntry;
  collapsed: boolean;
  onToggleCollapse: () => void;
}) {
  const a = e.asset;
  const qc = useQueryClient();
  const [actionMsg, setActionMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  const start = useMutation({
    mutationFn: () => api.k8sApplyComposite(a.id, []),
    onSuccess: (rs) => {
      setActionMsg({ kind: 'ok', text: `Started · ${rs.length} step(s)` });
      qc.invalidateQueries({ queryKey: ['dashboard-running'] });
    },
    onError: (err: Error) => setActionMsg({ kind: 'err', text: err.message }),
  });
  const stop = useMutation({
    mutationFn: () => api.k8sDeleteComposite(a.id, []),
    onSuccess: (rs) => {
      setActionMsg({ kind: 'ok', text: `Stopped · ${rs.length} step(s)` });
      qc.invalidateQueries({ queryKey: ['dashboard-running'] });
    },
    onError: (err: Error) => setActionMsg({ kind: 'err', text: err.message }),
  });
  const pin = useMutation({
    mutationFn: (v: boolean) => api.updateAsset(a.id, { dashboardPinned: v }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['dashboard-running'] }),
  });

  const busy = start.isPending || stop.isPending;

  return (
    <section
      className={`flex flex-col rounded-lg border bg-white shadow-sm ${
        e.live ? 'border-green-200' : 'border-gray-200'
      }`}
    >
      <header className="flex items-start gap-3 border-b border-gray-100 px-4 py-3">
        <button
          onClick={onToggleCollapse}
          className="mt-0.5 text-gray-400 hover:text-gray-700"
          title={collapsed ? 'Expand' : 'Collapse'}
        >
          {collapsed ? '▸' : '▾'}
        </button>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            {a.favorite && <span title="Favorite" style={{ color: '#dc2626' }}>♥</span>}
            <Link
              to={`/assets/${a.id}`}
              className="text-base font-semibold text-gray-900 hover:underline"
            >
              {a.name}
            </Link>
            <span
              className={`rounded px-1.5 py-0.5 text-[10px] font-medium uppercase ${
                e.live
                  ? 'bg-green-100 text-green-800'
                  : 'bg-gray-100 text-gray-600'
              }`}
            >
              {e.live ? 'live' : 'stopped'}
            </span>
          </div>
          {!collapsed && (
            <>
              <div className="mt-0.5 flex flex-wrap gap-2 text-[11px] text-gray-500">
                <span className="font-mono">{a.id}</span>
                <span>·</span>
                <span>{a.type}</span>
                {a.language && (
                  <>
                    <span>·</span>
                    <span>{a.language}</span>
                  </>
                )}
                <span>·</span>
                <span>{a.lifecycle}</span>
              </div>
              {a.description && (
                <p className="mt-1 line-clamp-2 text-xs text-gray-700">{a.description}</p>
              )}
            </>
          )}
        </div>
        <div className="flex flex-shrink-0 items-center gap-1">
          <button
            onClick={() => start.mutate()}
            disabled={busy || e.live}
            className="rounded bg-green-600 px-2 py-1 text-[11px] font-medium text-white hover:bg-green-700 disabled:bg-gray-300"
            title="kubectl apply for this asset and its runtime closure"
          >
            {start.isPending ? '…' : '▶ Start'}
          </button>
          <button
            onClick={() => stop.mutate()}
            disabled={busy || !e.live}
            className="rounded bg-red-600 px-2 py-1 text-[11px] font-medium text-white hover:bg-red-700 disabled:bg-gray-300"
            title="kubectl delete for this asset and its runtime closure"
          >
            {stop.isPending ? '…' : '■ Stop'}
          </button>
          <button
            onClick={() => pin.mutate(!a.dashboardPinned)}
            disabled={pin.isPending}
            className={`rounded border px-2 py-1 text-[11px] ${
              a.dashboardPinned
                ? 'border-yellow-400 bg-yellow-50 text-yellow-700 hover:bg-yellow-100'
                : 'border-gray-300 bg-white text-gray-600 hover:bg-gray-50'
            }`}
            title={a.dashboardPinned ? 'Unpin from dashboard' : 'Pin to dashboard'}
          >
            {a.dashboardPinned ? '★' : '☆'}
          </button>
        </div>
      </header>

      {actionMsg && (
        <div
          className={`px-4 py-1.5 text-[11px] ${
            actionMsg.kind === 'ok' ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'
          }`}
        >
          {actionMsg.text}
        </div>
      )}

      {!collapsed && (
        <div className="space-y-3 px-4 py-3">
          {e.uiEndpoints.length > 0 && (
            <Section title="Web UI">
              <div className="flex flex-wrap gap-2">
                {e.uiEndpoints.map((u) => (
                  <a
                    key={u.url}
                    href={u.url}
                    target="_blank"
                    rel="noreferrer"
                    className={`inline-flex items-center gap-1 rounded px-3 py-1.5 text-xs font-medium ${
                      u.live
                        ? 'bg-blue-600 text-white hover:bg-blue-700'
                        : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
                    }`}
                    title={u.label}
                  >
                    ↗ {u.label}
                  </a>
                ))}
              </div>
            </Section>
          )}

          {(e.apiEndpoints.length > 0 || e.swaggerUrl) && (
            <Section title="API">
              <div className="flex flex-wrap gap-2 text-xs">
                {e.apiEndpoints.map((u) => (
                  <a
                    key={u.url}
                    href={u.url}
                    target="_blank"
                    rel="noreferrer"
                    className="rounded border border-gray-300 bg-white px-2 py-1 font-mono text-gray-800 hover:bg-gray-50"
                  >
                    {u.label}
                  </a>
                ))}
                {e.swaggerUrl && (
                  <a
                    href={e.swaggerUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-1 rounded bg-amber-100 px-2 py-1 font-medium text-amber-800 hover:bg-amber-200"
                    title={e.swaggerUrl}
                  >
                    📖 Swagger / OpenAPI
                  </a>
                )}
              </div>
            </Section>
          )}

          {e.credentialFixtures.length > 0 && (
            <Section title="Credentials & seed data">
              <div className="flex flex-wrap gap-2">
                {e.credentialFixtures.map((f) => (
                  <Link
                    key={f.name}
                    to={`/assets/${a.id}`}
                    onClick={() => {
                      setTimeout(() => {
                        window.dispatchEvent(
                          new CustomEvent('devportal:goto-tab', { detail: 'fixtures' })
                        );
                      }, 50);
                    }}
                    className="rounded border border-purple-200 bg-purple-50 px-2 py-1 text-xs text-purple-800 hover:bg-purple-100"
                    title={f.description ?? f.name}
                  >
                    🔑 {f.name}
                  </Link>
                ))}
              </div>
            </Section>
          )}

          {a.tags.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {a.tags.slice(0, 8).map((t) => (
                <span
                  key={t}
                  className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-700"
                >
                  {t}
                </span>
              ))}
            </div>
          )}
        </div>
      )}

      {!collapsed && (
        <footer className="flex items-center justify-between border-t border-gray-100 px-4 py-2 text-[11px] text-gray-500">
          <Link to={`/assets/${a.id}`} className="text-blue-700 hover:underline">
            Asset details →
          </Link>
          <span>
            {e.endpoints.length} endpoint{e.endpoints.length === 1 ? '' : 's'}
          </span>
        </footer>
      )}
    </section>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 className="mb-1 text-[11px] font-medium uppercase tracking-wide text-gray-500">{title}</h3>
      {children}
    </div>
  );
}
