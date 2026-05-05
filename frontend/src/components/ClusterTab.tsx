import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api';
import { PodTerminal } from './PodTerminal';

type Pod = Awaited<ReturnType<typeof api.k8sPods>>[number];

export function ClusterTab({ assetId }: { assetId: string }) {
  const pods = useQuery({
    queryKey: ['k8s-pods', assetId],
    queryFn: () => api.k8sPods(assetId),
    refetchInterval: 5_000,
    retry: false,
  });
  const events = useQuery({
    queryKey: ['k8s-events', assetId],
    queryFn: () => api.k8sPodEvents(assetId),
    refetchInterval: 10_000,
    retry: false,
  });
  const [selectedPod, setSelectedPod] = useState<string | null>(null);

  return (
    <div className="space-y-4">
      <section className="rounded border border-gray-200 bg-white">
        <header className="flex items-center justify-between border-b border-gray-200 px-4 py-2">
          <h2 className="text-sm font-medium text-gray-900">Pods</h2>
          {pods.data && (
            <span className="text-xs text-gray-500">
              {pods.data.length} pod{pods.data.length === 1 ? '' : 's'}
            </span>
          )}
        </header>
        {pods.isLoading && <p className="px-4 py-3 text-sm text-gray-500">Loading…</p>}
        {pods.error && (
          <p className="px-4 py-3 text-sm text-red-600">{(pods.error as Error).message}</p>
        )}
        {pods.data && pods.data.length === 0 && (
          <p className="px-4 py-3 text-sm text-gray-500">
            No pods. Click "kubectl apply" on the runtime tab to deploy.
          </p>
        )}
        {pods.data && pods.data.length > 0 && (
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase text-gray-500">
              <tr>
                <th className="px-4 py-2">Name</th>
                <th className="px-4 py-2">Phase</th>
                <th className="px-4 py-2">Ready</th>
                <th className="px-4 py-2">Restarts</th>
                <th className="px-4 py-2">Node</th>
                <th className="px-4 py-2">IP</th>
              </tr>
            </thead>
            <tbody>
              {pods.data.map((p) => (
                <tr
                  key={p.name}
                  onClick={() => setSelectedPod(p.name)}
                  className={`cursor-pointer border-t border-gray-100 hover:bg-gray-50 ${
                    selectedPod === p.name ? 'bg-blue-50' : ''
                  }`}
                >
                  <td className="px-4 py-2 font-mono text-xs">{p.name}</td>
                  <td className="px-4 py-2"><PhaseBadge phase={p.phase} /></td>
                  <td className="px-4 py-2 font-mono text-xs">
                    {p.readyContainers}/{p.totalContainers}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs">{p.restartCount}</td>
                  <td className="px-4 py-2 text-xs">{p.node ?? '—'}</td>
                  <td className="px-4 py-2 font-mono text-xs">{p.podIp ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {selectedPod && pods.data && (
        <PodDrilldown
          assetId={assetId}
          pod={pods.data.find((p) => p.name === selectedPod)!}
          onClose={() => setSelectedPod(null)}
        />
      )}

      <section className="rounded border border-gray-200 bg-white">
        <header className="flex items-center justify-between border-b border-gray-200 px-4 py-2">
          <h2 className="text-sm font-medium text-gray-900">Recent events</h2>
          {events.data && (
            <span className="text-xs text-gray-500">{events.data.length} event{events.data.length === 1 ? '' : 's'}</span>
          )}
        </header>
        {events.error && (
          <p className="px-4 py-3 text-sm text-red-600">{(events.error as Error).message}</p>
        )}
        {events.data && events.data.length === 0 && (
          <p className="px-4 py-3 text-sm text-gray-500">No events.</p>
        )}
        {events.data && events.data.length > 0 && (
          <ul className="divide-y divide-gray-100 text-xs">
            {events.data.slice(-30).reverse().map((e, i) => (
              <li key={i} className="px-4 py-2">
                <div className="flex items-center gap-2">
                  <span
                    className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${
                      e.type === 'Warning' ? 'bg-amber-100 text-amber-800' : 'bg-gray-100 text-gray-700'
                    }`}
                  >
                    {e.type}
                  </span>
                  <span className="font-medium">{e.reason}</span>
                  <span className="text-gray-500">{e.involvedObject}</span>
                  {e.count > 1 && <span className="text-gray-400">×{e.count}</span>}
                  <span className="ml-auto text-gray-400">{e.lastSeen ?? ''}</span>
                </div>
                <div className="text-gray-700">{e.message}</div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function PodDrilldown({
  assetId,
  pod,
  onClose,
}: {
  assetId: string;
  pod: Pod;
  onClose: () => void;
}) {
  const [view, setView] = useState<'overview' | 'logs' | 'describe' | 'shell'>('overview');
  const containerName = pod.containers[0]?.name;
  const [container, setContainer] = useState<string>(containerName ?? '');

  const logs = useQuery({
    queryKey: ['k8s-pod-logs', assetId, pod.name, container],
    queryFn: () => api.k8sPodLogs(assetId, pod.name, container || undefined),
    enabled: view === 'logs',
    refetchInterval: view === 'logs' ? 3_000 : false,
  });
  const describe = useQuery({
    queryKey: ['k8s-pod-describe', assetId, pod.name],
    queryFn: () => api.k8sPodDescribe(assetId, pod.name),
    enabled: view === 'describe',
  });

  return (
    <section className="rounded border border-blue-300 bg-white">
      <header className="flex items-center justify-between border-b border-blue-200 bg-blue-50 px-4 py-2">
        <div className="flex items-center gap-3 text-sm">
          <span className="font-mono">{pod.name}</span>
          <PhaseBadge phase={pod.phase} />
          <span className="text-xs text-gray-500">{pod.namespace}</span>
        </div>
        <button onClick={onClose} className="text-sm text-gray-500 hover:text-gray-700">close ✕</button>
      </header>

      <nav className="flex gap-3 border-b border-gray-200 px-4 text-sm">
        {(['overview', 'logs', 'describe', 'shell'] as const).map((v) => (
          <button
            key={v}
            onClick={() => setView(v)}
            className={`-mb-px border-b-2 px-1 py-2 ${
              view === v
                ? 'border-blue-600 font-medium text-blue-700'
                : 'border-transparent text-gray-600 hover:text-gray-900'
            }`}
          >
            {v}
          </button>
        ))}
        {pod.containers.length > 1 && (
          <select
            value={container}
            onChange={(e) => setContainer(e.target.value)}
            className="ml-auto my-1 rounded border border-gray-300 px-2 py-0.5 text-xs"
          >
            {pod.containers.map((c) => (
              <option key={c.name} value={c.name}>
                {c.name}
              </option>
            ))}
          </select>
        )}
      </nav>

      <div className="p-4 text-sm">
        {view === 'overview' && <PodOverview pod={pod} />}
        {view === 'logs' && (
          <pre className="max-h-[60vh] overflow-auto rounded bg-gray-900 px-3 py-2 font-mono text-xs text-gray-100">
            {logs.error ? (logs.error as Error).message : logs.data ?? 'Loading…'}
          </pre>
        )}
        {view === 'describe' && (
          <pre className="max-h-[60vh] overflow-auto rounded bg-gray-100 px-3 py-2 font-mono text-xs">
            {describe.error ? (describe.error as Error).message : describe.data ?? 'Loading…'}
          </pre>
        )}
        {view === 'shell' && (
          <PodTerminal assetId={assetId} podName={pod.name} container={container || undefined} />
        )}
      </div>
    </section>
  );
}

function PodOverview({ pod }: { pod: Pod }) {
  return (
    <div className="space-y-3 text-sm">
      <div className="grid grid-cols-2 gap-x-3 gap-y-1">
        <Row label="Phase" value={pod.phase} />
        <Row label="Node" value={pod.node ?? '—'} />
        <Row label="Pod IP" value={pod.podIp ?? '—'} mono />
        <Row label="Started" value={pod.startTime ?? '—'} />
        <Row label="Ready" value={`${pod.readyContainers}/${pod.totalContainers}`} />
        <Row label="Restarts" value={String(pod.restartCount)} />
      </div>
      <div>
        <h3 className="text-xs font-medium uppercase text-gray-500">Containers</h3>
        <ul className="mt-1 space-y-2">
          {pod.containers.map((c) => (
            <li key={c.name} className="rounded border border-gray-200 p-3 text-xs">
              <div className="flex items-center gap-2">
                <span className="font-mono font-medium">{c.name}</span>
                <span
                  className={`rounded px-1.5 py-0.5 text-[10px] ${
                    c.ready ? 'bg-green-100 text-green-800' : 'bg-amber-100 text-amber-800'
                  }`}
                >
                  {c.ready ? 'ready' : c.state}
                </span>
                <span className="text-gray-500">restarts: {c.restartCount}</span>
              </div>
              <div className="font-mono text-gray-600">{c.image}</div>
              {c.reason && <div className="text-amber-700">reason: {c.reason}</div>}
              {c.lastTermReason && (
                <div className="text-red-700">
                  last terminated: {c.lastTermReason} (exit {c.lastTermExitCode ?? '?'})
                </div>
              )}
              {c.ports.length > 0 && (
                <div className="text-gray-600">ports: {c.ports.join(', ')}</div>
              )}
              {c.env.length > 0 && (
                <details className="mt-1">
                  <summary className="cursor-pointer text-gray-600">
                    env ({c.env.length})
                  </summary>
                  <table className="mt-1 w-full">
                    <tbody>
                      {c.env.map((e) => (
                        <tr key={e.name}>
                          <td className="font-mono pr-3">{e.name}</td>
                          <td className="font-mono text-gray-700 break-all">{e.value}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </details>
              )}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <>
      <div className="text-xs font-medium text-gray-500">{label}</div>
      <div className={mono ? 'font-mono text-xs' : 'text-sm'}>{value}</div>
    </>
  );
}

function PhaseBadge({ phase }: { phase: string }) {
  const cls =
    phase === 'Running'
      ? 'bg-green-100 text-green-800'
      : phase === 'Succeeded'
      ? 'bg-blue-100 text-blue-800'
      : phase === 'Failed'
      ? 'bg-red-100 text-red-800'
      : 'bg-amber-100 text-amber-800 animate-pulse';
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${cls}`}>{phase}</span>
  );
}
