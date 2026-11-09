import type { ReactNode } from 'react';
import type { CloudStackActivity, CloudStackRun } from '../api';
import { relTime } from './deployments';
import {
  checksLight,
  httpUrl,
  isActiveRun,
  jenkinsLight,
  REASON_TITLE,
  runLight,
  useAssetCloudStack,
} from './cloudstack';

/** "New task in cloud-stack" — opens cloud-stack's New task page for this repo in a new tab. */
export function NewCloudStackTaskButton({
  assetId,
  variant = 'header',
}: {
  assetId: string;
  variant?: 'header' | 'prominent';
}) {
  const q = useAssetCloudStack(assetId);
  const href = httpUrl(q.data?.newTaskUrl);
  if (!href) return null;
  const cls =
    variant === 'prominent'
      ? 'inline-flex items-center rounded bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700'
      : 'rounded border border-indigo-300 bg-indigo-50 px-3 py-1.5 text-sm text-indigo-800 hover:bg-indigo-100';
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className={cls}
      title={`Start a cloud-stack agent task on ${q.data?.repo ?? 'this repo'} (opens cloud-stack)`}
    >
      {variant === 'prominent' ? 'New task' : 'New task in cloud-stack'} ↗
    </a>
  );
}

function Dot({ dot, label, text }: { dot: string; label: string; text: string }) {
  return (
    <span className={`inline-flex items-center gap-1 whitespace-nowrap text-xs ${text}`} title={label}>
      <span className={`inline-block h-2.5 w-2.5 rounded-full ${dot}`} />
      {label}
    </span>
  );
}

function ExtLink({ href, children }: { href: string | null | undefined; children: ReactNode }) {
  const u = httpUrl(href);
  if (!u) return <>{children}</>;
  return (
    <a href={u} target="_blank" rel="noopener noreferrer" className="text-blue-700 hover:underline">
      {children}
    </a>
  );
}

function Section({ title, right, children }: { title: string; right?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-gray-900">{title}</h2>
        {right}
      </div>
      {children}
    </section>
  );
}

/** What cloud-stack (the agent / SDLC platform) is doing on this asset's repo. */
export function CloudStackTab({ assetId }: { assetId: string }) {
  const q = useAssetCloudStack(assetId, true);

  return (
    <div className="space-y-4">
      <section className="flex flex-wrap items-center justify-between gap-3 rounded border border-gray-200 bg-white p-4">
        <div>
          <h2 className="text-sm font-medium text-gray-900">cloud-stack</h2>
          <p className="text-xs text-gray-500">
            Agent tasks, pull requests and CI for{' '}
            {q.data?.repoUrl ? <ExtLink href={q.data.repoUrl}>{q.data.repo}</ExtLink> : 'this repo'}.
            {q.data?.available && q.data.fetchedAt && <> Updated {relTime(q.data.fetchedAt)}.</>}
          </p>
        </div>
        <div className="flex items-center gap-3">
          {q.data?.uiUrl && httpUrl(q.data.uiUrl) && (
            <a href={httpUrl(q.data.uiUrl)} target="_blank" rel="noopener noreferrer" className="text-xs text-blue-700 hover:underline">
              open cloud-stack ↗
            </a>
          )}
          <NewCloudStackTaskButton assetId={assetId} variant="prominent" />
        </div>
      </section>

      {q.isLoading && <p className="text-sm text-gray-500">Asking cloud-stack…</p>}
      {q.error && <p className="text-sm text-red-600">{(q.error as Error).message}</p>}
      {q.data && !q.data.available && <Unavailable data={q.data} />}
      {q.data && q.data.available && <Activity data={q.data} />}
    </div>
  );
}

function Unavailable({ data }: { data: CloudStackActivity }) {
  const title = data.reasonCode ? REASON_TITLE[data.reasonCode] ?? 'cloud-stack activity is unavailable' : 'cloud-stack activity is unavailable';
  return (
    <section className="rounded border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
      <p className="font-medium">{title}</p>
      {data.reason && <p className="mt-1">{data.reason}</p>}
      {data.reasonCode === 'not-configured' && (
        <p className="mt-1 text-xs">
          Set <code>CLOUDSTACK_API_URL</code>, <code>CLOUDSTACK_UI_URL</code> and <code>CLOUDSTACK_TOKEN</code> on the backend
          (see the README, “cloud-stack”).
        </p>
      )}
      {data.newTaskUrl && data.reasonCode !== 'no-github-repo' && (
        <p className="mt-1 text-xs">You can still start a task with the New task button.</p>
      )}
    </section>
  );
}

function Activity({ data }: { data: CloudStackActivity }) {
  const active = data.runs.filter(isActiveRun);
  const recent = data.runs.filter((r) => !isActiveRun(r));
  const j = data.jenkins;
  const jl = jenkinsLight(j?.status);

  return (
    <>
      <Section title={`Open pull requests (${data.openPRs.length})`}>
        {data.openPRs.length === 0 ? (
          <p className="text-sm text-gray-500">No open pull requests.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {data.openPRs.map((p, i) => {
              const cl = checksLight(p.checks);
              return (
                <li key={p.url ?? `${p.number}-${i}`} className="flex flex-wrap items-start justify-between gap-2 py-2 text-sm">
                  <div className="min-w-0">
                    <ExtLink href={p.url}>
                      {p.number != null && <span className="text-gray-500">#{p.number} </span>}
                      {p.title ?? '(untitled)'}
                    </ExtLink>
                    {p.why && <p className="text-xs text-gray-500">{p.why}</p>}
                  </div>
                  <div className="flex items-center gap-3">
                    {p.needs && (
                      <span className="rounded bg-amber-50 px-1.5 py-0.5 text-xs text-amber-800" title="What this PR needs next">
                        needs: {p.needs}
                      </span>
                    )}
                    <Dot {...cl} />
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Section>

      <Section title="Latest Jenkins build">
        {!j || (j.status ?? 'none') === 'none' ? (
          <p className="text-sm text-gray-500">No Jenkins build reported for this repo.</p>
        ) : (
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <Dot {...jl} />
            <ExtLink href={j.link}>
              {j.jobName ?? 'build'}
              {j.buildNumber != null && ` #${j.buildNumber}`}
            </ExtLink>
            {j.sha && <code className="text-xs text-gray-500">{j.sha.slice(0, 10)}</code>}
            {j.at && <span className="text-xs text-gray-500">{relTime(j.at)}</span>}
          </div>
        )}
      </Section>

      <Section title={`Agent runs (${active.length} active)`}>
        {data.runs.length === 0 ? (
          <p className="text-sm text-gray-500">No cloud-stack runs on this repo yet.</p>
        ) : (
          <div className="space-y-3">
            {active.length > 0 && <RunList title="Active" runs={active} />}
            {recent.length > 0 && <RunList title="Recent" runs={recent} />}
          </div>
        )}
      </Section>
    </>
  );
}

function RunList({ title, runs }: { title: string; runs: CloudStackRun[] }) {
  return (
    <div>
      <h3 className="mb-1 text-xs font-medium uppercase tracking-wide text-gray-500">{title}</h3>
      <ul className="divide-y divide-gray-100">
        {runs.map((r, i) => (
          <li key={r.id ?? i} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
            <div className="min-w-0">
              <ExtLink href={r.url}>{r.title ?? r.id ?? '(run)'}</ExtLink>
              {r.workflow && <span className="ml-2 text-xs text-gray-500">{r.workflow}</span>}
            </div>
            <div className="flex items-center gap-3">
              <Dot {...runLight(r.state)} />
              {r.createdAt && <span className="text-xs text-gray-500">{relTime(r.createdAt)}</span>}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
