import { Link } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Deployment } from '../api';
import { STATUS_STYLE, orderedUrls, relTime, versionText } from './deployments';

export function StatusLight({ d }: { d: Deployment }) {
  const s = STATUS_STYLE[d.status] ?? STATUS_STYLE.unknown;
  const detail = [s.hint, d.lastError, d.lastLatencyMs != null ? `${d.lastLatencyMs} ms` : null]
    .filter(Boolean)
    .join(' — ');
  return (
    <span className={`inline-flex items-center gap-1.5 text-xs ${s.text}`} title={detail}>
      <span className={`inline-block h-2.5 w-2.5 rounded-full ${s.dot}`} />
      {s.label}
    </span>
  );
}

/**
 * Deployment rows. `showAsset` adds the asset column (the registry page); the asset
 * page hides it and shows the target instead.
 */
export function DeploymentsTable({
  rows,
  showAsset = true,
  showTarget = false,
}: {
  rows: Deployment[];
  showAsset?: boolean;
  showTarget?: boolean;
}) {
  const qc = useQueryClient();
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['deployments'] });
    qc.invalidateQueries({ queryKey: ['deployment-targets'] });
    qc.invalidateQueries({ queryKey: ['asset-deployments'] });
  };
  const check = useMutation({ mutationFn: (id: number) => api.checkDeployment(id), onSuccess: refresh });
  const retire = useMutation({ mutationFn: (id: number) => api.retireDeployment(id), onSuccess: refresh });
  const remove = useMutation({ mutationFn: (id: number) => api.deleteDeployment(id), onSuccess: refresh });
  const err = (check.error || retire.error || remove.error) as Error | null;

  return (
    <div className="overflow-x-auto">
      {err && (
        <p className="mb-2 rounded bg-red-50 px-3 py-1.5 text-xs text-red-700">
          {err.message.startsWith('401')
            ? 'The registry needs a token for changes — set it under "Registry token" on the Deployments page.'
            : err.message}
        </p>
      )}
      <table className="w-full text-sm">
        <thead className="text-left text-xs uppercase tracking-wide text-gray-500">
          <tr>
            <th className="py-1.5 pr-3 font-medium">Status</th>
            {showAsset && <th className="py-1.5 pr-3 font-medium">Asset</th>}
            {showTarget && <th className="py-1.5 pr-3 font-medium">Target</th>}
            <th className="py-1.5 pr-3 font-medium">Where</th>
            <th className="py-1.5 pr-3 font-medium">Version</th>
            <th className="py-1.5 pr-3 font-medium">Links</th>
            <th className="py-1.5 pr-3 font-medium">Seen / checked</th>
            <th className="py-1.5 font-medium" />
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {rows.map((d) => (
            <tr key={d.id} className={d.retiredAt ? 'opacity-50' : ''}>
              <td className="py-2 pr-3 align-top whitespace-nowrap">
                <StatusLight d={d} />
                {d.phase === 'delivered' && (
                  <div className="mt-0.5 text-[10px] uppercase tracking-wide text-violet-600" title="Built / delivered, not known to be running">
                    built / delivered
                  </div>
                )}
              </td>
              {showAsset && (
                <td className="py-2 pr-3 align-top">
                  <Link to={`/assets/${d.assetId}`} className="font-medium text-blue-700 hover:underline">
                    {d.assetName}
                  </Link>
                  <div className="font-mono text-[11px] text-gray-400">{d.assetId}</div>
                </td>
              )}
              {showTarget && <td className="py-2 pr-3 align-top font-medium text-gray-800">{d.target}</td>}
              <td className="py-2 pr-3 align-top text-xs text-gray-700">
                {[d.namespace, d.name].filter(Boolean).join(' / ') || '—'}
                <div className="text-[11px] text-gray-400" title="Who reported it">{d.source}</div>
              </td>
              <td className="py-2 pr-3 align-top font-mono text-xs text-gray-700">{versionText(d)}</td>
              <td className="py-2 pr-3 align-top">
                <div className="flex flex-wrap gap-1">
                  {orderedUrls(d.urls).map((u) => (
                    <a
                      key={u.url + u.label}
                      href={u.url}
                      target="_blank"
                      rel="noreferrer"
                      title={u.url}
                      className={`rounded px-2 py-0.5 text-xs ${
                        u.primary
                          ? 'bg-blue-600 text-white hover:bg-blue-700'
                          : 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
                      }`}
                    >
                      ↗ {u.label}
                    </a>
                  ))}
                  {d.urls.length === 0 && <span className="text-xs text-gray-400">—</span>}
                </div>
              </td>
              <td className="py-2 pr-3 align-top text-xs text-gray-500 whitespace-nowrap">
                <div title={d.lastReportedAt}>seen {relTime(d.lastReportedAt)}</div>
                <div title={d.lastCheckedAt ?? ''}>checked {relTime(d.lastCheckedAt)}</div>
              </td>
              <td className="py-2 align-top text-right whitespace-nowrap">
                {!d.retiredAt && (d.healthUrl || d.urls.length > 0) && (
                  <button
                    onClick={() => check.mutate(d.id)}
                    className="text-xs text-blue-700 hover:underline"
                    title="Run the health check now"
                  >
                    check
                  </button>
                )}
                {!d.retiredAt && (
                  <button
                    onClick={() => retire.mutate(d.id)}
                    className="ml-2 text-xs text-gray-600 hover:underline"
                    title="Mark as no longer running (kept for history)"
                  >
                    retire
                  </button>
                )}
                <button
                  onClick={() => {
                    if (confirm(`Delete the ${d.assetName} deployment on ${d.target}?`)) remove.mutate(d.id);
                  }}
                  className="ml-2 text-xs text-red-600 hover:underline"
                >
                  delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
