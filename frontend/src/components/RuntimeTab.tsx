import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type DockerContainer } from '../api';

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
  const dockerRun = useMutation({
    mutationFn: () => api.dockerRun(assetId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['docker-containers', assetId] }),
  });
  const dockerStop = useMutation({
    mutationFn: (name: string) => api.dockerStop(assetId, name),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['docker-containers', assetId] }),
  });
  const k8sApply = useMutation({
    mutationFn: () => api.k8sApply(assetId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['k8s-status', assetId] }),
  });
  const k8sDelete = useMutation({
    mutationFn: () => api.k8sDelete(assetId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['k8s-status', assetId] }),
  });

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
            >
              Build image
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
        {containers.data && containers.data.length === 0 ? (
          <p className="text-sm text-gray-500">No containers.</p>
        ) : containers.data ? (
          <ContainersTable containers={containers.data} onStop={(name) => dockerStop.mutate(name)} />
        ) : null}
      </section>

      {/* K8s */}
      <section className="rounded border border-gray-200 bg-white p-4">
        <header className="mb-2 flex items-center justify-between">
          <h2 className="text-sm font-medium text-gray-900">Kubernetes</h2>
          <div className="flex gap-2 text-xs">
            <button
              onClick={() => k8sApply.mutate()}
              className="rounded bg-blue-600 px-2 py-1 text-white hover:bg-blue-700"
              disabled={k8sApply.isPending}
            >
              kubectl apply
            </button>
            <button
              onClick={() => k8sDelete.mutate()}
              className="rounded border border-red-300 px-2 py-1 text-red-700 hover:bg-red-50"
              disabled={k8sDelete.isPending}
            >
              kubectl delete
            </button>
          </div>
        </header>
        {(k8sApply.error || k8sDelete.error || k8sStatus.error) && (
          <p className="mb-2 text-sm text-red-600">
            {((k8sApply.error || k8sDelete.error || k8sStatus.error) as Error)?.message}
          </p>
        )}
        {k8sStatus.data && (
          <div className="text-sm">
            <p className="text-xs text-gray-500">Namespace: {k8sStatus.data.namespace}</p>
            <h3 className="mt-2 text-xs font-medium uppercase text-gray-500">Pods</h3>
            {k8sStatus.data.pods.length === 0 ? (
              <p className="text-gray-500">none</p>
            ) : (
              <ul>
                {k8sStatus.data.pods.map((p) => (
                  <li key={p.name} className="font-mono">
                    {p.name} <span className="text-xs text-gray-500">{p.phase}</span>
                  </li>
                ))}
              </ul>
            )}
            <h3 className="mt-2 text-xs font-medium uppercase text-gray-500">Services</h3>
            {k8sStatus.data.services.length === 0 ? (
              <p className="text-gray-500">none</p>
            ) : (
              <ul>
                {k8sStatus.data.services.map((s) => (
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
        {k8sLinks.data && (
          <div className="mt-3 space-y-1 text-xs">
            <p className="font-medium text-gray-700">Monitoring</p>
            <code className="block rounded bg-gray-100 px-2 py-1">{k8sLinks.data.k9s}</code>
            <code className="block rounded bg-gray-100 px-2 py-1">{k8sLinks.data.kubectlLogs}</code>
            {k8sLinks.data.grafana && (
              <a
                href={k8sLinks.data.grafana}
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
