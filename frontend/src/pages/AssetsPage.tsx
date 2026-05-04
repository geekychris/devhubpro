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
            <AssetRow key={a.id} asset={a} />
          ))}
        </ul>
      )}
    </div>
  );
}

function AssetRow({ asset }: { asset: Asset }) {
  return (
    <li>
      <Link
        to={`/assets/${asset.id}`}
        className="block px-4 py-3 hover:bg-gray-50"
      >
        <div className="flex items-center justify-between">
          <div>
            <div className="font-medium text-gray-900">{asset.name}</div>
            <div className="text-xs text-gray-500">{asset.id}</div>
          </div>
          <div className="flex items-center gap-2">
            <Badge>{asset.type}</Badge>
            {asset.language && <Badge variant="muted">{asset.language}</Badge>}
            <Badge variant={asset.lifecycle === 'stable' ? 'good' : 'muted'}>
              {asset.lifecycle}
            </Badge>
          </div>
        </div>
      </Link>
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
