import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Build } from '../api';

export function BuildsTab({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [mode, setMode] = useState<'shallow' | 'deep'>('shallow');
  const [commandName, setCommandName] = useState('build');
  const [commandLine, setCommandLine] = useState('');
  const [ref, setRef] = useState('');

  const builds = useQuery({
    queryKey: ['builds', assetId],
    queryFn: () => api.listBuilds(assetId),
    refetchInterval: (q) => {
      const data = q.state.data as Build[] | undefined;
      return data?.some((b) => b.status === 'queued' || b.status === 'running') ? 2000 : false;
    },
  });

  const kick = useMutation({
    mutationFn: () =>
      api.kickBuild(assetId, {
        mode,
        commandName: commandLine ? undefined : commandName,
        commandLine: commandLine || undefined,
        ref: ref || undefined,
      }),
    onSuccess: (b) => {
      setSelectedId(b.id);
      queryClient.invalidateQueries({ queryKey: ['builds', assetId] });
    },
  });

  return (
    <div className="space-y-4">
      <section className="rounded border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-medium text-gray-900">Run a build</h2>
        <div className="grid grid-cols-2 gap-3 text-sm">
          <label className="space-y-1">
            <span className="block text-xs font-medium text-gray-700">Mode</span>
            <select
              value={mode}
              onChange={(e) => setMode(e.target.value as 'shallow' | 'deep')}
              className="w-full rounded border border-gray-300 px-3 py-1.5"
            >
              <option value="shallow">shallow (this asset only)</option>
              <option value="deep">deep (all transitive deps first)</option>
            </select>
          </label>
          <label className="space-y-1">
            <span className="block text-xs font-medium text-gray-700">Command name (from manifest)</span>
            <input
              value={commandName}
              onChange={(e) => setCommandName(e.target.value)}
              placeholder="build"
              className="w-full rounded border border-gray-300 px-3 py-1.5 disabled:bg-gray-100"
              disabled={!!commandLine}
            />
          </label>
          <label className="col-span-2 space-y-1">
            <span className="block text-xs font-medium text-gray-700">Or override with raw command line</span>
            <input
              value={commandLine}
              onChange={(e) => setCommandLine(e.target.value)}
              placeholder="e.g. mvn -DskipTests package"
              className="w-full rounded border border-gray-300 px-3 py-1.5 font-mono text-xs"
            />
          </label>
          <label className="col-span-2 space-y-1">
            <span className="block text-xs font-medium text-gray-700">Git ref (blank = default branch)</span>
            <input
              value={ref}
              onChange={(e) => setRef(e.target.value)}
              placeholder="main"
              className="w-full rounded border border-gray-300 px-3 py-1.5"
            />
          </label>
        </div>
        <div className="mt-3 flex items-center gap-3">
          <button
            onClick={() => kick.mutate()}
            disabled={kick.isPending}
            className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {kick.isPending ? 'Starting…' : 'Run build'}
          </button>
          {kick.error && <p className="text-sm text-red-600">{(kick.error as Error).message}</p>}
        </div>
      </section>

      <section className="rounded border border-gray-200 bg-white">
        <h2 className="border-b border-gray-200 px-4 py-2 text-sm font-medium text-gray-900">Recent builds</h2>
        {builds.data && builds.data.length === 0 && (
          <p className="px-4 py-3 text-sm text-gray-500">No builds yet.</p>
        )}
        {builds.data && builds.data.length > 0 && (
          <ul className="divide-y divide-gray-100">
            {builds.data.map((b) => (
              <li key={b.id}>
                <button
                  onClick={() => setSelectedId(b.id)}
                  className={`flex w-full items-center justify-between px-4 py-2 text-left text-sm hover:bg-gray-50 ${
                    selectedId === b.id ? 'bg-blue-50' : ''
                  }`}
                >
                  <div>
                    <div className="font-mono text-xs">#{b.id} · {b.commandName}</div>
                    <div className="text-xs text-gray-500">
                      {b.gitRef} · {new Date(b.createdAt).toLocaleString()}
                    </div>
                  </div>
                  <StatusBadge status={b.status} exitCode={b.exitCode} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {selectedId !== null && <ChainOrLog buildId={selectedId} />}
    </div>
  );
}

function ChainOrLog({ buildId }: { buildId: number }) {
  const chain = useQuery({
    queryKey: ['build-chain', buildId],
    queryFn: () => api.getBuildChain(buildId),
    refetchInterval: (q) => {
      const data = q.state.data as Build[] | undefined;
      return data?.some((b) => b.status === 'queued' || b.status === 'running') ? 1500 : false;
    },
  });
  if (chain.isLoading || !chain.data) return <BuildLog buildId={buildId} />;
  // If the chain is just one build, the standalone view is fine.
  if (chain.data.length <= 1) return <BuildLog buildId={buildId} />;
  return (
    <section className="rounded border border-blue-300 bg-white">
      <header className="flex items-center justify-between border-b border-blue-200 bg-blue-50 px-4 py-2">
        <h2 className="text-sm font-medium text-gray-900">
          Deep build chain — {chain.data.length} builds
        </h2>
        <ChainSummary builds={chain.data} />
      </header>
      <ol className="divide-y divide-gray-100">
        {chain.data.map((b: Build, i: number) => (
          <ChainSegment key={b.id} build={b} ordinal={i + 1} initiallyOpen={b.id === buildId} />
        ))}
      </ol>
    </section>
  );
}

function ChainSummary({ builds }: { builds: Build[] }) {
  const ok = builds.filter((b) => b.status === 'succeeded').length;
  const fail = builds.filter((b) => b.status === 'failed').length;
  const running = builds.filter((b) => b.status === 'running' || b.status === 'queued').length;
  return (
    <div className="flex gap-2 text-xs">
      <span className="rounded bg-green-100 px-2 py-0.5 font-medium text-green-800">{ok} ok</span>
      {fail > 0 && (
        <span className="rounded bg-red-100 px-2 py-0.5 font-medium text-red-800">{fail} failed</span>
      )}
      {running > 0 && (
        <span className="rounded bg-blue-100 px-2 py-0.5 font-medium text-blue-800 animate-pulse">
          {running} active
        </span>
      )}
    </div>
  );
}

function ChainSegment({
  build,
  ordinal,
  initiallyOpen,
}: {
  build: Build;
  ordinal: number;
  initiallyOpen: boolean;
}) {
  return (
    <li>
      <details open={initiallyOpen}>
        <summary className="flex cursor-pointer items-center justify-between px-4 py-2 text-sm hover:bg-gray-50">
          <div>
            <span className="mr-2 inline-block w-6 text-center font-mono text-xs text-gray-500">
              {ordinal}.
            </span>
            <span className="font-mono">{build.assetId}</span>
            <span className="ml-2 text-xs text-gray-500">
              #{build.id} · {build.commandName} · {build.gitRef}
            </span>
          </div>
          <StatusBadge status={build.status} exitCode={build.exitCode} />
        </summary>
        <SegmentLog buildId={build.id} status={build.status} />
      </details>
    </li>
  );
}

function SegmentLog({ buildId, status }: { buildId: number; status: Build['status'] }) {
  const { data: log } = useQuery({
    queryKey: ['build-log', buildId],
    queryFn: () => api.getBuildLog(buildId),
    refetchInterval: status === 'queued' || status === 'running' ? 1500 : false,
  });
  return (
    <pre className="max-h-72 overflow-auto bg-gray-900 px-4 py-3 font-mono text-xs leading-relaxed text-gray-100">
      {log ?? 'Loading…'}
    </pre>
  );
}

function StatusBadge({ status, exitCode }: { status: Build['status']; exitCode: number | null }) {
  const map: Record<Build['status'], string> = {
    queued: 'bg-gray-200 text-gray-700',
    running: 'bg-blue-100 text-blue-800 animate-pulse',
    succeeded: 'bg-green-100 text-green-800',
    failed: 'bg-red-100 text-red-800',
    cancelled: 'bg-yellow-100 text-yellow-800',
  };
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${map[status]}`}>
      {status}
      {exitCode !== null && ` (${exitCode})`}
    </span>
  );
}

function BuildLog({ buildId }: { buildId: number }) {
  const { data: build } = useQuery({
    queryKey: ['build', buildId],
    queryFn: () => api.getBuild(buildId),
    refetchInterval: (q) => {
      const data = q.state.data as Build | undefined;
      return data?.status === 'queued' || data?.status === 'running' ? 1500 : false;
    },
  });

  const { data: log } = useQuery({
    queryKey: ['build-log', buildId],
    queryFn: () => api.getBuildLog(buildId),
    refetchInterval: (q) => {
      // Stop polling once the build's terminal state has been reflected and we have content.
      return build?.status === 'queued' || build?.status === 'running' ? 1500
        : !q.state.data ? 1500 : false;
    },
  });

  return (
    <section className="rounded border border-gray-200 bg-white">
      <div className="flex items-center justify-between border-b border-gray-200 px-4 py-2">
        <h2 className="text-sm font-medium text-gray-900">
          Build #{buildId} log
        </h2>
        {build && <StatusBadge status={build.status} exitCode={build.exitCode} />}
      </div>
      <pre className="max-h-[480px] overflow-auto bg-gray-900 px-4 py-3 font-mono text-xs leading-relaxed text-gray-100">
        {log ?? 'Loading…'}
      </pre>
    </section>
  );
}
