import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type FixtureResult, type TestFixture } from '../api';

/**
 * Test-data fixtures (spec.test.fixtures): seed users, generate posts, dump credentials, etc.
 * Each fixture has a Run button. After running, the result panel shows the parsed credentials
 * table + clickable links + summary, plus an expandable log tail. Re-runs append to the same
 * card so users can compare attempts.
 */
export function FixturesTab({ assetId }: { assetId: string }) {
  const fixtures = useQuery({
    queryKey: ['test-fixtures', assetId],
    queryFn: () => api.listTestFixtures(assetId),
    retry: false,
  });

  if (fixtures.isLoading) return <p className="text-gray-500">Loading fixtures…</p>;
  if (fixtures.error)
    return <p className="text-red-600">{(fixtures.error as Error).message}</p>;
  if (!fixtures.data) return null;

  if (fixtures.data.length === 0) {
    return (
      <section className="rounded border border-gray-200 bg-white p-4 text-sm text-gray-600">
        <h2 className="mb-2 text-sm font-medium text-gray-900">Test fixtures</h2>
        <p>
          No fixtures declared in this asset's <code>devportal.yaml</code>. Add entries under{' '}
          <code className="font-mono">spec.test.fixtures</code> — each is a name + command. The
          command's stdout can include a{' '}
          <code className="font-mono">DEVPORTAL_FIXTURE: {'{json}'}</code> line carrying{' '}
          <code className="font-mono">credentials</code>, <code className="font-mono">links</code>,
          and <code className="font-mono">summary</code> for structured display here.
        </p>
      </section>
    );
  }

  return (
    <div className="space-y-4">
      {fixtures.data.map((f) => (
        <FixtureCard key={f.name} assetId={assetId} fixture={f} />
      ))}
    </div>
  );
}

function FixtureCard({ assetId, fixture }: { assetId: string; fixture: TestFixture }) {
  const queryClient = useQueryClient();
  const last = useQuery({
    queryKey: ['fixture-last', assetId, fixture.name],
    queryFn: async () => {
      try {
        const r = await api.lastFixtureRun(assetId, fixture.name);
        return r;
      } catch {
        return null;
      }
    },
  });
  const run = useMutation({
    mutationFn: () => api.runTestFixture(assetId, fixture.name),
    onSuccess: (r) => {
      queryClient.setQueryData(['fixture-last', assetId, fixture.name], r);
    },
  });
  const [showLog, setShowLog] = useState(false);

  // Use the freshest result available (just-run > cached last-run).
  const result: FixtureResult | null | undefined = run.data ?? last.data;

  return (
    <section className="rounded border border-gray-200 bg-white p-4 text-sm">
      <header className="mb-2 flex flex-wrap items-center gap-2">
        <h3 className="font-medium text-gray-900">{fixture.name}</h3>
        <span className="text-xs text-gray-500">{fixture.runIn ?? 'host'}</span>
        <button
          onClick={() => run.mutate()}
          disabled={run.isPending}
          className="ml-auto rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700 disabled:bg-gray-400"
        >
          {run.isPending ? 'Running…' : result ? 'Run again' : 'Run'}
        </button>
      </header>
      {fixture.description && (
        <p className="mb-2 text-xs text-gray-600">{fixture.description}</p>
      )}
      <details className="mb-2 text-xs text-gray-500">
        <summary className="cursor-pointer hover:text-gray-700">Show command</summary>
        <pre className="mt-1 whitespace-pre-wrap rounded bg-gray-50 p-2 font-mono text-[11px] text-gray-800">
          {fixture.command}
        </pre>
      </details>

      {run.error && (
        <p className="mt-2 text-xs text-red-600">{(run.error as Error).message}</p>
      )}

      {result && <FixtureResultPanel result={result} showLog={showLog} setShowLog={setShowLog} />}
    </section>
  );
}

function FixtureResultPanel({
  result,
  showLog,
  setShowLog,
}: {
  result: FixtureResult;
  showLog: boolean;
  setShowLog: (b: boolean) => void;
}) {
  const ok = result.status === 'succeeded';
  return (
    <div
      className={`rounded border p-3 text-sm ${
        ok ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'
      }`}
    >
      <div className="mb-2 flex flex-wrap items-baseline gap-2 text-xs">
        <span>{ok ? '✅' : '❌'}</span>
        <span className="font-medium text-gray-900">{result.status}</span>
        {result.exitCode != null && (
          <span className="text-gray-600">exit {result.exitCode}</span>
        )}
        {result.durationMs != null && (
          <span className="text-gray-500">{(result.durationMs / 1000).toFixed(2)}s</span>
        )}
        {result.finishedAt && (
          <span className="text-gray-500">
            ran {new Date(result.finishedAt).toLocaleString()}
          </span>
        )}
        <button
          onClick={() => setShowLog(!showLog)}
          className="ml-auto text-blue-700 hover:underline"
        >
          {showLog ? 'Hide log' : 'Show log'}
        </button>
      </div>

      {result.summary && (
        <p className="mb-2 text-sm text-gray-800">{result.summary}</p>
      )}

      {result.credentials.length > 0 && (
        <div className="mb-2 overflow-x-auto rounded border border-gray-200 bg-white">
          <table className="w-full text-xs">
            <thead className="bg-gray-50 text-left text-[11px] uppercase text-gray-500">
              <tr>
                <th className="px-2 py-1">Label</th>
                <th className="px-2 py-1">Username</th>
                <th className="px-2 py-1">Password</th>
                <th className="px-2 py-1">Role</th>
                <th className="px-2 py-1">URL</th>
              </tr>
            </thead>
            <tbody>
              {result.credentials.map((c, i) => (
                <tr key={i} className="border-t border-gray-100">
                  <td className="px-2 py-1 font-medium text-gray-900">{c.label ?? '—'}</td>
                  <td className="px-2 py-1 font-mono">{c.username ?? '—'}</td>
                  <td className="px-2 py-1">
                    {c.password ? <CopyableCell value={c.password} /> : '—'}
                  </td>
                  <td className="px-2 py-1 text-gray-600">{c.role ?? '—'}</td>
                  <td className="px-2 py-1">
                    {c.url ? (
                      <a
                        href={c.url}
                        target="_blank"
                        rel="noreferrer"
                        className="text-blue-700 hover:underline"
                      >
                        open
                      </a>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {result.links.length > 0 && (
        <div className="mb-2 flex flex-wrap gap-2 text-xs">
          {result.links.map((l, i) => (
            <a
              key={i}
              href={l.url ?? '#'}
              target="_blank"
              rel="noreferrer"
              className="rounded border border-blue-200 bg-blue-50 px-2 py-1 text-blue-800 hover:bg-blue-100"
            >
              {l.label ?? l.url}
            </a>
          ))}
        </div>
      )}

      {result.parseError && (
        <p className="text-xs text-amber-700">⚠ {result.parseError}</p>
      )}

      {!result.summary &&
        result.credentials.length === 0 &&
        result.links.length === 0 &&
        !result.parseError && (
          <p className="text-xs text-gray-500">
            Command exited {result.status}; no <code className="font-mono">DEVPORTAL_FIXTURE:</code>{' '}
            line was emitted. See the log for raw output.
          </p>
        )}

      {showLog && (
        <pre className="mt-2 max-h-64 overflow-auto rounded bg-white p-2 font-mono text-[11px]">
          {result.logTail.join('\n')}
        </pre>
      )}
    </div>
  );
}

function CopyableCell({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      onClick={() => {
        navigator.clipboard.writeText(value).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        });
      }}
      className="font-mono text-gray-800 hover:text-blue-700"
      title="Click to copy"
    >
      {copied ? 'copied!' : value}
    </button>
  );
}
