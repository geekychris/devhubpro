import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';

export function MetaAssetsPage() {
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ['meta-assets'], queryFn: () => api.listMetaAssets() });

  const [showForm, setShowForm] = useState(false);
  const [id, setId] = useState('');
  const [name, setName] = useState('');
  const [kind, setKind] = useState('postgres');
  const [configText, setConfigText] = useState('{}');

  const create = useMutation({
    mutationFn: () => {
      let config: Record<string, unknown> = {};
      try {
        config = configText ? JSON.parse(configText) : {};
      } catch {
        throw new Error('config must be valid JSON');
      }
      return api.createMetaAsset({ id, name, kind, config });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['meta-assets'] });
      setShowForm(false);
      setId('');
      setName('');
      setKind('postgres');
      setConfigText('{}');
    },
  });

  const remove = useMutation({
    mutationFn: (mid: string) => api.deleteMetaAsset(mid),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['meta-assets'] }),
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-gray-900">Meta-assets</h1>
        <button
          onClick={() => setShowForm((v) => !v)}
          className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
        >
          {showForm ? 'Cancel' : 'New meta-asset'}
        </button>
      </div>

      {showForm && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            create.mutate();
          }}
          className="space-y-3 rounded border border-gray-200 bg-white p-4 text-sm"
        >
          <div className="grid grid-cols-2 gap-3">
            <label>
              <span className="block text-xs font-medium text-gray-700">Id (slug)</span>
              <input
                value={id}
                onChange={(e) => setId(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-1.5"
                required
              />
            </label>
            <label>
              <span className="block text-xs font-medium text-gray-700">Name</span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-1.5"
                required
              />
            </label>
            <label>
              <span className="block text-xs font-medium text-gray-700">Kind</span>
              <select
                value={kind}
                onChange={(e) => setKind(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-1.5"
              >
                <option value="postgres">postgres</option>
                <option value="redis">redis</option>
                <option value="memcache">memcache</option>
                <option value="opensearch">opensearch</option>
                <option value="minio">minio</option>
                <option value="other">other</option>
              </select>
            </label>
          </div>
          <label>
            <span className="block text-xs font-medium text-gray-700">Config (JSON)</span>
            <textarea
              value={configText}
              onChange={(e) => setConfigText(e.target.value)}
              rows={4}
              className="w-full rounded border border-gray-300 px-3 py-1.5 font-mono text-xs"
            />
          </label>
          {create.error && <p className="text-sm text-red-600">{(create.error as Error).message}</p>}
          <button
            type="submit"
            className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
            disabled={create.isPending}
          >
            {create.isPending ? 'Creating…' : 'Create'}
          </button>
        </form>
      )}

      {list.data && list.data.length === 0 && (
        <p className="text-sm text-gray-500">No meta-assets yet.</p>
      )}
      {list.data && list.data.length > 0 && (
        <ul className="divide-y divide-gray-200 rounded border border-gray-200 bg-white">
          {list.data.map((m) => (
            <li key={m.id} className="flex items-center justify-between px-4 py-3 text-sm">
              <div>
                <div className="font-medium text-gray-900">{m.name}</div>
                <div className="text-xs text-gray-500">
                  {m.id} · {m.kind} {m.provisionedByPortal ? '· portal-provisioned' : ''}
                </div>
              </div>
              <button
                onClick={() => {
                  if (confirm(`Delete meta-asset ${m.id}?`)) remove.mutate(m.id);
                }}
                className="text-xs text-red-600 hover:underline"
              >
                delete
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
