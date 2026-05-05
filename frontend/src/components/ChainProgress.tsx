import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, type BuildProgress } from '../api';

const STATUS_PILL: Record<BuildProgress['status'], string> = {
  queued: 'bg-gray-200 text-gray-700',
  running: 'bg-blue-100 text-blue-800',
  succeeded: 'bg-green-100 text-green-800',
  failed: 'bg-red-100 text-red-800',
  cancelled: 'bg-amber-100 text-amber-800',
};

/**
 * Live view of a build chain (parent + children). Polls /api/builds/{id}/progress every 2s
 * while the parent is in a non-terminal state. Designed for image-build chains and deep build
 * chains alike.
 */
export function ChainProgress({ buildId, title }: { buildId: number; title?: string }) {
  const q = useQuery({
    queryKey: ['build-progress', buildId],
    queryFn: () => api.getBuildProgress(buildId),
    refetchInterval: (query) => {
      const s = query.state.data?.status;
      if (s === 'running' || s === 'queued') return 2_000;
      return false;
    },
  });

  if (q.isLoading) return <p className="text-xs text-gray-500">Loading progress…</p>;
  if (q.error)
    return <p className="text-xs text-red-600">{(q.error as Error).message}</p>;
  if (!q.data) return null;
  const p = q.data;
  const showPct = p.summary.total > 0;
  const pct = showPct
    ? Math.round(((p.summary.succeeded + p.summary.failed + p.summary.skipped) / p.summary.total) * 100)
    : 0;

  return (
    <div className="rounded border border-gray-200 bg-white p-3 text-sm">
      <header className="mb-2 flex items-center gap-2">
        <span className={`rounded px-2 py-0.5 text-xs font-medium ${STATUS_PILL[p.status]}`}>
          {p.status}
        </span>
        <span className="font-mono text-xs text-gray-700">
          #{p.buildId} {title ?? p.commandName}
        </span>
        {p.durationMs != null && (
          <span className="text-xs text-gray-500">{(p.durationMs / 1000).toFixed(1)}s</span>
        )}
        <span className="ml-auto text-xs text-gray-600">
          {p.summary.succeeded} ok · {p.summary.failed} failed · {p.summary.skipped} skipped ·{' '}
          {p.summary.running + p.summary.queued} pending
          {showPct && <span className="ml-2 font-medium text-gray-800">{pct}%</span>}
        </span>
      </header>

      {p.children.length === 0 && p.status !== 'queued' && (
        <p className="text-xs text-gray-500">No child builds yet.</p>
      )}

      <ul className="space-y-1">
        {p.children.map((c) => (
          <ChildRow key={c.id} child={c} />
        ))}
      </ul>

      {p.summaryText && (p.status === 'succeeded' || p.status === 'failed') && (
        <details className="mt-2 text-xs">
          <summary className="cursor-pointer text-gray-600 hover:underline">Show parent log</summary>
          <pre className="mt-1 max-h-48 overflow-auto rounded bg-gray-50 p-2 font-mono text-[11px]">
            {p.summaryText}
          </pre>
        </details>
      )}
    </div>
  );
}

function ChildRow({ child }: { child: BuildProgress['children'][number] }) {
  const [expanded, setExpanded] = useState(false);
  const dur = child.durationMs == null ? '—' : `${(child.durationMs / 1000).toFixed(1)}s`;
  return (
    <li className="rounded border border-gray-100 bg-white text-xs">
      <div className="flex flex-wrap items-center gap-2 px-2 py-1">
        <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${STATUS_PILL[child.status]}`}>
          {child.status}
        </span>
        <span className="font-mono text-gray-800">{child.label}</span>
        <span className="text-gray-500">#{child.id}</span>
        <span className="text-gray-500">{dur}</span>
        <button
          onClick={() => setExpanded((v) => !v)}
          className="ml-auto text-blue-700 hover:underline"
          title="Show last log lines"
        >
          {expanded ? 'Hide log' : 'Show log'}
        </button>
      </div>
      {child.errorHint && (
        <div className="border-t border-red-100 bg-red-50 px-2 py-1 font-mono text-[11px] text-red-800">
          ↪ {child.errorHint}
        </div>
      )}
      {expanded && child.tailLines.length > 0 && (
        <pre className="max-h-48 overflow-auto rounded-b bg-gray-50 px-2 py-1 font-mono text-[11px]">
          {child.tailLines.join('\n')}
        </pre>
      )}
    </li>
  );
}
