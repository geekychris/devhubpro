import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api';

export function ProxyRoutesPage() {
  const q = useQuery({
    queryKey: ['proxy-routes'],
    queryFn: () => api.listProxyRoutes(),
    refetchInterval: 5_000,
  });
  const [filter, setFilter] = useState('');

  const routes = q.data?.routes ?? [];
  const conflicts = q.data?.conflicts ?? [];

  const filtered = routes.filter((r) => {
    if (!filter.trim()) return true;
    const s = filter.toLowerCase();
    return (
      r.assetId.toLowerCase().includes(s) ||
      r.path.toLowerCase().includes(s) ||
      r.portSlot.toLowerCase().includes(s) ||
      (r.host ?? '').toLowerCase().includes(s)
    );
  });
  filtered.sort((a, b) => a.path.localeCompare(b.path));

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold text-gray-900">Proxy routes</h1>
        <p className="text-sm text-gray-500">
          Paths exposed through the shared Traefik ingress. Each row is one asset's
          {' '}<code>spec.runtime.proxy</code>{' '}block — so the cluster ingress port can be mapped to
          your router and every service is reachable as <code>http://&lt;your-host&gt;/&lt;path&gt;</code>.
          {' '}Routes are <em>declared</em> when present in the manifest and{' '}
          <em>applied</em> when a portal-managed Ingress exists in the cluster.
        </p>
      </div>

      {conflicts.length > 0 && (
        <section className="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-900">
          <h2 className="font-medium">Route conflicts</h2>
          <p className="mb-2 text-xs">
            More than one asset declares the same (host, path) pair. Apply will fail for the
            second one until the conflict is resolved.
          </p>
          <ul className="space-y-1 text-xs">
            {conflicts.map((c) => (
              <li key={`${c.host}|${c.path}`}>
                <code className="font-mono">
                  {c.host && c.host !== '*' ? c.host : '*'}{c.path}
                </code>{' '}
                — claimed by{' '}
                {c.assetIds.map((id, i) => (
                  <span key={id}>
                    {i > 0 ? ', ' : ''}
                    <Link to={`/assets/${id}`} className="underline">{id}</Link>
                  </span>
                ))}
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="flex flex-wrap items-center gap-3 rounded border border-gray-200 bg-white p-3">
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="filter by asset / path / host"
          className="flex-1 min-w-[18rem] rounded border border-gray-300 px-3 py-1.5 text-sm"
        />
        <span className="ml-auto text-xs text-gray-500">
          {routes.length} declared · {routes.filter((r) => r.applied).length} applied
        </span>
      </div>

      {q.error && <p className="text-sm text-red-600">{(q.error as Error).message}</p>}

      {!q.isLoading && filtered.length === 0 && (
        <p className="text-sm text-gray-500">
          No proxy routes {filter ? 'match the filter' : 'declared yet'}. Add{' '}
          <code>spec.runtime.proxy</code> to an asset's <code>devportal.yaml</code> to expose it
          under the shared ingress.
        </p>
      )}

      {filtered.length > 0 && (
        <section className="rounded border border-gray-200 bg-white">
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase text-gray-500">
              <tr>
                <th className="px-4 py-2">Status</th>
                <th className="px-4 py-2">Path</th>
                <th className="px-4 py-2">Asset</th>
                <th className="px-4 py-2">Port slot</th>
                <th className="px-4 py-2">Strip</th>
                <th className="px-4 py-2">Host</th>
                <th className="px-4 py-2">Local URL</th>
                <th className="px-4 py-2"></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.assetId + r.path} className="border-t border-gray-100">
                  <td className="px-4 py-2"><StatusPill applied={r.applied} /></td>
                  <td className="px-4 py-2 font-mono">{r.path}</td>
                  <td className="px-4 py-2">
                    <Link to={`/assets/${r.assetId}`} className="text-blue-700 hover:underline">
                      {r.assetId}
                    </Link>
                  </td>
                  <td className="px-4 py-2 font-mono text-xs">{r.portSlot}</td>
                  <td className="px-4 py-2 text-xs">{r.stripPrefix ? 'yes' : 'no'}</td>
                  <td className="px-4 py-2 text-xs">
                    {r.host ?? <span className="text-gray-400">any</span>}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs">
                    <a href={r.url} target="_blank" rel="noreferrer" className="text-blue-700 hover:underline">
                      {r.url}
                    </a>
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      onClick={() => navigator.clipboard.writeText(r.url)}
                      className="text-gray-400 hover:text-gray-700"
                      title="Copy URL"
                    >
                      ⧉
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}

function StatusPill({ applied }: { applied: boolean }) {
  return applied ? (
    <span className="rounded bg-green-100 px-1.5 py-0.5 text-xs text-green-800" title="ingress is live in the cluster">
      applied
    </span>
  ) : (
    <span className="rounded bg-gray-200 px-1.5 py-0.5 text-xs text-gray-700" title="declared in manifest, not yet applied to the cluster">
      declared
    </span>
  );
}
