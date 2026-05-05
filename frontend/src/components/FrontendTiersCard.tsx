import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type FrontendTier } from '../api';

const FRAMEWORK_BADGE: Record<FrontendTier['framework'], string> = {
  VITE: 'bg-purple-100 text-purple-800',
  CRA: 'bg-blue-100 text-blue-800',
  NEXT: 'bg-gray-900 text-white',
  VUE_CLI: 'bg-green-100 text-green-800',
  ANGULAR: 'bg-red-100 text-red-800',
  GENERIC_REACT: 'bg-cyan-100 text-cyan-800',
};

/**
 * Lists React/Vite/Next/Vue/Angular tiers detected in the workspace, with a one-click scaffold
 * button per tier that doesn't yet have a Dockerfile. Scaffolding is idempotent — re-clicking
 * a partially-scaffolded tier only writes the missing files.
 */
export function FrontendTiersCard({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const tiers = useQuery({
    queryKey: ['frontend-tiers', assetId],
    queryFn: () => api.listFrontendTiers(assetId),
    retry: false,
  });
  const scaffold = useMutation({
    mutationFn: (path: string) => api.scaffoldFrontend(assetId, path),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['frontend-tiers', assetId] });
      queryClient.invalidateQueries({ queryKey: ['workspace-status', assetId] });
      queryClient.invalidateQueries({ queryKey: ['audit', assetId] });
    },
  });

  if (tiers.isLoading) return null;
  if (tiers.error)
    return (
      <section className="rounded border border-red-200 bg-red-50 p-3 text-xs text-red-700">
        Frontend tier detection failed: {(tiers.error as Error).message}
      </section>
    );
  if (!tiers.data || tiers.data.length === 0) return null;

  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <header className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-gray-900">Frontend tiers</h2>
        <span className="text-xs text-gray-500">{tiers.data.length} detected</span>
      </header>
      <ul className="space-y-2 text-sm">
        {tiers.data.map((t) => (
          <li
            key={t.relPath}
            className="flex flex-wrap items-center gap-2 rounded border border-gray-100 bg-gray-50 px-3 py-2"
          >
            <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${FRAMEWORK_BADGE[t.framework]}`}>
              {t.framework}
            </span>
            <code className="font-mono text-xs text-gray-800">{t.relPath}</code>
            <span className="text-[11px] text-gray-500">
              {t.packageManager} · out=<code className="font-mono">{t.outputDir}</code>
            </span>
            {t.hasDockerfile ? (
              <span className="ml-auto rounded bg-green-100 px-1.5 py-0.5 text-[11px] text-green-800">
                Dockerfile ✓
              </span>
            ) : (
              <button
                onClick={() => scaffold.mutate(t.relPath)}
                disabled={scaffold.isPending}
                className="ml-auto rounded bg-amber-600 px-2 py-1 text-xs text-white hover:bg-amber-700 disabled:bg-gray-400"
                title="Generate Dockerfile + nginx.conf + k8s manifest for this tier"
              >
                {scaffold.isPending && scaffold.variables === t.relPath
                  ? 'Scaffolding…'
                  : 'Scaffold Dockerfile + k8s'}
              </button>
            )}
          </li>
        ))}
      </ul>
      {scaffold.error && (
        <p className="mt-2 text-xs text-red-600">{(scaffold.error as Error).message}</p>
      )}
      {scaffold.data && (
        <div className="mt-2 rounded border border-green-200 bg-green-50 px-3 py-2 text-xs">
          <div className="font-medium text-green-900">{scaffold.data.message}</div>
          <ul className="mt-1 font-mono text-[11px] text-gray-700">
            {scaffold.data.filesWritten.map((f) => (
              <li key={f}>+ {f}</li>
            ))}
          </ul>
          <p className="mt-1 text-gray-600">
            Image: <code className="font-mono">{scaffold.data.imageTag}</code> · NodePort{' '}
            <code className="font-mono">{scaffold.data.nodePort}</code>
          </p>
          <p className="mt-1 text-gray-600">
            Next: add this tag to <code>spec.docker.images</code>, run <em>Build local images</em>,
            then <em>Apply</em>.
          </p>
        </div>
      )}
    </section>
  );
}
