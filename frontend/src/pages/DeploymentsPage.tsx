import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, REGISTRY_TOKEN_KEY, type DeploymentTargetSummary } from '../api';
import { DeploymentsTable } from '../components/DeploymentsTable';
import { STATUS_STYLE, TARGET_KIND_LABEL, groupByTarget, relTime } from '../components/deployments';

const STATUSES = ['healthy', 'degraded', 'down', 'unknown', 'reported', 'retired'] as const;

/** "What is running where": every reported deployment, grouped by target. */
export function DeploymentsPage() {
  const [target, setTarget] = useState('');
  const [status, setStatus] = useState('');
  const [phase, setPhase] = useState('');
  const [q, setQ] = useState('');
  const [includeRetired, setIncludeRetired] = useState(false);

  const targets = useQuery({
    queryKey: ['deployment-targets'],
    queryFn: () => api.listDeploymentTargets(),
    refetchInterval: 30_000,
  });
  const list = useQuery({
    queryKey: ['deployments', target, status, phase, q, includeRetired],
    queryFn: () => api.listDeployments({ target, status, phase, q, includeRetired }),
    refetchInterval: 30_000,
  });
  const groups = useMemo(() => groupByTarget(list.data ?? []), [list.data]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">Deployments</h1>
          <p className="text-sm text-gray-500">
            What is running where — reported by cloud-stack, SDLC deliveries, Claude Code or by hand,
            health-checked every minute.
          </p>
        </div>
        <TokenField />
      </div>

      {targets.data && targets.data.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {targets.data.map((t) => (
            <TargetChip key={t.target} t={t} active={target === t.target}
              onClick={() => setTarget(target === t.target ? '' : t.target)} />
          ))}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2 text-sm">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="filter by asset, target, namespace, version, source…"
          className="min-w-64 flex-1 rounded border border-gray-300 px-3 py-1.5"
        />
        <select value={target} onChange={(e) => setTarget(e.target.value)} className="rounded border border-gray-300 px-2 py-1.5">
          <option value="">all targets</option>
          {(targets.data ?? []).map((t) => (
            <option key={t.target} value={t.target}>{t.target}</option>
          ))}
        </select>
        <select value={status} onChange={(e) => setStatus(e.target.value)} className="rounded border border-gray-300 px-2 py-1.5">
          <option value="">any status</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
        <select value={phase} onChange={(e) => setPhase(e.target.value)} className="rounded border border-gray-300 px-2 py-1.5">
          <option value="">running + delivered</option>
          <option value="running">running</option>
          <option value="delivered">built / delivered</option>
        </select>
        <label className="flex items-center gap-1 text-gray-600">
          <input type="checkbox" checked={includeRetired} onChange={(e) => setIncludeRetired(e.target.checked)} />
          include retired
        </label>
      </div>

      {list.isLoading && <p className="text-gray-500">Loading…</p>}
      {list.error && <p className="text-red-600">{(list.error as Error).message}</p>}
      {list.data && list.data.length === 0 && (
        <div className="rounded border border-dashed border-gray-300 bg-white p-6 text-sm text-gray-600">
          <p className="font-medium text-gray-800">Nothing reported{target || status || q ? ' for this filter' : ' yet'}.</p>
          <p className="mt-1">
            Deployments arrive from reporters: cloud-stack (set <code>DEVHUB_URL</code> on its api), the
            MCP tool <code>report_deployment</code>, or{' '}
            <code>POST /api/deployments/report</code>. See the README section “Deployment registry”.
          </p>
        </div>
      )}

      {groups.map((g) => (
        <section key={g.target} className="rounded border border-gray-200 bg-white">
          <header className="flex items-center justify-between border-b border-gray-100 px-4 py-2">
            <h2 className="text-base font-semibold text-gray-900">
              {g.target}
              {TARGET_KIND_LABEL[g.kind] && (
                <span className="ml-2 rounded bg-gray-100 px-1.5 py-0.5 text-[11px] font-normal text-gray-600">
                  {TARGET_KIND_LABEL[g.kind]}
                </span>
              )}
            </h2>
            <span className="text-xs text-gray-500">{g.rows.length} deployment{g.rows.length === 1 ? '' : 's'}</span>
          </header>
          <div className="px-4 py-2">
            <DeploymentsTable rows={g.rows} />
          </div>
        </section>
      ))}
    </div>
  );
}

function TargetChip({ t, active, onClick }: { t: DeploymentTargetSummary; active: boolean; onClick: () => void }) {
  const counts: [keyof typeof STATUS_STYLE, number][] = [
    ['healthy', t.healthy], ['degraded', t.degraded], ['down', t.down], ['unknown', t.unknown], ['reported', t.reported],
  ];
  return (
    <button
      onClick={onClick}
      className={`rounded border px-3 py-1.5 text-left text-xs ${
        active ? 'border-blue-500 bg-blue-50' : 'border-gray-200 bg-white hover:bg-gray-50'
      }`}
      title={`last report ${relTime(t.lastReportedAt)}`}
    >
      <div className="font-semibold text-gray-900">
        {t.target} <span className="font-normal text-gray-500">· {t.total}</span>
      </div>
      <div className="mt-0.5 flex gap-2">
        {counts.filter(([, n]) => n > 0).map(([s, n]) => (
          <span key={s} className={`inline-flex items-center gap-1 ${STATUS_STYLE[s].text}`}>
            <span className={`inline-block h-2 w-2 rounded-full ${STATUS_STYLE[s].dot}`} />
            {n}
          </span>
        ))}
      </div>
    </button>
  );
}

/** The registry's write token, kept in this browser only (needed when DEVHUB_REGISTRY_TOKEN is set). */
function TokenField() {
  const read = () => {
    try {
      return localStorage.getItem(REGISTRY_TOKEN_KEY) ?? '';
    } catch {
      return '';
    }
  };
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState(read);
  const saved = read() !== '';
  return (
    <div className="text-xs">
      {!open ? (
        <button onClick={() => setOpen(true)} className="text-gray-500 hover:text-gray-800" title="Needed for check / retire / delete when the backend sets DEVHUB_REGISTRY_TOKEN">
          Registry token: {saved ? 'set' : 'not set'}
        </button>
      ) : (
        <form
          className="flex items-center gap-1"
          onSubmit={(e) => {
            e.preventDefault();
            try {
              if (value) localStorage.setItem(REGISTRY_TOKEN_KEY, value);
              else localStorage.removeItem(REGISTRY_TOKEN_KEY);
            } catch {
              // storage unavailable
            }
            setOpen(false);
          }}
        >
          <input
            type="password"
            autoComplete="off"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="X-DevHub-Token"
            className="rounded border border-gray-300 px-2 py-1"
          />
          <button type="submit" className="rounded bg-gray-800 px-2 py-1 text-white">save</button>
        </form>
      )}
    </div>
  );
}
