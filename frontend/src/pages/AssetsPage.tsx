import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api, type Asset } from '../api';

export function AssetsPage() {
  const [q, setQ] = useState('');
  const [type, setType] = useState('');
  const { data, isLoading, error } = useQuery({
    queryKey: ['assets', q, type],
    queryFn: () => api.listAssets(q || undefined, type || undefined),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-gray-900">Assets</h1>
        <Link
          to="/register"
          className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
        >
          Register asset
        </Link>
      </div>

      <div className="flex gap-3">
        <input
          type="search"
          placeholder="Search by id, name, or description"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="flex-1 rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none"
        />
        <select
          value={type}
          onChange={(e) => setType(e.target.value)}
          className="rounded border border-gray-300 px-3 py-1.5 text-sm"
        >
          <option value="">All types</option>
          <option value="library">Library</option>
          <option value="service">Service</option>
          <option value="meta-asset">Meta-asset</option>
        </select>
      </div>

      {isLoading && <p className="text-gray-500">Loading…</p>}
      {error && <p className="text-red-600">{(error as Error).message}</p>}
      {data && data.length === 0 && (
        <p className="text-gray-500">No assets yet. Register one.</p>
      )}
      {data && data.length > 0 && (
        <ul className="divide-y divide-gray-200 rounded border border-gray-200 bg-white">
          {data.map((a) => (
            <AssetRow key={a.id} asset={a} depth={0} ancestors={new Set([a.id])} />
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * One row in the assets list. Recursively renders children when expanded by fetching the
 * 1-hop producer graph for this asset. {@code ancestors} prevents cycles in lazy expansion.
 */
function AssetRow({
  asset,
  depth,
  ancestors,
}: {
  asset: Asset;
  depth: number;
  ancestors: Set<string>;
}) {
  const [expanded, setExpanded] = useState(false);
  const graph = useQuery({
    queryKey: ['asset-children', asset.id],
    queryFn: () =>
      api.getGraph(asset.id, { direction: 'producers', producerDepth: 1, consumerDepth: 0 }),
    enabled: expanded,
    staleTime: 30_000,
  });

  // Producers (children) = nodes other than the root.
  const children = graph.data?.nodes.filter((n) => n.id !== asset.id) ?? [];
  const hasChildren = expanded ? children.length > 0 : null; // null = unknown until expanded

  return (
    <li>
      <div
        className="group flex items-start px-4 py-3 hover:bg-gray-50"
        style={{ paddingLeft: 16 + depth * 24 }}
      >
        <button
          aria-label={expanded ? 'Collapse' : 'Expand'}
          onClick={() => setExpanded((v) => !v)}
          className="mt-1 mr-2 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded text-gray-400 hover:bg-gray-200 hover:text-gray-700"
          title="Show producers (what this asset depends on)"
        >
          <span className={`transition-transform ${expanded ? 'rotate-90' : ''}`}>▸</span>
        </button>

        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-3">
            <div className="min-w-0">
              <Link
                to={`/assets/${asset.id}`}
                className="font-medium text-gray-900 hover:underline"
              >
                {asset.name}
              </Link>
              <span className="ml-2 text-xs text-gray-500">{asset.id}</span>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <Badge>{asset.type}</Badge>
              {asset.language && <Badge variant="muted">{asset.language}</Badge>}
              <Badge variant={asset.lifecycle === 'stable' ? 'good' : 'muted'}>
                {asset.lifecycle}
              </Badge>
            </div>
          </div>

          {asset.description && (
            <p className="mt-1 line-clamp-2 text-xs text-gray-600">
              {asset.description}
            </p>
          )}

          {asset.tags.length > 0 && (
            <div className="mt-1 flex flex-wrap gap-1">
              {asset.tags.map((t) => (
                <span
                  key={t}
                  className="rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-700"
                >
                  {t}
                </span>
              ))}
            </div>
          )}
        </div>
      </div>

      {expanded && (
        <div className="border-t border-gray-100 bg-gray-50">
          {graph.isLoading && (
            <p className="px-4 py-2 text-xs text-gray-500" style={{ paddingLeft: 16 + (depth + 1) * 24 }}>
              Loading dependencies…
            </p>
          )}
          {graph.error && (
            <p className="px-4 py-2 text-xs text-red-600" style={{ paddingLeft: 16 + (depth + 1) * 24 }}>
              {(graph.error as Error).message}
            </p>
          )}
          {hasChildren === false && (
            <p className="px-4 py-2 text-xs text-gray-500" style={{ paddingLeft: 16 + (depth + 1) * 24 }}>
              No dependencies.
            </p>
          )}
          {children.length > 0 && (
            <ul className="divide-y divide-gray-100">
              {children.map((child) =>
                ancestors.has(child.id) ? (
                  <li
                    key={child.id}
                    className="px-4 py-2 text-xs text-amber-700"
                    style={{ paddingLeft: 16 + (depth + 1) * 24 }}
                  >
                    ↻ <span className="font-mono">{child.id}</span> (cycle)
                  </li>
                ) : (
                  <AssetRow
                    key={child.id}
                    asset={child}
                    depth={depth + 1}
                    ancestors={new Set([...ancestors, child.id])}
                  />
                )
              )}
            </ul>
          )}
        </div>
      )}
    </li>
  );
}

function Badge({
  children,
  variant = 'default',
}: {
  children: React.ReactNode;
  variant?: 'default' | 'good' | 'muted';
}) {
  const cls =
    variant === 'good'
      ? 'bg-green-100 text-green-800'
      : variant === 'muted'
      ? 'bg-gray-100 text-gray-700'
      : 'bg-blue-100 text-blue-800';
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${cls}`}>
      {children}
    </span>
  );
}
