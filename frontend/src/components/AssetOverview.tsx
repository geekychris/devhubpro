import { useQuery } from '@tanstack/react-query';
import { api, type Asset } from '../api';

export function AssetOverview({ asset }: { asset: Asset }) {
  const gitInfo = useQuery({
    queryKey: ['git-info', asset.id],
    queryFn: () => api.gitInfo(asset.id),
    retry: false,
  });

  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2 rounded border border-gray-200 bg-white p-4 text-sm">
        <Row label="Type" value={asset.type} />
        <Row label="Language" value={asset.language ?? '—'} />
        <Row label="Owner" value={asset.owner ?? '—'} />
        <Row label="Lifecycle" value={asset.lifecycle} />
        <Row
          label="Repo"
          value={
            <a href={asset.repoUrl} target="_blank" rel="noreferrer" className="text-blue-700 hover:underline break-all">
              {asset.repoUrl}
            </a>
          }
        />
        <Row label="Default branch" value={asset.repoDefaultBranch} />
        <Row label="K8s namespace" value={asset.k8sNamespace ?? <span className="text-gray-400">{asset.id} (default)</span>} mono />
        <Row label="Description" value={asset.description ?? gitInfo.data?.description ?? '—'} wide />
        <Row label="Tags" value={asset.tags.length ? asset.tags.join(', ') : '—'} wide />
        <Row label="Created" value={new Date(asset.createdAt).toLocaleString()} />
        <Row label="Updated" value={new Date(asset.updatedAt).toLocaleString()} />
      </dl>

      <section className="rounded border border-gray-200 bg-white p-4 text-sm space-y-2">
        <h2 className="text-sm font-medium text-gray-900">GitHub</h2>
        {gitInfo.isLoading && <p className="text-xs text-gray-500">Loading git info…</p>}
        {gitInfo.error && (
          <p className="text-xs text-red-600">{(gitInfo.error as Error).message}</p>
        )}
        {gitInfo.data && (
          <>
            <div className="flex flex-wrap gap-3 text-xs text-gray-700">
              <Stat label="Stars" value={gitInfo.data.stargazersCount} />
              <Stat label="Forks" value={gitInfo.data.forksCount} />
              <Stat label="Open issues" value={gitInfo.data.openIssuesCount} />
              <Stat label="License" value={gitInfo.data.license ?? '—'} />
              {gitInfo.data.pushedAt && (
                <Stat label="Last push" value={new Date(gitInfo.data.pushedAt).toLocaleString()} />
              )}
            </div>
            {gitInfo.data.topics.length > 0 && (
              <div>
                <span className="text-xs font-medium text-gray-500">GitHub topics: </span>
                {gitInfo.data.topics.map((t) => (
                  <span
                    key={t}
                    className="ml-1 inline-block rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-700"
                  >
                    {t}
                  </span>
                ))}
              </div>
            )}
            <div>
              <h3 className="text-xs font-medium text-gray-500">
                Git tags ({gitInfo.data.tags.length})
              </h3>
              {gitInfo.data.tags.length === 0 ? (
                <p className="text-xs text-gray-500">No tags / releases.</p>
              ) : (
                <ul className="text-xs font-mono">
                  {gitInfo.data.tags.slice(0, 10).map((t) => (
                    <li key={t.name} className="text-gray-700">
                      {t.name}{' '}
                      {t.sha && <span className="text-gray-400">{t.sha.substring(0, 7)}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </>
        )}
      </section>
    </div>
  );
}

function Row({
  label,
  value,
  wide,
  mono,
}: {
  label: string;
  value: React.ReactNode;
  wide?: boolean;
  mono?: boolean;
}) {
  return (
    <div className={wide ? 'sm:col-span-2' : ''}>
      <dt className="text-xs font-medium uppercase tracking-wide text-gray-500">{label}</dt>
      <dd className={`mt-0.5 text-gray-900 ${mono ? 'font-mono text-xs' : ''}`}>{value}</dd>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded bg-gray-50 px-3 py-2">
      <div className="text-[11px] uppercase text-gray-500">{label}</div>
      <div className="text-sm font-medium text-gray-900">{value}</div>
    </div>
  );
}
