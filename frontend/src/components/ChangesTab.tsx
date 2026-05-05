import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type WorkspaceFileChange } from '../api';

const STATUS_COLOR: Record<WorkspaceFileChange['status'], string> = {
  modified: 'bg-amber-100 text-amber-800',
  added: 'bg-green-100 text-green-800',
  deleted: 'bg-red-100 text-red-800',
  untracked: 'bg-blue-100 text-blue-800',
  missing: 'bg-red-100 text-red-800',
  conflicting: 'bg-purple-100 text-purple-800',
};

export function ChangesTab({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const status = useQuery({
    queryKey: ['workspace-status', assetId],
    queryFn: () => api.getWorkspaceStatus(assetId),
    refetchInterval: 8_000,
  });

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [openDiff, setOpenDiff] = useState<string | null>(null);
  const defaultBranch = useMemo(
    () => `devportal/fix-${assetId}-${Math.floor(Date.now() / 1000)}`,
    [assetId]
  );
  const [branch, setBranch] = useState(defaultBranch);
  const [message, setMessage] = useState('');
  const [push, setPush] = useState(false);

  const diff = useQuery({
    queryKey: ['workspace-diff', assetId, openDiff],
    queryFn: () => api.getWorkspaceDiff(assetId, openDiff!),
    enabled: !!openDiff,
  });

  const commit = useMutation({
    mutationFn: () =>
      api.commitWorkspace(assetId, {
        branch,
        message: message || `devportal: workspace edit on ${assetId}`,
        paths: Array.from(selected),
        push,
      }),
    onSuccess: () => {
      setSelected(new Set());
      setMessage('');
      queryClient.invalidateQueries({ queryKey: ['workspace-status', assetId] });
    },
  });

  const push_ = useMutation({
    mutationFn: () => api.pushWorkspace(assetId, branch),
  });

  if (status.isLoading) return <p className="text-gray-500">Loading workspace status…</p>;
  if (status.error)
    return <p className="text-red-600">{(status.error as Error).message}</p>;
  if (!status.data) return null;
  const s = status.data;
  const branchLooksProtected = branch === 'main' || branch === 'master';

  const toggle = (p: string) => {
    setSelected((prev) => {
      const n = new Set(prev);
      n.has(p) ? n.delete(p) : n.add(p);
      return n;
    });
  };

  return (
    <div className="space-y-4">
      <section className="rounded border border-gray-200 bg-white p-4 text-sm">
        <div className="flex flex-wrap items-center gap-x-6 gap-y-1">
          <span>
            Branch: <code className="font-mono text-gray-900">{s.branch}</code>
          </span>
          {s.head && (
            <span className="text-gray-500">
              HEAD <code className="font-mono">{s.head.slice(0, 8)}</code>
            </span>
          )}
          {s.aheadCount >= 0 && (
            <span className="text-gray-500">
              {s.aheadCount} ahead / {s.behindCount} behind origin
            </span>
          )}
          {s.aheadCount < 0 && (
            <span className="text-gray-400">no upstream on origin</span>
          )}
          <span className={s.clean ? 'text-green-700' : 'text-amber-700'}>
            {s.clean ? 'clean' : `${s.files.length} change${s.files.length === 1 ? '' : 's'}`}
          </span>
        </div>
      </section>

      <section className="rounded border border-gray-200 bg-white">
        <div className="flex items-center justify-between border-b border-gray-200 px-4 py-2 text-sm">
          <h2 className="font-medium text-gray-900">Files</h2>
          {s.files.length > 0 && (
            <div className="flex gap-3 text-xs">
              <button
                onClick={() => setSelected(new Set(s.files.map((f) => f.path)))}
                className="text-blue-700 hover:underline"
              >
                Select all
              </button>
              <button
                onClick={() => setSelected(new Set())}
                className="text-gray-600 hover:underline"
              >
                Clear
              </button>
            </div>
          )}
        </div>
        {s.files.length === 0 ? (
          <p className="px-4 py-3 text-sm text-gray-500">No uncommitted changes.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {s.files.map((f) => (
              <li key={f.path} className="px-4 py-2 text-sm">
                <div className="flex items-center gap-3">
                  <input
                    type="checkbox"
                    checked={selected.has(f.path)}
                    onChange={() => toggle(f.path)}
                    className="rounded border-gray-300"
                  />
                  <span
                    className={`rounded px-1.5 py-0.5 text-xs font-medium ${STATUS_COLOR[f.status]}`}
                  >
                    {f.status}
                  </span>
                  <span className="font-mono text-xs text-gray-800">{f.path}</span>
                  <button
                    onClick={() => setOpenDiff(openDiff === f.path ? null : f.path)}
                    className="ml-auto text-xs text-blue-700 hover:underline"
                  >
                    {openDiff === f.path ? 'Hide diff' : 'View diff'}
                  </button>
                </div>
                {openDiff === f.path && (
                  <pre className="mt-2 max-h-96 overflow-auto rounded bg-gray-50 p-2 font-mono text-xs leading-tight">
                    {diff.isLoading ? 'Loading…' : diff.data || '(no diff — file may be untracked or binary)'}
                  </pre>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="rounded border border-gray-200 bg-white p-4 text-sm space-y-3">
        <h2 className="font-medium text-gray-900">Commit</h2>
        <label className="block space-y-1">
          <span className="block text-xs font-medium text-gray-700">Branch</span>
          <input
            type="text"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            className={`w-full rounded border px-3 py-1.5 text-sm font-mono ${
              branchLooksProtected ? 'border-red-400' : 'border-gray-300'
            }`}
          />
          {branchLooksProtected && (
            <span className="block text-xs text-red-600">
              Refusing to commit to {branch}. Use a side branch.
            </span>
          )}
        </label>
        <label className="block space-y-1">
          <span className="block text-xs font-medium text-gray-700">Message</span>
          <textarea
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            rows={3}
            placeholder={`devportal: workspace edit on ${assetId}`}
            className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
          />
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={push}
            onChange={(e) => setPush(e.target.checked)}
            className="rounded border-gray-300"
          />
          <span>Push to <code className="font-mono">origin/{branch || '…'}</code> after commit</span>
        </label>
        <div className="flex items-center gap-2">
          <button
            disabled={selected.size === 0 || branchLooksProtected || commit.isPending}
            onClick={() => commit.mutate()}
            className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:bg-gray-400"
          >
            {commit.isPending ? 'Committing…' : `Commit ${selected.size} file${selected.size === 1 ? '' : 's'}`}
          </button>
          <button
            disabled={branchLooksProtected || push_.isPending}
            onClick={() => push_.mutate()}
            className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-100 disabled:opacity-50"
            title="Push the branch as-is, no new commit"
          >
            {push_.isPending ? 'Pushing…' : 'Push branch'}
          </button>
        </div>
        {commit.error && (
          <p className="text-sm text-red-600">{(commit.error as Error).message}</p>
        )}
        {commit.data && (
          <div className="rounded border border-green-200 bg-green-50 px-3 py-2 text-sm">
            <div>
              Committed <code className="font-mono">{commit.data.commit?.slice(0, 8)}</code> to{' '}
              <code className="font-mono">{commit.data.branch}</code>
              {commit.data.pushed && ' and pushed to origin'}.
            </div>
            <div className="mt-1 text-xs text-gray-600">
              {commit.data.filesChanged.length} file{commit.data.filesChanged.length === 1 ? '' : 's'} staged.
            </div>
            {commit.data.prSuggestion && (
              <a
                href={commit.data.prSuggestion}
                target="_blank"
                rel="noreferrer"
                className="text-blue-700 hover:underline"
              >
                Open PR on GitHub
              </a>
            )}
          </div>
        )}
        {push_.data && (
          <div className="rounded border border-green-200 bg-green-50 px-3 py-2 text-sm">
            Pushed <code className="font-mono">{push_.data.branch}</code> to origin.
          </div>
        )}
        {push_.error && (
          <p className="text-sm text-red-600">{(push_.error as Error).message}</p>
        )}
      </section>
    </div>
  );
}
