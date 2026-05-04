import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api';

export function AnalyzeTab({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();

  const validation = useQuery({
    queryKey: ['validate', assetId],
    queryFn: () => api.validateAsset(assetId),
  });

  const analyze = useMutation({
    mutationFn: () => api.analyzeAsset(assetId),
  });

  const autoWire = useMutation({
    mutationFn: () => api.autoWireAsset(assetId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['asset-deps', assetId] });
      queryClient.invalidateQueries({ queryKey: ['asset-graph', assetId] });
      analyze.mutate();
    },
  });

  return (
    <div className="space-y-4">
      <section className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-2 text-sm font-medium text-gray-900">GitHub validate</h2>
        {validation.isLoading && <p className="text-sm text-gray-500">Loading…</p>}
        {validation.error && <p className="text-sm text-red-600">{(validation.error as Error).message}</p>}
        {validation.data && (
          <div className="grid grid-cols-2 gap-x-3 gap-y-1 text-sm">
            <Row label="Repo URL" value={validation.data.repoUrl} mono />
            <Row label="Parsed full-name" value={validation.data.fullName ?? '—'} mono />
            <Row label="Reachable" value={badge(validation.data.reachable)} />
            <Row label="Remote default branch" value={validation.data.defaultBranchRemote ?? '—'} />
            <Row
              label="Default branch matches"
              value={validation.data.defaultBranchRemote ? badge(validation.data.defaultBranchMatches) : '—'}
            />
            <Row label="devportal.yaml present" value={badge(validation.data.hasManifest)} />
            <Row label="pom.xml present" value={badge(validation.data.hasPom)} />
            <Row label="build.gradle present" value={badge(validation.data.hasGradle)} />
            <Row label="package.json present" value={badge(validation.data.hasPackageJson)} />
            <Row label="Dockerfile present" value={badge(validation.data.hasDockerfile)} />
            <Row label="k8s/ or deploy/ present" value={badge(validation.data.hasK8sDir)} />
            {validation.data.error && (
              <Row label="Error" value={<span className="text-red-600">{validation.data.error}</span>} />
            )}
          </div>
        )}
      </section>

      <section className="rounded border border-gray-200 bg-white p-4">
        <header className="mb-2 flex items-center justify-between">
          <h2 className="text-sm font-medium text-gray-900">Maven analyzer</h2>
          <div className="flex gap-2 text-xs">
            <button
              onClick={() => analyze.mutate()}
              className="rounded bg-blue-600 px-2 py-1 text-white hover:bg-blue-700"
              disabled={analyze.isPending}
            >
              {analyze.isPending ? 'Analyzing…' : 'Analyze pom.xml'}
            </button>
            <button
              onClick={() => autoWire.mutate()}
              className="rounded bg-green-600 px-2 py-1 text-white hover:bg-green-700"
              disabled={autoWire.isPending}
            >
              {autoWire.isPending ? 'Wiring…' : 'Auto-wire matched deps'}
            </button>
          </div>
        </header>

        {analyze.error && <p className="mb-2 text-sm text-red-600">{(analyze.error as Error).message}</p>}
        {autoWire.error && <p className="mb-2 text-sm text-red-600">{(autoWire.error as Error).message}</p>}

        {!analyze.data && (
          <p className="text-sm text-gray-500">
            Click <em>Analyze pom.xml</em> to parse this asset's workspace.
          </p>
        )}

        {analyze.data && !analyze.data.foundPom && (
          <p className="text-sm text-gray-600">
            No <code>pom.xml</code> at workspace root. Maven analysis skipped.
          </p>
        )}

        {analyze.data && analyze.data.foundPom && (
          <div className="space-y-3 text-sm">
            <div>
              <h3 className="text-xs font-medium uppercase text-gray-500">
                Published artifacts ({analyze.data.publishedArtifacts.length})
              </h3>
              <ul className="mt-1 font-mono text-xs">
                {analyze.data.publishedArtifacts.map((a, i) => (
                  <li key={i}>
                    {a.groupId ?? '(no group)'}:{a.artifactId}:{a.version ?? '(no version)'}{' '}
                    <span className="text-gray-500">@ {a.relativePath}</span>
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <h3 className="text-xs font-medium uppercase text-gray-500">
                Declared dependencies ({analyze.data.dependencyMatches.length})
              </h3>
              <table className="mt-1 w-full text-xs">
                <thead className="text-left text-gray-500">
                  <tr>
                    <th className="py-1">groupId:artifactId</th>
                    <th className="py-1">version</th>
                    <th className="py-1">match</th>
                  </tr>
                </thead>
                <tbody>
                  {analyze.data.dependencyMatches.map((m, i) => (
                    <tr key={i} className="border-t border-gray-100">
                      <td className="py-1 font-mono">
                        {m.coord.groupId ?? '(no group)'}:{m.coord.artifactId}
                      </td>
                      <td className="py-1 font-mono">{m.coord.version ?? '—'}</td>
                      <td className="py-1">
                        {m.matchedAssetId ? (
                          <span>
                            <Link
                              to={`/assets/${m.matchedAssetId}`}
                              className="text-blue-700 hover:underline"
                            >
                              {m.matchedAssetId}
                            </Link>
                            {m.alreadyWired ? (
                              <span className="ml-1 text-xs text-green-700">(already wired)</span>
                            ) : (
                              <span className="ml-1 text-xs text-amber-700">(suggest add)</span>
                            )}
                          </span>
                        ) : (
                          <span className="text-gray-400">no portal asset publishes this</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {analyze.data.warnings.length > 0 && (
              <div>
                <h3 className="text-xs font-medium uppercase text-gray-500">Warnings</h3>
                <ul className="mt-1 list-disc pl-4 text-xs text-amber-700">
                  {analyze.data.warnings.map((w, i) => (
                    <li key={i}>{w}</li>
                  ))}
                </ul>
              </div>
            )}

            {autoWire.data && (
              <div className="rounded border border-green-200 bg-green-50 p-3 text-xs text-green-900">
                Auto-wired {autoWire.data.added} new edge(s); {autoWire.data.alreadyPresent}{' '}
                already present; {autoWire.data.unmatched} unmatched.
                {autoWire.data.wiredProducers.length > 0 && (
                  <> Added: {autoWire.data.wiredProducers.join(', ')}</>
                )}
              </div>
            )}
          </div>
        )}
      </section>
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <>
      <div className="text-xs font-medium text-gray-500">{label}</div>
      <div className={mono ? 'font-mono text-xs break-all' : ''}>{value}</div>
    </>
  );
}

function badge(yes: boolean) {
  return (
    <span
      className={`rounded px-1.5 py-0.5 text-xs font-medium ${
        yes ? 'bg-green-100 text-green-800' : 'bg-gray-200 text-gray-700'
      }`}
    >
      {yes ? 'yes' : 'no'}
    </span>
  );
}
