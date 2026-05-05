import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, type K8sDiagnostics } from '../api';

const SEV_COLOR: Record<K8sDiagnostics['findings'][number]['severity'], string> = {
  error: 'bg-red-100 text-red-800 border-red-200',
  warn: 'bg-amber-100 text-amber-800 border-amber-200',
  info: 'bg-blue-100 text-blue-800 border-blue-200',
};

/**
 * K8s diagnostics panel: live punch list of issues from the diagnostics endpoint. Auto-refresh
 * every 8s. Findings are grouped by severity. Each row carries a one-sentence hint and (for
 * crash-loop / error-exit) the last few container log lines.
 */
export function DiagnosticsCard({ assetId }: { assetId: string }) {
  const q = useQuery({
    queryKey: ['k8s-diagnostics', assetId],
    queryFn: () => api.k8sDiagnostics(assetId),
    refetchInterval: 8_000,
    retry: false,
  });
  const [collapsed, setCollapsed] = useState(false);

  const grouped = useMemo(() => {
    if (!q.data) return null;
    const errors = q.data.findings.filter((f) => f.severity === 'error');
    const warns = q.data.findings.filter((f) => f.severity === 'warn');
    return { errors, warns };
  }, [q.data]);

  if (q.isLoading) {
    return (
      <section className="rounded border border-gray-200 bg-white p-4 text-sm text-gray-500">
        Loading diagnostics…
      </section>
    );
  }
  if (q.error) {
    return (
      <section className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        Diagnostics unavailable: {(q.error as Error).message}
      </section>
    );
  }
  if (!q.data || !grouped) return null;
  const s = q.data.summary;
  const allClear = grouped.errors.length === 0 && grouped.warns.length === 0;

  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <header className="mb-2 flex flex-wrap items-center gap-2 text-sm">
        <h2 className="font-medium text-gray-900">Diagnostics</h2>
        <span className="text-xs text-gray-500">
          ns <code className="font-mono">{q.data.namespace}</code>
        </span>
        <span className="text-xs text-gray-700">
          pods: {s.podsRunning} running · {s.podsPending} pending · {s.podsBroken} broken
        </span>
        <span className="text-xs text-gray-500" title={q.data.evaluatedAt}>
          as of {new Date(q.data.evaluatedAt).toLocaleTimeString()}
        </span>
        <span className="ml-auto flex items-center gap-2 text-xs">
          {allClear ? (
            <span className="rounded bg-green-100 px-2 py-0.5 font-medium text-green-800">all clear</span>
          ) : (
            <>
              {grouped.errors.length > 0 && (
                <span className="rounded bg-red-100 px-2 py-0.5 font-medium text-red-800">
                  {grouped.errors.length} error{grouped.errors.length === 1 ? '' : 's'}
                </span>
              )}
              {grouped.warns.length > 0 && (
                <span className="rounded bg-amber-100 px-2 py-0.5 font-medium text-amber-800">
                  {grouped.warns.length} warning{grouped.warns.length === 1 ? '' : 's'}
                </span>
              )}
            </>
          )}
          <button
            onClick={() => q.refetch()}
            className="text-blue-700 hover:underline"
            title="Re-run diagnostics now"
          >
            refresh
          </button>
          {!allClear && (
            <button
              onClick={() => setCollapsed((v) => !v)}
              className="text-gray-600 hover:underline"
            >
              {collapsed ? 'show' : 'hide'}
            </button>
          )}
        </span>
      </header>

      {allClear && (
        <p className="text-sm text-green-700">No issues detected. ✅</p>
      )}

      {!allClear && !collapsed && (
        <ul className="space-y-2">
          {[...grouped.errors, ...grouped.warns].map((f, i) => (
            <FindingRow key={f.resource + ':' + f.code + ':' + i} finding={f} />
          ))}
        </ul>
      )}
    </section>
  );
}

function formatAge(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h${Math.floor((seconds % 3600) / 60)}m`;
  return `${Math.floor(seconds / 86400)}d${Math.floor((seconds % 86400) / 3600)}h`;
}

function FindingRow({ finding: f }: { finding: K8sDiagnostics['findings'][number] }) {
  const [showLog, setShowLog] = useState(false);
  return (
    <li className={`rounded border p-2 text-xs ${SEV_COLOR[f.severity]}`}>
      <div className="flex flex-wrap items-baseline gap-2">
        <span className="rounded bg-white/70 px-1.5 py-0.5 font-mono text-[10px] text-gray-800">
          {f.code}
        </span>
        <span className="font-mono text-gray-900">{f.resource}</span>
        {f.image && (
          <span className="font-mono text-gray-700" title="image">
            {f.image}
          </span>
        )}
        {f.ageSeconds != null && (
          <span className="text-[11px] text-gray-700" title={f.firstSeenAt ?? ''}>
            {formatAge(f.ageSeconds)}
          </span>
        )}
        {f.restartCount != null && f.restartCount > 0 && (
          <span
            className="rounded bg-white/70 px-1.5 py-0.5 text-[11px] text-gray-800"
            title={f.lastTransitionAt ? `last exit: ${new Date(f.lastTransitionAt).toLocaleString()}` : 'restart count'}
          >
            ⟲ {f.restartCount}
          </span>
        )}
        {f.logTail && f.logTail.length > 0 && (
          <button
            onClick={() => setShowLog((v) => !v)}
            className="ml-auto text-blue-700 hover:underline"
          >
            {showLog ? 'hide log' : 'show log'}
          </button>
        )}
      </div>
      <div className="mt-1 text-gray-900">{f.message}</div>
      <div className="mt-1 italic text-gray-700">→ {f.hint}</div>
      {showLog && f.logTail && f.logTail.length > 0 && (
        <pre className="mt-2 max-h-48 overflow-auto rounded bg-white/80 p-2 font-mono text-[11px]">
          {f.logTail.join('\n')}
        </pre>
      )}
    </li>
  );
}
