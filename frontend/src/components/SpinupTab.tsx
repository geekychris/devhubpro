import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type SpinupJob, type SpinupStep } from '../api';

const STEP_ORDER = ['BUILD_IMAGES', 'ENSURE_NAMESPACE', 'APPLY', 'ROLLOUT_RESTART', 'WAIT_READY', 'RUN_HOOKS', 'PROBE_ENDPOINTS'] as const;

const STEP_LABEL: Record<string, string> = {
  BUILD_IMAGES: 'Build images',
  ENSURE_NAMESPACE: 'Ensure namespace',
  APPLY: 'kubectl apply',
  ROLLOUT_RESTART: 'Rollout-restart updated deployments',
  WAIT_READY: 'Wait for pod readiness',
  RUN_HOOKS: 'Run on-apply hooks',
  PROBE_ENDPOINTS: 'Probe endpoints',
};

const STEP_HINT: Record<string, string> = {
  BUILD_IMAGES: 'Builds every Docker image declared in this asset and its runtime closure.',
  ENSURE_NAMESPACE: 'Creates the target namespace if it does not exist yet.',
  APPLY: 'Runs kubectl apply against the rendered manifests.',
  ROLLOUT_RESTART: 'For each rebuilt image tag, kubectl rollout restart any deployment using it. Necessary because apply is a no-op when only :latest bytes change.',
  WAIT_READY: 'Polls workloads for up to 90s and fails the spinup if pods are not Ready — surfaces unhealthy pods by name and reason.',
  RUN_HOOKS: 'Executes test fixtures marked runOnApply: true.',
  PROBE_ENDPOINTS: 'Tries each host-accessible HTTP endpoint until one responds (3 min cap).',
};

const STATUS_PILL: Record<SpinupStep['status'], string> = {
  pending: 'bg-gray-100 text-gray-600',
  running: 'bg-blue-100 text-blue-800',
  succeeded: 'bg-green-100 text-green-800',
  failed: 'bg-red-100 text-red-800',
  skipped: 'bg-amber-50 text-amber-800',
};

const STATUS_ICON: Record<SpinupStep['status'], string> = {
  pending: '○',
  running: '◐',
  succeeded: '✓',
  failed: '✗',
  skipped: '–',
};

const JOB_PILL: Record<SpinupJob['status'], string> = {
  queued: 'bg-gray-200 text-gray-700',
  running: 'bg-blue-100 text-blue-800',
  succeeded: 'bg-green-100 text-green-800',
  failed: 'bg-red-100 text-red-800',
};

export function SpinupTab({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const [activeJobId, setActiveJobId] = useState<number | null>(null);
  const [skipImageBuild, setSkipImageBuild] = useState(false);
  const [skipProbe, setSkipProbe] = useState(false);
  const [includeRuntime, setIncludeRuntime] = useState(true);
  const [showLog, setShowLog] = useState(true);

  const recent = useQuery({
    queryKey: ['spinup-jobs', assetId],
    queryFn: () => api.listSpinupJobs(),
    refetchInterval: () => (activeJobId ? false : 5_000),
  });

  // Auto-pick the most recent job for this asset if none selected.
  useEffect(() => {
    if (activeJobId != null) return;
    const mine = (recent.data ?? []).filter((j) => j.assetId === assetId);
    if (mine.length > 0) setActiveJobId(mine[0].id);
  }, [recent.data, assetId, activeJobId]);

  const job = useQuery({
    queryKey: ['spinup-job', activeJobId],
    queryFn: () => api.getSpinupJob(activeJobId as number),
    enabled: activeJobId != null,
    refetchInterval: (q) => {
      const s = q.state.data?.status;
      return s === 'running' || s === 'queued' ? 1_500 : false;
    },
  });

  const start = useMutation({
    mutationFn: () => api.startSpinup(assetId, { skipImageBuild, skipProbe, includeRuntime }),
    onSuccess: (j) => {
      setActiveJobId(j.id);
      queryClient.invalidateQueries({ queryKey: ['spinup-jobs', assetId] });
    },
  });

  const mineRecent = useMemo(
    () => (recent.data ?? []).filter((j) => j.assetId === assetId),
    [recent.data, assetId]
  );

  const j = job.data;
  const isRunning = j?.status === 'running' || j?.status === 'queued';

  // Merge actual steps with the canonical order so unstarted steps still appear greyed-out.
  const renderedSteps: SpinupStep[] = useMemo(() => {
    const byName = new Map<string, SpinupStep>();
    (j?.steps ?? []).forEach((s) => byName.set(s.name, s));
    return STEP_ORDER.map((name) =>
      byName.get(name) ?? { name, status: 'pending', startedAt: null, finishedAt: null, message: null }
    );
  }, [j]);

  return (
    <div className="space-y-4">
      <section className="rounded border border-gray-200 bg-white p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-sm font-medium text-gray-900">Start in Kubernetes</h2>
            <p className="mt-1 max-w-2xl text-xs text-gray-600">
              Walks this asset from registered → running and serving traffic: build images
              (including any cross-asset images in the runtime closure) → kubectl apply →
              wait for pods → run-on-apply hooks → probe endpoints. Each step is recorded
              below; the chain stops on the first failure and points at the next move.
            </p>
          </div>
          <button
            onClick={() => start.mutate()}
            disabled={start.isPending || isRunning}
            className="rounded bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-gray-300"
          >
            {isRunning ? 'Running…' : start.isPending ? 'Starting…' : 'Start in Kubernetes'}
          </button>
        </div>

        <div className="mt-3 flex flex-wrap gap-x-5 gap-y-1.5 text-xs text-gray-700">
          <label className="flex items-center gap-1.5">
            <input
              type="checkbox"
              checked={skipImageBuild}
              onChange={(e) => setSkipImageBuild(e.target.checked)}
              disabled={isRunning}
            />
            <span>Skip image build</span>
            <span className="text-gray-400">(images already in the cluster)</span>
          </label>
          <label className="flex items-center gap-1.5">
            <input
              type="checkbox"
              checked={includeRuntime}
              onChange={(e) => setIncludeRuntime(e.target.checked)}
              disabled={isRunning || skipImageBuild}
            />
            <span>Include runtime closure</span>
            <span className="text-gray-400">(build sibling images first)</span>
          </label>
          <label className="flex items-center gap-1.5">
            <input
              type="checkbox"
              checked={skipProbe}
              onChange={(e) => setSkipProbe(e.target.checked)}
              disabled={isRunning}
            />
            <span>Skip endpoint probe</span>
            <span className="text-gray-400">(ClusterIP-only)</span>
          </label>
        </div>

        {start.error && (
          <p className="mt-2 rounded bg-red-50 px-2 py-1 text-xs text-red-800">
            {(start.error as Error).message}
          </p>
        )}
      </section>

      {!j && !isRunning && mineRecent.length === 0 && (
        <p className="text-sm text-gray-500">
          No spinup runs yet. Hit “Start in Kubernetes” to launch one.
        </p>
      )}

      {j && (
        <section className="space-y-3">
          <div className="flex flex-wrap items-center gap-2 rounded border border-gray-200 bg-white px-3 py-2 text-xs">
            <span className={`rounded px-2 py-0.5 font-medium ${JOB_PILL[j.status]}`}>
              {j.status}
            </span>
            <span className="font-mono text-gray-700">job #{j.id}</span>
            {j.currentStep && j.status === 'running' && (
              <span className="text-gray-600">→ {STEP_LABEL[j.currentStep] ?? j.currentStep}</span>
            )}
            <span className="text-gray-500">{durationLabel(j)}</span>
            {j.entryUrl && j.status === 'succeeded' && (
              <a
                href={j.entryUrl}
                target="_blank"
                rel="noreferrer"
                className="ml-auto rounded bg-green-600 px-2 py-1 font-medium text-white hover:bg-green-700"
              >
                Open {j.entryUrl} ↗
              </a>
            )}
          </div>

          {j.status === 'failed' && j.error && (
            <div className="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-900">
              <p className="font-medium">Spinup failed at {failedStepLabel(j)}</p>
              <p className="mt-1 font-mono text-xs">{j.error}</p>
              <p className="mt-2 text-xs text-red-800">
                Expand the failing step below for its log lines, then check the relevant tab
                (Builds for image failures, Cluster for pod failures, Fixtures for hooks).
              </p>
            </div>
          )}

          <ol className="space-y-2">
            {renderedSteps.map((step, idx) => (
              <StepRow key={step.name} step={step} index={idx + 1} log={j.log ?? []} />
            ))}
          </ol>

          <div className="rounded border border-gray-200 bg-white">
            <button
              onClick={() => setShowLog((v) => !v)}
              className="flex w-full items-center justify-between px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-50"
            >
              <span className="font-medium">Full log ({(j.log ?? []).length} lines)</span>
              <span>{showLog ? 'hide' : 'show'}</span>
            </button>
            {showLog && (j.log ?? []).length > 0 && (
              <pre className="max-h-72 overflow-auto border-t border-gray-100 bg-gray-50 px-3 py-2 font-mono text-[11px] text-gray-800">
                {(j.log ?? []).join('\n')}
              </pre>
            )}
          </div>
        </section>
      )}

      {mineRecent.length > 1 && (
        <section className="rounded border border-gray-200 bg-white p-3 text-xs">
          <h3 className="mb-1.5 font-medium text-gray-900">Recent runs</h3>
          <ul className="divide-y divide-gray-100">
            {mineRecent.map((r) => (
              <li key={r.id} className="flex items-center gap-2 py-1.5">
                <span className={`rounded px-1.5 py-0.5 font-medium ${JOB_PILL[r.status]}`}>
                  {r.status}
                </span>
                <span className="font-mono text-gray-700">#{r.id}</span>
                <span className="text-gray-500">{shortTime(r.startedAt)}</span>
                <span className="text-gray-500">{durationLabel(r)}</span>
                <button
                  onClick={() => setActiveJobId(r.id)}
                  className={`ml-auto text-blue-700 hover:underline ${r.id === activeJobId ? 'font-semibold' : ''}`}
                >
                  {r.id === activeJobId ? 'viewing' : 'view'}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

function StepRow({ step, index, log }: { step: SpinupStep; index: number; log: string[] }) {
  const [open, setOpen] = useState(step.status === 'failed' || step.status === 'running');
  // Show this step's slice of the global log between its "→ name" and "← name" markers.
  const stepLog = useMemo(() => sliceStepLog(log, step.name), [log, step.name]);
  const dur = stepDuration(step);
  const hint = STEP_HINT[step.name];
  const isClickable = step.status !== 'pending';

  return (
    <li
      className={`rounded border ${
        step.status === 'failed' ? 'border-red-300' : 'border-gray-200'
      } bg-white`}
    >
      <button
        type="button"
        disabled={!isClickable}
        onClick={() => setOpen((v) => !v)}
        className={`flex w-full items-center gap-3 px-3 py-2 text-left text-sm ${
          isClickable ? 'hover:bg-gray-50' : 'cursor-default'
        }`}
      >
        <span className="w-5 text-center text-gray-400">{index}</span>
        <span
          className={`inline-flex h-5 w-5 items-center justify-center rounded-full text-xs font-bold ${STATUS_PILL[step.status]}`}
        >
          {STATUS_ICON[step.status]}
        </span>
        <span className="font-medium text-gray-900">{STEP_LABEL[step.name] ?? step.name}</span>
        <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium uppercase ${STATUS_PILL[step.status]}`}>
          {step.status}
        </span>
        {dur && <span className="text-xs text-gray-500">{dur}</span>}
        {step.message && (
          <span className="ml-2 truncate text-xs text-gray-600">— {step.message}</span>
        )}
        {isClickable && (
          <span className="ml-auto text-xs text-gray-500">{open ? '▾' : '▸'}</span>
        )}
      </button>
      {open && isClickable && (
        <div className="border-t border-gray-100 bg-gray-50 px-3 py-2 text-xs">
          {hint && <p className="mb-1 text-gray-600">{hint}</p>}
          {step.message && (
            <p
              className={`mb-1 font-mono text-[11px] ${
                step.status === 'failed' ? 'text-red-800' : 'text-gray-800'
              }`}
            >
              {step.message}
            </p>
          )}
          {stepLog.length > 0 ? (
            <pre className="max-h-48 overflow-auto rounded bg-white p-2 font-mono text-[11px] text-gray-800">
              {stepLog.join('\n')}
            </pre>
          ) : (
            <p className="text-gray-500">No log lines captured for this step yet.</p>
          )}
        </div>
      )}
    </li>
  );
}

function sliceStepLog(log: string[], stepName: string): string[] {
  const out: string[] = [];
  let inside = false;
  for (const line of log) {
    if (line.includes('→ ' + stepName)) {
      inside = true;
      out.push(line);
      continue;
    }
    if (line.includes('← ' + stepName + ':')) {
      out.push(line);
      inside = false;
      continue;
    }
    if (line.includes('skipped ' + stepName)) {
      out.push(line);
      continue;
    }
    if (inside) out.push(line);
  }
  return out;
}

function stepDuration(step: SpinupStep): string | null {
  if (!step.startedAt) return null;
  const start = Date.parse(step.startedAt);
  const end = step.finishedAt ? Date.parse(step.finishedAt) : Date.now();
  const secs = (end - start) / 1000;
  if (secs < 1) return '<1s';
  if (secs < 60) return `${secs.toFixed(1)}s`;
  return `${Math.floor(secs / 60)}m${Math.round(secs % 60)}s`;
}

function durationLabel(j: SpinupJob): string {
  if (!j.startedAt) return '';
  const end = j.finishedAt ? Date.parse(j.finishedAt) : Date.now();
  const secs = (end - Date.parse(j.startedAt)) / 1000;
  if (secs < 60) return `${secs.toFixed(1)}s`;
  return `${Math.floor(secs / 60)}m${Math.round(secs % 60)}s`;
}

function shortTime(iso: string | null): string {
  if (!iso) return '';
  return new Date(iso).toLocaleTimeString();
}

function failedStepLabel(j: SpinupJob): string {
  const failed = (j.steps ?? []).find((s) => s.status === 'failed');
  return failed ? STEP_LABEL[failed.name] ?? failed.name : 'unknown step';
}
