import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type DockerContainer } from '../api';
import { WorkspaceTerminalModal } from './WorkspaceTerminal';
import { ChainProgress } from './ChainProgress';
import { DiagnosticsCard } from './DiagnosticsCard';
import { FrontendTiersCard } from './FrontendTiersCard';

export function RuntimeTab({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const ports = useQuery({
    queryKey: ['ports', assetId],
    queryFn: () => api.listPortsForAsset(assetId),
  });
  const consumes = useQuery({
    queryKey: ['consumes', assetId],
    queryFn: () => api.consumesFor(assetId),
  });
  const containers = useQuery({
    queryKey: ['docker-containers', assetId],
    queryFn: () => api.dockerContainers(assetId),
    refetchInterval: 5000,
  });
  const k8sStatus = useQuery({
    queryKey: ['k8s-status', assetId],
    queryFn: () => api.k8sStatus(assetId),
    retry: false,
    refetchInterval: 5000,
  });
  const k8sLinks = useQuery({
    queryKey: ['k8s-links', assetId],
    queryFn: () => api.k8sLinks(assetId),
    retry: false,
  });

  const allocate = useMutation({
    mutationFn: (scope: 'local' | 'k8s-nodeport') => api.allocatePorts(assetId, scope, false),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ports', assetId] }),
  });
  const release = useMutation({
    mutationFn: (scope: 'local' | 'k8s-nodeport') => api.releasePorts(assetId, scope),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ports', assetId] }),
  });
  const dockerBuild = useMutation({ mutationFn: () => api.dockerBuild(assetId) });
  const dockerBuildImages = useMutation({
    mutationFn: (includeRuntime: boolean) => api.dockerBuildImages(assetId, includeRuntime),
  });
  const dockerRun = useMutation({
    mutationFn: () => api.dockerRun(assetId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['docker-containers', assetId] }),
  });
  const dockerStop = useMutation({
    mutationFn: (name: string) => api.dockerStop(assetId, name),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['docker-containers', assetId] }),
  });
  // k8s apply/delete mutations now live inside K8sSection so we can show their output inline.

  return (
    <div className="space-y-4">
      {/* Ports */}
      <section className="rounded border border-gray-200 bg-white p-4">
        <header className="mb-2 flex items-center justify-between">
          <h2 className="text-sm font-medium text-gray-900">Port reservations</h2>
          <div className="flex gap-2 text-xs">
            <button
              onClick={() => allocate.mutate('local')}
              className="rounded bg-blue-600 px-2 py-1 text-white hover:bg-blue-700"
              disabled={allocate.isPending}
            >
              Allocate local
            </button>
            <button
              onClick={() => allocate.mutate('k8s-nodeport')}
              className="rounded bg-blue-600 px-2 py-1 text-white hover:bg-blue-700"
              disabled={allocate.isPending}
            >
              Allocate k8s NodePort
            </button>
            <button
              onClick={() => release.mutate('local')}
              className="rounded border border-red-300 px-2 py-1 text-red-700 hover:bg-red-50"
            >
              Release local
            </button>
          </div>
        </header>
        {ports.data && ports.data.length === 0 && (
          <p className="text-sm text-gray-500">
            No ports allocated. Click "Allocate local" once you've cloned the workspace (run any
            build) and have a <code>devportal.yaml</code> with port slots declared.
          </p>
        )}
        {ports.data && ports.data.length > 0 && (
          <table className="w-full text-sm">
            <thead className="text-left text-xs uppercase text-gray-500">
              <tr>
                <th className="py-1">Slot</th>
                <th className="py-1">Scope</th>
                <th className="py-1">Port</th>
                <th className="py-1">Protocol</th>
              </tr>
            </thead>
            <tbody>
              {ports.data.map((p) => (
                <tr key={p.id} className="border-t border-gray-100">
                  <td className="py-1 font-mono">{p.slotName}</td>
                  <td className="py-1">{p.scope}</td>
                  <td className="py-1 font-mono">{p.port}</td>
                  <td className="py-1">{p.protocol}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {(allocate.error || release.error) && (
          <p className="mt-2 text-sm text-red-600">
            {((allocate.error || release.error) as Error)?.message}
          </p>
        )}
      </section>

      {/* Consumes */}
      <section className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-2 text-sm font-medium text-gray-900">Consumes (shared infra)</h2>
        {consumes.data && consumes.data.length === 0 && (
          <p className="text-sm text-gray-500">
            Nothing consumed yet. Add meta-asset attachments via the API or the meta-assets page.
          </p>
        )}
        {consumes.data && consumes.data.length > 0 && (
          <ul className="text-sm">
            {consumes.data.map((c) => (
              <li key={c.id} className="border-t border-gray-100 py-1">
                <span className="font-mono">{c.metaAssetId}</span>
                {c.role && <span className="ml-2 text-xs text-gray-500">role: {c.role}</span>}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Docker */}
      <section className="rounded border border-gray-200 bg-white p-4">
        <header className="mb-2 flex items-center justify-between">
          <h2 className="text-sm font-medium text-gray-900">Docker</h2>
          <div className="flex gap-2 text-xs">
            <button
              onClick={() => dockerBuild.mutate()}
              className="rounded bg-blue-600 px-2 py-1 text-white hover:bg-blue-700"
              disabled={dockerBuild.isPending}
              title="Build the single Dockerfile referenced by spec.docker (or workspace root)"
            >
              Build image
            </button>
            <button
              onClick={() => dockerBuildImages.mutate(false)}
              className="rounded bg-indigo-600 px-2 py-1 text-white hover:bg-indigo-700"
              disabled={dockerBuildImages.isPending}
              title="Build every entry under spec.docker.images for this asset"
            >
              {dockerBuildImages.isPending ? 'Building images…' : 'Build local images'}
            </button>
            <button
              onClick={() => dockerBuildImages.mutate(true)}
              className="rounded border border-indigo-300 px-2 py-1 text-indigo-700 hover:bg-indigo-50"
              disabled={dockerBuildImages.isPending}
              title="Same as 'Build local images' but also builds images declared by runtime-edge producers"
            >
              + dependents
            </button>
            <button
              onClick={() => dockerRun.mutate()}
              className="rounded bg-green-600 px-2 py-1 text-white hover:bg-green-700"
              disabled={dockerRun.isPending}
            >
              Run container
            </button>
          </div>
        </header>
        {(dockerBuild.error || dockerRun.error) && (
          <p className="mb-2 text-sm text-red-600">
            {((dockerBuild.error || dockerRun.error) as Error)?.message}
          </p>
        )}
        {dockerBuild.data && (
          <p className="mb-2 text-sm text-gray-600">
            Build kicked: #{dockerBuild.data.id} — see Builds tab for live log.
          </p>
        )}
        {dockerBuildImages.data && (
          <div className="mb-2">
            <ChainProgress buildId={dockerBuildImages.data.id} title="build-images" />
          </div>
        )}
        {dockerBuildImages.error && (
          <p className="mb-2 text-sm text-red-600">{(dockerBuildImages.error as Error).message}</p>
        )}
        {containers.data && containers.data.length === 0 ? (
          <p className="text-sm text-gray-500">No containers.</p>
        ) : containers.data ? (
          <ContainersTable containers={containers.data} onStop={(name) => dockerStop.mutate(name)} />
        ) : null}
      </section>

      {/* K8s */}
      <K8sSection assetId={assetId} k8sStatusData={k8sStatus.data} k8sLinksData={k8sLinks.data} />

      {/* Detected frontend tiers (React/Vite/Next/Vue/Angular) — scaffold Dockerfile + k8s on demand */}
      <FrontendTiersCard assetId={assetId} />

      {/* K8s diagnostics */}
      <DiagnosticsCard assetId={assetId} />

      {/* Port forwards */}
      <PortForwardsCard assetId={assetId} />

      {/* Ingress proxy */}
      <ProxyCard assetId={assetId} />

      {/* Endpoints */}
      <EndpointsCard assetId={assetId} />
    </div>
  );
}

function PortForwardsCard({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const forwards = useQuery({
    queryKey: ['port-forwards', assetId],
    queryFn: () => api.listPortForwardsForAsset(assetId),
    refetchInterval: 3_000,
  });
  const pods = useQuery({
    queryKey: ['k8s-pods', assetId],
    queryFn: () => api.k8sPods(assetId),
    retry: false,
  });

  const [showForm, setShowForm] = useState(false);
  const [pod, setPod] = useState('');
  const [containerPort, setContainerPort] = useState('8080');
  const [hostPort, setHostPort] = useState('');

  const start = useMutation({
    mutationFn: () =>
      api.startPortForward(assetId, {
        podName: pod,
        containerPort: parseInt(containerPort, 10),
        hostPort: hostPort.trim() ? parseInt(hostPort, 10) : undefined,
      }),
    onSuccess: () => {
      setShowForm(false);
      setHostPort('');
      queryClient.invalidateQueries({ queryKey: ['port-forwards', assetId] });
      queryClient.invalidateQueries({ queryKey: ['port-forwards-all'] });
      queryClient.invalidateQueries({ queryKey: ['endpoints', assetId] });
    },
  });
  const stop = useMutation({
    mutationFn: (id: number) => api.stopPortForward(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['port-forwards', assetId] });
      queryClient.invalidateQueries({ queryKey: ['port-forwards-all'] });
    },
  });

  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <header className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-gray-900">
          Port forwards
          <span className="ml-2 text-xs font-normal text-gray-500">
            tunnels a pod port to localhost via <code>kubectl port-forward</code>
          </span>
        </h2>
        <button
          onClick={() => {
            setShowForm((v) => !v);
            if (!pod && pods.data?.[0]) setPod(pods.data[0].name);
          }}
          disabled={!pods.data || pods.data.length === 0}
          className="rounded bg-blue-600 px-2 py-1 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
          title={!pods.data || pods.data.length === 0 ? 'No pods yet — kubectl apply first' : undefined}
        >
          {showForm ? 'Cancel' : '+ Add forward'}
        </button>
      </header>

      {showForm && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (pod && containerPort) start.mutate();
          }}
          className="mb-3 grid grid-cols-3 gap-2 rounded border border-blue-200 bg-blue-50 p-3 text-sm"
        >
          <label>
            <span className="block text-xs font-medium text-gray-700">Pod</span>
            <select
              value={pod}
              onChange={(e) => setPod(e.target.value)}
              className="w-full rounded border border-gray-300 px-2 py-1 text-xs font-mono"
            >
              {(pods.data ?? []).map((p) => (
                <option key={p.name} value={p.name}>{p.name}</option>
              ))}
            </select>
          </label>
          <label>
            <span className="block text-xs font-medium text-gray-700">Container port</span>
            {(() => {
              const sel = (pods.data ?? []).find((p) => p.name === pod);
              const opts: { port: number; label: string }[] = [];
              if (sel) {
                for (const c of sel.containers) {
                  for (const port of c.ports) opts.push({ port, label: `${port} (${c.name})` });
                }
              }
              if (opts.length === 0) {
                return (
                  <input
                    type="number"
                    value={containerPort}
                    onChange={(e) => setContainerPort(e.target.value)}
                    required
                    className="w-full rounded border border-gray-300 px-2 py-1 text-xs font-mono"
                  />
                );
              }
              return (
                <select
                  value={containerPort}
                  onChange={(e) => setContainerPort(e.target.value)}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-xs font-mono"
                >
                  {opts.map((o, i) => (
                    <option key={i} value={String(o.port)}>{o.label}</option>
                  ))}
                </select>
              );
            })()}
          </label>
          <label>
            <span className="block text-xs font-medium text-gray-700">Host port (blank = auto)</span>
            <input
              type="number"
              value={hostPort}
              onChange={(e) => setHostPort(e.target.value)}
              placeholder="auto"
              className="w-full rounded border border-gray-300 px-2 py-1 text-xs font-mono"
            />
          </label>
          <div className="col-span-3">
            <button
              type="submit"
              disabled={start.isPending || !pod}
              className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {start.isPending ? 'Starting…' : 'Start forward'}
            </button>
            {start.error && (
              <span className="ml-2 text-xs text-red-600">{(start.error as Error).message}</span>
            )}
          </div>
        </form>
      )}

      {forwards.data && forwards.data.length === 0 && (
        <p className="text-sm text-gray-500">No active port-forwards.</p>
      )}
      {forwards.data && forwards.data.length > 0 && (
        <table className="w-full text-sm">
          <thead className="text-left text-xs uppercase text-gray-500">
            <tr>
              <th className="py-1">Status</th>
              <th className="py-1">URL</th>
              <th className="py-1">Target</th>
              <th className="py-1"></th>
            </tr>
          </thead>
          <tbody>
            {forwards.data.map((f) => (
              <tr key={f.id} className="border-t border-gray-100">
                <td className="py-1">
                  <span
                    className={`rounded px-1.5 py-0.5 text-xs ${
                      f.status === 'running'
                        ? 'bg-green-100 text-green-800'
                        : f.status === 'failed'
                        ? 'bg-red-100 text-red-800'
                        : 'bg-gray-200 text-gray-700'
                    }`}
                  >
                    {f.status}
                  </span>
                </td>
                <td className="py-1 font-mono text-xs">
                  <a
                    href={`http://localhost:${f.hostPort}/`}
                    target="_blank"
                    rel="noreferrer"
                    className="text-blue-700 hover:underline"
                  >
                    http://localhost:{f.hostPort}/
                  </a>
                </td>
                <td className="py-1 font-mono text-xs">
                  {f.namespace}/{f.podName}:{f.containerPort}
                </td>
                <td className="py-1 text-right">
                  {f.status === 'running' && (
                    <button
                      onClick={() => stop.mutate(f.id)}
                      className="text-xs text-red-600 hover:underline"
                    >
                      stop
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function K8sSection({
  assetId,
  k8sStatusData,
  k8sLinksData,
}: {
  assetId: string;
  k8sStatusData: { namespace: string; pods: { name: string; phase: string }[]; services: { name: string; type: string; ports: number[] }[] } | undefined;
  k8sLinksData: { k9s: string; grafana: string | null; kubectlLogs: string } | undefined;
}) {
  const queryClient = useQueryClient();
  const runtimePlan = useQuery({
    queryKey: ['k8s-runtime-plan', assetId],
    queryFn: () => api.k8sRuntimePlan(assetId),
    retry: false,
  });
  // Producers in the runtime closure (excludes the root). When non-empty, the UI shows the
  // composite-apply panel.
  const runtimeProducers = runtimePlan.data?.steps.filter((s) => !s.isRoot) ?? [];
  const [skipped, setSkipped] = useState<Set<string>>(new Set());
  const toggleSkip = (id: string) =>
    setSkipped((prev) => {
      const n = new Set(prev);
      n.has(id) ? n.delete(id) : n.add(id);
      return n;
    });
  const invalidateK8s = () => {
    queryClient.invalidateQueries({ queryKey: ['k8s-status', assetId] });
    queryClient.invalidateQueries({ queryKey: ['k8s-pods', assetId] });
    queryClient.invalidateQueries({ queryKey: ['endpoints', assetId] });
    queryClient.invalidateQueries({ queryKey: ['k8s-runtime-plan', assetId] });
  };
  const apply = useMutation({
    mutationFn: () => api.k8sApply(assetId),
    onSuccess: invalidateK8s,
  });
  const k8sDelete = useMutation({
    mutationFn: () => api.k8sDelete(assetId),
    onSuccess: invalidateK8s,
  });
  const applyComposite = useMutation({
    mutationFn: () => api.k8sApplyComposite(assetId, Array.from(skipped)),
    onSuccess: invalidateK8s,
  });
  const deleteComposite = useMutation({
    mutationFn: () => api.k8sDeleteComposite(assetId, Array.from(skipped)),
    onSuccess: invalidateK8s,
  });

  const scaffold = useMutation({
    mutationFn: () => api.k8sScaffold(assetId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['panels', assetId] }),
  });
  const render = useMutation({ mutationFn: () => api.k8sRender(assetId) });

  const verifyM = useMutation({ mutationFn: () => api.verifyAsset(assetId, 'docker') });

  const [commitOpen, setCommitOpen] = useState(false);
  const [commitMode, setCommitMode] = useState<'render' | 'workspace'>('render');
  const [branch, setBranch] = useState('');
  const [message, setMessage] = useState('');
  const [push, setPush] = useState(false);
  const [shellOpen, setShellOpen] = useState(false);
  const commit = useMutation({
    mutationFn: () =>
      commitMode === 'render'
        ? api.k8sCommitRender(assetId, branch || undefined, message || undefined, push)
        : api.k8sCommitWorkspace(assetId, branch || undefined, message || undefined, push),
  });

  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <header className="mb-2 flex items-center justify-between flex-wrap gap-2">
        <h2 className="text-sm font-medium text-gray-900">Kubernetes</h2>
        <div className="flex gap-2 text-xs flex-wrap">
          <button
            onClick={() => scaffold.mutate()}
            className="rounded bg-amber-600 px-2 py-1 text-white hover:bg-amber-700"
            disabled={scaffold.isPending}
            title="Generate k8s/deployment.yaml + k8s/service.yaml from inferred ports (no commit)"
          >
            {scaffold.isPending ? 'Scaffolding…' : 'Scaffold k8s'}
          </button>
          <button
            onClick={() => render.mutate()}
            className="rounded border border-gray-300 px-2 py-1 text-gray-700 hover:bg-gray-100"
            disabled={render.isPending}
            title="Render manifests with allocated NodePorts (no apply)"
          >
            {render.isPending ? 'Rendering…' : 'Render preview'}
          </button>
          <button
            onClick={() => { setCommitOpen(true); setCommitMode('render'); }}
            className="rounded bg-purple-600 px-2 py-1 text-white hover:bg-purple-700"
          >
            Commit to branch
          </button>
          <button
            onClick={() => verifyM.mutate()}
            className="rounded bg-green-600 px-2 py-1 text-white hover:bg-green-700"
            disabled={verifyM.isPending}
            title="Build → run → http probe (docker stage)"
          >
            {verifyM.isPending ? 'Verifying…' : 'Verify'}
          </button>
          <button
            onClick={() => apply.mutate()}
            className="rounded bg-blue-600 px-2 py-1 text-white hover:bg-blue-700"
            disabled={apply.isPending}
          >
            {apply.isPending ? 'Applying…' : 'kubectl apply'}
          </button>
          <button
            onClick={() => k8sDelete.mutate()}
            className="rounded border border-red-300 px-2 py-1 text-red-700 hover:bg-red-50"
            disabled={k8sDelete.isPending}
          >
            kubectl delete
          </button>
          <button
            onClick={() => setShellOpen(true)}
            className="rounded bg-slate-700 px-2 py-1 text-white hover:bg-slate-800"
            title="Open a host shell rooted at this asset's workspace"
          >
            Workspace shell
          </button>
        </div>
      </header>

      <WorkspaceTerminalModal assetId={assetId} open={shellOpen} onClose={() => setShellOpen(false)} />

      {scaffold.data && (
        <div className="mb-2 rounded border border-amber-200 bg-amber-50 p-2 text-xs">
          Scaffolded: {scaffold.data.files.join(', ')}. Click <em>Commit to branch</em> with mode "workspace" to ship them.
        </div>
      )}
      {scaffold.error && <p className="mb-2 text-sm text-red-600">{(scaffold.error as Error).message}</p>}

      {render.data && (
        <div className="mb-2 rounded border border-gray-200 bg-gray-50 p-2 text-xs">
          Rendered to <code className="font-mono">{render.data.renderedDir}</code> (source: {render.data.source}).
        </div>
      )}
      {render.error && <p className="mb-2 text-sm text-red-600">{(render.error as Error).message}</p>}

      {runtimeProducers.length > 0 && (
        <div className="mb-3 rounded border border-blue-200 bg-blue-50/40 p-3 text-sm">
          <header className="mb-2 flex items-center justify-between">
            <h3 className="text-sm font-medium text-gray-900">
              Bring up dependent services ({runtimeProducers.length})
            </h3>
            <span className="text-xs text-gray-500">
              from <code className="font-mono">kind=runtime</code> dependency edges
            </span>
          </header>
          <ul className="mb-3 space-y-1">
            {runtimeProducers.map((s) => (
              <li
                key={s.assetId}
                className="flex items-center gap-2 rounded border border-gray-200 bg-white px-2 py-1 text-xs"
              >
                <input
                  type="checkbox"
                  checked={!skipped.has(s.assetId)}
                  onChange={() => toggleSkip(s.assetId)}
                  disabled={!s.hasManifests}
                  className="rounded border-gray-300"
                  title={s.hasManifests ? 'Include in apply / delete' : 'No k8s manifests — always skipped'}
                />
                <code className="font-mono">{s.assetId}</code>
                <span className="text-gray-500">
                  → namespace <code className="font-mono">{s.namespace}</code>
                </span>
                {!s.hasManifests && (
                  <span className="ml-auto rounded bg-gray-200 px-1.5 py-0.5 text-[11px] text-gray-700">
                    no manifests
                  </span>
                )}
              </li>
            ))}
          </ul>
          <div className="flex gap-2">
            <button
              onClick={() => applyComposite.mutate()}
              disabled={applyComposite.isPending}
              className="rounded bg-blue-600 px-2 py-1 text-xs text-white hover:bg-blue-700 disabled:bg-gray-400"
              title="Apply each producer in topo order, then this asset. Stops on the first failure."
            >
              {applyComposite.isPending ? 'Applying chain…' : 'Apply with dependents'}
            </button>
            <button
              onClick={() => deleteComposite.mutate()}
              disabled={deleteComposite.isPending}
              className="rounded border border-red-300 px-2 py-1 text-xs text-red-700 hover:bg-red-50 disabled:opacity-50"
              title="Delete this asset, then each producer in reverse topo order."
            >
              {deleteComposite.isPending ? 'Deleting chain…' : 'Delete with dependents'}
            </button>
          </div>
          {applyComposite.data && (
            <CompositeResult title="apply" results={applyComposite.data} />
          )}
          {applyComposite.error && (
            <p className="mt-2 text-xs text-red-700">apply chain failed: {(applyComposite.error as Error).message}</p>
          )}
          {deleteComposite.data && (
            <CompositeResult title="delete" results={deleteComposite.data} />
          )}
          {deleteComposite.error && (
            <p className="mt-2 text-xs text-red-700">delete chain failed: {(deleteComposite.error as Error).message}</p>
          )}
        </div>
      )}

      {verifyM.data && (
        <div className={`mb-2 rounded border p-2 text-xs ${verifyM.data.passed ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'}`}>
          <div className="font-medium">
            {verifyM.data.passed ? '✅ verify passed' : `❌ verify failed at ${verifyM.data.failedAt}`}
            {' — '} {verifyM.data.summary}
          </div>
          <ul className="mt-1 font-mono">
            {verifyM.data.steps.map((s, i) => (
              <li key={i}>
                {s.ok ? '✓' : '✗'} {s.name} ({s.durationMs}ms) — {s.detail}
              </li>
            ))}
          </ul>
        </div>
      )}
      {verifyM.error && <p className="mb-2 text-sm text-red-600">{(verifyM.error as Error).message}</p>}

      {(apply.data || apply.error || k8sDelete.data || k8sDelete.error) && (
        <div
          className={`mb-2 rounded border p-2 text-xs ${
            apply.error || k8sDelete.error ? 'border-red-200 bg-red-50' : 'border-green-200 bg-green-50'
          }`}
        >
          {apply.data && (() => {
            const out = String(apply.data.output ?? '');
            const lines = out.split('\n').filter(Boolean);
            const resources = lines
              .map((l) => l.match(/^(\S+\/\S+)\s+(\w+)/))
              .filter(Boolean)
              .map((m) => ({ resource: m![1], action: m![2].toUpperCase() }));
            return (
              <>
                <div className="font-medium">
                  ✅ kubectl apply — namespace <code className="font-mono">{String(apply.data.namespace ?? 'default')}</code>
                </div>
                {resources.length > 0 ? (
                  <ul className="mt-1 font-mono text-xs">
                    {resources.map((r, i) => (
                      <li key={i}>
                        <span className={r.action === 'CREATED' ? 'text-green-700' : r.action === 'UNCHANGED' ? 'text-gray-500' : 'text-blue-700'}>
                          {r.action.padEnd(10)}
                        </span>
                        <span>{r.resource}</span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <pre className="mt-1 whitespace-pre-wrap font-mono">{out}</pre>
                )}
                <div className="mt-2 flex flex-wrap gap-2">
                  <a
                    href="#cluster"
                    onClick={(e) => {
                      e.preventDefault();
                      window.dispatchEvent(new CustomEvent('devportal:goto-tab', { detail: 'cluster' }));
                    }}
                    className="rounded bg-blue-600 px-2 py-1 text-xs text-white hover:bg-blue-700"
                  >
                    View resources in cluster →
                  </a>
                  <button
                    onClick={() => setShellOpen(true)}
                    className="rounded bg-slate-700 px-2 py-1 text-xs text-white hover:bg-slate-800"
                  >
                    Open workspace shell
                  </button>
                </div>
              </>
            );
          })()}
          {apply.error && <p className="text-red-700">apply failed: {(apply.error as Error).message}</p>}
          {k8sDelete.data && (
            <>
              <div className="font-medium">🗑 kubectl delete</div>
              <pre className="mt-1 whitespace-pre-wrap font-mono">{String(k8sDelete.data.output ?? '')}</pre>
            </>
          )}
          {k8sDelete.error && <p className="text-red-700">delete failed: {(k8sDelete.error as Error).message}</p>}
        </div>
      )}

      {commitOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-lg rounded-lg bg-white shadow-xl">
            <header className="flex items-center justify-between border-b border-gray-200 px-4 py-3">
              <h3 className="text-sm font-medium text-gray-900">Commit k8s changes — {assetId}</h3>
              <button onClick={() => setCommitOpen(false)} className="text-gray-500">✕</button>
            </header>
            <div className="space-y-3 px-4 py-3 text-sm">
              <label className="block">
                <span className="text-xs font-medium text-gray-700">Source</span>
                <select
                  value={commitMode}
                  onChange={(e) => setCommitMode(e.target.value as 'render' | 'workspace')}
                  className="mt-1 w-full rounded border border-gray-300 px-3 py-1.5"
                >
                  <option value="render">Render: rewrite originals with allocated NodePorts</option>
                  <option value="workspace">Workspace: commit current edits (e.g. after Scaffold)</option>
                </select>
              </label>
              <label className="block">
                <span className="text-xs font-medium text-gray-700">Branch</span>
                <input
                  value={branch}
                  onChange={(e) => setBranch(e.target.value)}
                  placeholder="devportal/k8s-... (auto-named if blank)"
                  className="mt-1 w-full rounded border border-gray-300 px-3 py-1.5 font-mono text-xs"
                />
              </label>
              <label className="block">
                <span className="text-xs font-medium text-gray-700">Commit message</span>
                <input
                  value={message}
                  onChange={(e) => setMessage(e.target.value)}
                  placeholder="devportal: update k8s manifests with allocated ports"
                  className="mt-1 w-full rounded border border-gray-300 px-3 py-1.5"
                />
              </label>
              <label className="flex items-center gap-2 text-xs">
                <input type="checkbox" checked={push} onChange={(e) => setPush(e.target.checked)} />
                Push branch to <code>origin</code> after commit (uses your stored PAT)
              </label>
              {commit.data && (
                <div className="rounded border border-green-200 bg-green-50 p-2 text-xs">
                  Committed <code>{commit.data.commit?.slice(0, 7) ?? '(no changes)'}</code> on <code>{commit.data.branch}</code>
                  {commit.data.filesChanged.length > 0 && <> — {commit.data.filesChanged.length} file(s)</>}
                  {commit.data.pushed && <div className="text-green-700">Pushed to origin/{commit.data.branch}</div>}
                  <div className="mt-2 flex flex-wrap gap-2">
                    {commit.data.prSuggestion && (
                      <a href={commit.data.prSuggestion} target="_blank" rel="noreferrer" className="rounded bg-blue-600 px-2 py-1 text-xs text-white hover:bg-blue-700">
                        Open PR →
                      </a>
                    )}
                    <button
                      onClick={() => setShellOpen(true)}
                      className="rounded bg-slate-700 px-2 py-1 text-xs text-white hover:bg-slate-800"
                      title={`Open shell at branch ${commit.data.branch}`}
                    >
                      Open shell on {commit.data.branch}
                    </button>
                  </div>
                </div>
              )}
              {commit.error && <p className="text-xs text-red-600">{(commit.error as Error).message}</p>}
            </div>
            <footer className="flex items-center justify-end gap-2 border-t border-gray-200 px-4 py-3">
              <button onClick={() => setCommitOpen(false)} className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-100">
                Close
              </button>
              <button
                onClick={() => commit.mutate()}
                disabled={commit.isPending}
                className="rounded bg-purple-600 px-3 py-1.5 text-sm text-white hover:bg-purple-700 disabled:opacity-50"
              >
                {commit.isPending ? 'Committing…' : push ? 'Commit & push' : 'Commit (local only)'}
              </button>
            </footer>
          </div>
        </div>
      )}

      {k8sStatusData && (
        <div className="text-sm">
          <p className="text-xs text-gray-500">Namespace: {k8sStatusData.namespace}</p>
          <h3 className="mt-2 text-xs font-medium uppercase text-gray-500">Pods</h3>
          {k8sStatusData.pods.length === 0 ? (
            <p className="text-gray-500">none</p>
          ) : (
            <ul>
              {k8sStatusData.pods.map((p) => (
                <li key={p.name} className="font-mono">
                  {p.name} <span className="text-xs text-gray-500">{p.phase}</span>
                </li>
              ))}
            </ul>
          )}
          <h3 className="mt-2 text-xs font-medium uppercase text-gray-500">Services</h3>
          {k8sStatusData.services.length === 0 ? (
            <p className="text-gray-500">none</p>
          ) : (
            <ul>
              {k8sStatusData.services.map((s) => (
                <li key={s.name} className="font-mono">
                  {s.name}{' '}
                  <span className="text-xs text-gray-500">
                    {s.type} :{s.ports.join(',')}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {k8sLinksData && (
        <div className="mt-3 space-y-1 text-xs">
          <p className="font-medium text-gray-700">Monitoring</p>
          <code className="block rounded bg-gray-100 px-2 py-1">{k8sLinksData.k9s}</code>
          <code className="block rounded bg-gray-100 px-2 py-1">{k8sLinksData.kubectlLogs}</code>
          {k8sLinksData.grafana && (
            <a
              href={k8sLinksData.grafana}
              target="_blank"
              rel="noreferrer"
              className="text-blue-700 hover:underline"
            >
              Open Grafana →
            </a>
          )}
        </div>
      )}
    </section>
  );
}

function ProxyCard({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const proxy = useQuery({
    queryKey: ['proxy', assetId],
    queryFn: () => api.getAssetProxy(assetId),
  });
  const ports = useQuery({
    queryKey: ['ports', assetId],
    queryFn: () => api.listPortsForAsset(assetId),
  });
  const current = proxy.data?.proxy ?? null;
  const slotOptions = Array.from(new Set((ports.data ?? []).map((p) => p.slotName)));

  const [path, setPath] = useState('');
  const [portSlot, setPortSlot] = useState('http');
  const [stripPrefix, setStripPrefix] = useState(true);
  const [host, setHost] = useState('');
  const [editing, setEditing] = useState(false);

  // Hydrate the form whenever the asset's current proxy changes (initial load + post-mutation).
  useEffect(() => {
    if (current) {
      setPath(current.path ?? '');
      setPortSlot(current.portSlot);
      setStripPrefix(current.stripPrefix);
      setHost(current.host ?? '');
    } else {
      setPath(`/${assetId}`);
      setPortSlot(slotOptions[0] ?? 'http');
      setStripPrefix(true);
      setHost('');
    }
    setEditing(false);
  }, [current, assetId, slotOptions.join(',')]);

  const trimmedPath = path.trim();
  const trimmedHost = host.trim();
  const previewUrl = trimmedHost
    ? `http://${trimmedHost}${trimmedPath || '/'}`
    : trimmedPath
    ? `http://localhost${trimmedPath}`
    : '';
  const canSave = portSlot.trim() && (trimmedPath || trimmedHost);

  const save = useMutation({
    mutationFn: () =>
      api.setAssetProxy(assetId, {
        path: trimmedPath || null,
        portSlot: portSlot.trim(),
        stripPrefix,
        host: trimmedHost || null,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['proxy', assetId] });
      queryClient.invalidateQueries({ queryKey: ['workspace-status', assetId] });
      queryClient.invalidateQueries({ queryKey: ['proxy-routes'] });
      queryClient.invalidateQueries({ queryKey: ['endpoints', assetId] });
    },
  });
  const remove = useMutation({
    mutationFn: () => api.removeAssetProxy(assetId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['proxy', assetId] });
      queryClient.invalidateQueries({ queryKey: ['workspace-status', assetId] });
      queryClient.invalidateQueries({ queryKey: ['proxy-routes'] });
      queryClient.invalidateQueries({ queryKey: ['endpoints', assetId] });
    },
  });

  const showForm = editing || !current;

  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <header className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-gray-900">Ingress proxy</h2>
        <span className="text-xs text-gray-500">
          {current
            ? current.host && !current.path
              ? `host ${current.host}`
              : current.host
              ? `${current.host}${current.path}`
              : `mounted at ${current.path}`
            : 'not enabled'}
        </span>
      </header>
      <p className="mb-3 text-xs text-gray-500">
        Expose this asset under the shared Traefik ingress. Pick a <strong>path</strong> (mount at{' '}
        <code>/{assetId}</code> on any host), a <strong>host</strong> (claim a hostname like{' '}
        <code>{assetId}.example.com</code> at the root), or both. The change is written to{' '}
        <code>devportal.yaml</code> in the workspace and surfaces in the <em>Changes</em> tab —
        review and commit it there to publish.
      </p>

      {!showForm && current && (
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span className="rounded bg-green-100 px-2 py-0.5 text-xs text-green-800">enabled</span>
          <span className="font-mono">
            {(current.host ?? '') + (current.path ?? '/')}
          </span>
          <span className="text-xs text-gray-500">
            slot <span className="font-mono">{current.portSlot}</span>
            {current.path && current.path !== '/' ? (
              <> · strip <span className="font-mono">{current.stripPrefix ? 'yes' : 'no'}</span></>
            ) : null}
          </span>
          <button
            onClick={() => setEditing(true)}
            className="ml-auto rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50"
          >
            Edit
          </button>
          <button
            onClick={() => remove.mutate()}
            disabled={remove.isPending}
            className="rounded border border-red-300 px-2 py-1 text-xs text-red-700 hover:bg-red-50 disabled:opacity-50"
          >
            {remove.isPending ? 'Removing…' : 'Disable'}
          </button>
        </div>
      )}

      {showForm && (
        <div className="space-y-2">
          <div className="flex flex-wrap gap-2 text-sm">
            <label className="flex flex-col text-xs text-gray-600">
              Path
              <input
                value={path}
                onChange={(e) => setPath(e.target.value)}
                placeholder={trimmedHost ? '/ (default)' : `/${assetId}`}
                className="mt-0.5 w-44 rounded border border-gray-300 px-2 py-1 font-mono text-sm"
              />
            </label>
            <label className="flex flex-col text-xs text-gray-600">
              Port slot
              {slotOptions.length > 0 ? (
                <select
                  value={portSlot}
                  onChange={(e) => setPortSlot(e.target.value)}
                  className="mt-0.5 w-32 rounded border border-gray-300 px-2 py-1 font-mono text-sm"
                >
                  {slotOptions.map((s) => (
                    <option key={s} value={s}>{s}</option>
                  ))}
                  {!slotOptions.includes(portSlot) && portSlot && (
                    <option value={portSlot}>{portSlot}</option>
                  )}
                </select>
              ) : (
                <input
                  value={portSlot}
                  onChange={(e) => setPortSlot(e.target.value)}
                  placeholder="http"
                  className="mt-0.5 w-32 rounded border border-gray-300 px-2 py-1 font-mono text-sm"
                />
              )}
            </label>
            <label className="flex flex-col text-xs text-gray-600">
              Host (optional)
              <input
                value={host}
                onChange={(e) => setHost(e.target.value)}
                placeholder="any"
                className="mt-0.5 w-56 rounded border border-gray-300 px-2 py-1 font-mono text-sm"
              />
            </label>
            <label
              className={`flex items-center gap-1 text-xs self-end pb-1 ${
                trimmedPath && trimmedPath !== '/' ? 'text-gray-700' : 'text-gray-400'
              }`}
              title={
                trimmedPath && trimmedPath !== '/'
                  ? 'Strip the path prefix before forwarding'
                  : 'Only meaningful when a non-/ path is set'
              }
            >
              <input
                type="checkbox"
                checked={stripPrefix}
                disabled={!trimmedPath || trimmedPath === '/'}
                onChange={(e) => setStripPrefix(e.target.checked)}
              />
              Strip prefix
            </label>
          </div>
          {previewUrl && (
            <p className="text-xs text-gray-500">
              URL: <span className="font-mono">{previewUrl}</span>
              {!trimmedHost && (
                <> (any host — also reachable as <code>http://&lt;your-ip&gt;{trimmedPath}</code>)</>
              )}
            </p>
          )}
          <div className="flex items-center gap-2">
            <button
              onClick={() => save.mutate()}
              disabled={save.isPending || !canSave}
              className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {save.isPending ? 'Saving…' : current ? 'Save changes' : 'Enable proxy'}
            </button>
            {current && (
              <button
                onClick={() => setEditing(false)}
                className="rounded border border-gray-300 px-3 py-1 text-xs hover:bg-gray-50"
              >
                Cancel
              </button>
            )}
            {save.error && (
              <span className="text-xs text-red-600">{(save.error as Error).message}</span>
            )}
            {remove.error && (
              <span className="text-xs text-red-600">{(remove.error as Error).message}</span>
            )}
          </div>
        </div>
      )}
    </section>
  );
}

function EndpointsCard({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const ep = useQuery({
    queryKey: ['endpoints', assetId],
    queryFn: () => api.endpoints(assetId),
    refetchInterval: 5000,
  });
  const expose = useMutation({
    mutationFn: (vars: { podName: string; containerPort: number }) =>
      api.startPortForward(assetId, vars),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['endpoints', assetId] });
      queryClient.invalidateQueries({ queryKey: ['port-forwards', assetId] });
      queryClient.invalidateQueries({ queryKey: ['port-forwards-all'] });
    },
  });
  if (ep.isLoading) return null;
  if (ep.error) return null;
  const eps = ep.data?.endpoints ?? [];

  // Group by host-accessibility — what the user really wants to know.
  const hostAccessible = eps.filter((e) => e.hostAccessible);
  const inCluster = eps.filter((e) => !e.hostAccessible && e.scope !== 'external');
  const external = eps.filter((e) => e.scope === 'external');

  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <header className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-gray-900">Endpoints</h2>
        <span className="text-xs text-gray-500">{eps.length} URL{eps.length === 1 ? '' : 's'}</span>
      </header>
      {eps.length === 0 && (
        <p className="text-sm text-gray-500">
          No URLs derivable yet. Allocate ports / apply k8s / register a repo URL to populate this list.
        </p>
      )}

      {hostAccessible.length > 0 && (
        <Group title="🟢 Host accessible — open these from your browser" rows={hostAccessible} accent="green" />
      )}
      {inCluster.length > 0 && (
        <Group
          title="🔒 In-cluster only — not reachable from your host as-is"
          rows={inCluster}
          accent="amber"
          onExpose={(hint) =>
            expose.mutate({ podName: hint.podName, containerPort: hint.containerPort })
          }
        />
      )}
      {external.length > 0 && (
        <Group title="🔗 External" rows={external} accent="gray" />
      )}
      {expose.error && (
        <p className="mt-2 text-xs text-red-600">expose failed: {(expose.error as Error).message}</p>
      )}
      {expose.data && (
        <p className="mt-2 text-xs text-green-700">
          Started port-forward → http://localhost:{expose.data.hostPort}/
        </p>
      )}
    </section>
  );
}

function Group({
  title,
  rows,
  accent,
  onExpose,
}: {
  title: string;
  rows: NonNullable<Awaited<ReturnType<typeof api.endpoints>>>['endpoints'];
  accent: 'green' | 'amber' | 'gray';
  onExpose?: (hint: { podName: string; containerPort: number }) => void;
}) {
  const headerCls =
    accent === 'green'
      ? 'bg-green-50 border-green-200 text-green-900'
      : accent === 'amber'
      ? 'bg-amber-50 border-amber-200 text-amber-900'
      : 'bg-gray-50 border-gray-200 text-gray-700';
  return (
    <div className={`mb-3 rounded border ${headerCls}`}>
      <h3 className="border-b border-current/10 px-3 py-1 text-xs font-medium">{title}</h3>
      <ul className="divide-y divide-current/10">
        {rows.map((e, i) => (
          <li key={i} className="flex items-center gap-2 px-3 py-1.5 text-xs">
            <span
              className={`rounded px-1.5 py-0.5 text-[10px] ${
                e.live ? 'bg-green-100 text-green-800' : 'bg-gray-200 text-gray-600'
              }`}
              title={e.live ? 'something is currently listening' : 'nothing listening yet'}
            >
              {e.live ? 'live' : 'idle'}
            </span>
            <span className="font-medium">{e.label}</span>
            <a
              href={e.url}
              target="_blank"
              rel="noreferrer"
              className="font-mono text-blue-700 hover:underline truncate"
            >
              {e.url}
            </a>
            <button
              onClick={() => navigator.clipboard.writeText(e.url)}
              className="text-gray-400 hover:text-gray-700"
              title="Copy URL"
            >
              ⧉
            </button>
            {e.exposeHint && onExpose && (
              <button
                onClick={() => onExpose(e.exposeHint!)}
                className="rounded bg-blue-600 px-1.5 py-0.5 text-[10px] text-white hover:bg-blue-700"
                title="Start kubectl port-forward to expose this on a host port"
              >
                Expose to host
              </button>
            )}
            <span className="ml-auto text-[10px] text-gray-500">{e.origin}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ContainersTable({
  containers,
  onStop,
}: {
  containers: DockerContainer[];
  onStop: (name: string) => void;
}) {
  return (
    <table className="w-full text-sm">
      <thead className="text-left text-xs uppercase text-gray-500">
        <tr>
          <th className="py-1">Name</th>
          <th className="py-1">Image</th>
          <th className="py-1">Status</th>
          <th className="py-1">Ports</th>
          <th className="py-1"></th>
        </tr>
      </thead>
      <tbody>
        {containers.map((c) => (
          <tr key={c.id} className="border-t border-gray-100">
            <td className="py-1 font-mono">{c.name}</td>
            <td className="py-1 text-xs">{c.image}</td>
            <td className="py-1 text-xs">{c.status}</td>
            <td className="py-1 text-xs font-mono">{c.ports.join(', ')}</td>
            <td className="py-1 text-right">
              <button onClick={() => onStop(c.name)} className="text-xs text-red-600 hover:underline">
                stop+rm
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Renders the result of a composite k8s apply / delete: one row per step (asset) with a counts
 * summary parsed from the kubectl output. Skipped steps show their reason; failed steps show
 * the kubectl exit code and full output.
 */
function CompositeResult({
  title,
  results,
}: {
  title: 'apply' | 'delete';
  results: Record<string, unknown>[];
}) {
  return (
    <div className="mt-2 space-y-1">
      {results.map((r, i) => {
        const asset = String(r.asset ?? '?');
        if (r.skipped) {
          return (
            <div key={i} className="rounded border border-gray-200 bg-white px-2 py-1 text-xs text-gray-600">
              <span className="font-mono">{asset}</span>{' '}
              <span className="rounded bg-gray-100 px-1 text-[11px]">skipped: {String(r.skipped)}</span>
            </div>
          );
        }
        const out = String(r.output ?? '');
        const exitCode = r.exitCode === undefined ? null : Number(r.exitCode);
        // kubectl output: "<resource>/<name> <action>". Tally per-action so the result is at-a-glance.
        const counts: Record<string, number> = {};
        for (const line of out.split('\n')) {
          const m = line.match(/^\S+\/\S+\s+(\w+)/);
          if (m) counts[m[1]] = (counts[m[1]] ?? 0) + 1;
        }
        const ok = exitCode === 0 || exitCode === null;
        return (
          <div
            key={i}
            className={`rounded border px-2 py-1 text-xs ${ok ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'}`}
          >
            <div className="flex flex-wrap items-center gap-2">
              <span>{ok ? '✅' : '❌'}</span>
              <span className="font-mono font-medium">{asset}</span>
              {r.namespace ? (
                <span className="text-gray-500">
                  → ns <code className="font-mono">{String(r.namespace)}</code>
                </span>
              ) : null}
              {Object.keys(counts).length > 0 && (
                <span className="ml-auto flex gap-2 text-[11px] text-gray-600">
                  {Object.entries(counts).map(([action, n]) => (
                    <span key={action}>
                      {action} <strong>{String(n)}</strong>
                    </span>
                  ))}
                </span>
              )}
            </div>
            {!ok && (
              <pre className="mt-1 max-h-48 overflow-auto whitespace-pre-wrap font-mono text-[11px]">
                {`${title} exit ${exitCode}\n${out}`}
              </pre>
            )}
          </div>
        );
      })}
    </div>
  );
}
