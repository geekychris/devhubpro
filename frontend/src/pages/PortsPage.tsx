import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';

type Row = {
  source: 'reservation' | 'forward';
  assetId: string;
  scope: string;          // local | k8s-nodeport | port-forward
  slot: string;           // slot name or "<podName>:<containerPort>"
  port: number;
  protocol: string;
  url: string | null;     // localhost URL for things reachable from host
  detail: string;
  rid?: number;
  fid?: number;
  status?: 'running' | 'stopped' | 'failed';
};

export function PortsPage() {
  const queryClient = useQueryClient();
  const reservations = useQuery({
    queryKey: ['ports-all'],
    queryFn: () => api.listAllPorts(),
    refetchInterval: 5_000,
  });
  const forwards = useQuery({
    queryKey: ['port-forwards-all'],
    queryFn: () => api.listPortForwards(),
    refetchInterval: 3_000,
  });

  const [groupBy, setGroupBy] = useState<'scope' | 'asset'>('scope');
  const [filter, setFilter] = useState('');

  const stop = useMutation({
    mutationFn: (id: number) => api.stopPortForward(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['port-forwards-all'] }),
  });

  const rows: Row[] = useMemo(() => {
    const out: Row[] = [];
    for (const r of reservations.data ?? []) {
      out.push({
        source: 'reservation',
        assetId: r.assetId,
        scope: r.scope,
        slot: r.slotName,
        port: r.port,
        protocol: r.protocol,
        url:
          r.scope === 'local' || r.scope === 'k8s-nodeport'
            ? `http://localhost:${r.port}/`
            : null,
        detail: '',
        rid: r.id,
      });
    }
    for (const f of forwards.data ?? []) {
      out.push({
        source: 'forward',
        assetId: f.assetId,
        scope: 'port-forward',
        slot: `${f.podName}:${f.containerPort}`,
        port: f.hostPort,
        protocol: 'tcp',
        url: `http://localhost:${f.hostPort}/`,
        detail: `→ ${f.namespace}/${f.podName}:${f.containerPort}`,
        fid: f.id,
        status: f.status,
      });
    }
    return out;
  }, [reservations.data, forwards.data]);

  const filtered = rows.filter((r) => {
    if (!filter.trim()) return true;
    const q = filter.toLowerCase();
    return (
      r.assetId.toLowerCase().includes(q) ||
      r.scope.toLowerCase().includes(q) ||
      r.slot.toLowerCase().includes(q) ||
      String(r.port).includes(q) ||
      (r.url ?? '').toLowerCase().includes(q) ||
      r.detail.toLowerCase().includes(q)
    );
  });

  const groups: Record<string, Row[]> = {};
  for (const r of filtered) {
    const key = groupBy === 'scope' ? r.scope : r.assetId;
    (groups[key] ??= []).push(r);
  }
  const groupKeys = Object.keys(groups).sort((a, b) => {
    if (groupBy === 'scope') {
      const order = ['local', 'k8s-nodeport', 'port-forward'];
      return order.indexOf(a) - order.indexOf(b);
    }
    return a.localeCompare(b);
  });

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold text-gray-900">Port registry</h1>
        <p className="text-sm text-gray-500">
          Every concrete port the portal knows about — local docker, Kubernetes NodePort, and active
          {' '}
          <code>kubectl port-forward</code> tunnels. URLs are the host-reachable address.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3 rounded border border-gray-200 bg-white p-3">
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="filter by asset / port / url / pod"
          className="flex-1 min-w-[18rem] rounded border border-gray-300 px-3 py-1.5 text-sm"
        />
        <div className="flex items-center gap-1 text-xs">
          <span className="text-gray-500">Group by:</span>
          {(['scope', 'asset'] as const).map((g) => (
            <button
              key={g}
              onClick={() => setGroupBy(g)}
              className={`rounded px-2 py-1 ${
                groupBy === g
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              {g}
            </button>
          ))}
        </div>
        <span className="ml-auto text-xs text-gray-500">
          {rows.length} total · {filtered.length} shown
        </span>
      </div>

      {(reservations.error || forwards.error) && (
        <p className="text-sm text-red-600">
          {((reservations.error || forwards.error) as Error).message}
        </p>
      )}

      {filtered.length === 0 && (
        <p className="text-sm text-gray-500">
          No ports {filter ? 'match the filter' : 'reserved yet'}.
        </p>
      )}

      {groupKeys.map((key) => (
        <GroupBlock
          key={key}
          group={key}
          rows={groups[key]}
          groupBy={groupBy}
          onStopForward={(id: number) => stop.mutate(id)}
        />
      ))}
    </div>
  );
}

function GroupBlock({
  group,
  rows,
  groupBy,
  onStopForward,
}: {
  group: string;
  rows: Row[];
  groupBy: 'scope' | 'asset';
  onStopForward: (id: number) => void;
}) {
  const heading =
    groupBy === 'scope' ? scopeLabel(group) : (
      <Link to={`/assets/${group}`} className="text-blue-700 hover:underline">
        {group}
      </Link>
    );
  rows.sort((a, b) => a.port - b.port);
  return (
    <section className="rounded border border-gray-200 bg-white">
      <header className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-4 py-2">
        <h2 className="text-sm font-medium text-gray-900">{heading}</h2>
        <span className="text-xs text-gray-500">
          {rows.length} entr{rows.length === 1 ? 'y' : 'ies'}
        </span>
      </header>
      <table className="w-full text-sm">
        <thead className="text-left text-xs uppercase text-gray-500">
          <tr>
            <th className="px-4 py-2">Status</th>
            <th className="px-4 py-2">Port</th>
            <th className="px-4 py-2">Slot / Target</th>
            <th className="px-4 py-2">Asset</th>
            <th className="px-4 py-2">URL</th>
            <th className="px-4 py-2"></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={`${r.source}-${r.rid ?? r.fid}`} className="border-t border-gray-100">
              <td className="px-4 py-2"><StatusPill row={r} /></td>
              <td className="px-4 py-2 font-mono">{r.port}</td>
              <td className="px-4 py-2 font-mono text-xs">
                {r.slot} {r.detail && <span className="text-gray-400">{r.detail}</span>}
              </td>
              <td className="px-4 py-2">
                <Link to={`/assets/${r.assetId}`} className="text-blue-700 hover:underline">
                  {r.assetId}
                </Link>
              </td>
              <td className="px-4 py-2 font-mono text-xs">
                {r.url ? (
                  <a
                    href={r.url}
                    target="_blank"
                    rel="noreferrer"
                    className="text-blue-700 hover:underline"
                  >
                    {r.url}
                  </a>
                ) : (
                  <span className="text-gray-400">—</span>
                )}
              </td>
              <td className="px-4 py-2 text-right whitespace-nowrap">
                {r.url && (
                  <button
                    onClick={() => navigator.clipboard.writeText(r.url!)}
                    className="text-gray-400 hover:text-gray-700"
                    title="Copy URL"
                  >
                    ⧉
                  </button>
                )}
                {r.source === 'forward' && r.fid !== undefined && r.status === 'running' && (
                  <button
                    onClick={() => onStopForward(r.fid!)}
                    className="ml-2 text-xs text-red-600 hover:underline"
                  >
                    stop
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function scopeLabel(scope: string): React.ReactNode {
  const map: Record<string, string> = {
    local: 'Local docker (host port)',
    'k8s-nodeport': 'Kubernetes NodePort (Rancher Desktop forwards to localhost)',
    'port-forward': 'kubectl port-forward (active tunnel)',
  };
  return map[scope] ?? scope;
}

function StatusPill({ row }: { row: Row }) {
  if (row.source === 'forward') {
    const cls =
      row.status === 'running'
        ? 'bg-green-100 text-green-800'
        : row.status === 'failed'
        ? 'bg-red-100 text-red-800'
        : 'bg-gray-200 text-gray-700';
    return <span className={`rounded px-1.5 py-0.5 text-xs ${cls}`}>{row.status}</span>;
  }
  return (
    <span className="rounded bg-blue-100 px-1.5 py-0.5 text-xs text-blue-800">reserved</span>
  );
}
