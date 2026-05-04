import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type ImportJob } from '../api';

const LANGS = ['Java', 'Kotlin', 'Go', 'Rust', 'TypeScript', 'JavaScript', 'Python'];

export function BulkImportPage() {
  const queryClient = useQueryClient();
  const [owner, setOwner] = useState('geekychris');
  const [selectedLangs, setSelectedLangs] = useState<string[]>(['Java', 'Kotlin']);
  const [includeText, setIncludeText] = useState('');
  const [excludeText, setExcludeText] = useState('');
  const [skipArchived, setSkipArchived] = useState(true);
  const [skipForks, setSkipForks] = useState(true);
  const [cloneAndAnalyze, setCloneAndAnalyze] = useState(true);
  const [autoWire, setAutoWire] = useState(true);
  const [activeJobId, setActiveJobId] = useState<number | null>(null);

  const preview = useQuery({
    queryKey: ['org-repos', owner],
    queryFn: () => api.previewOrgRepos(owner),
    enabled: false, // manual via button
  });

  const start = useMutation({
    mutationFn: () =>
      api.startBulkImport(owner, {
        languages: selectedLangs,
        includePatterns: includeText.split(/[\s,]+/).filter(Boolean),
        excludePatterns: excludeText.split(/[\s,]+/).filter(Boolean),
        skipArchived,
        skipForks,
        cloneAndAnalyze,
        autoWireMavenDeps: autoWire,
      }),
    onSuccess: (j) => {
      setActiveJobId(j.id);
      queryClient.invalidateQueries({ queryKey: ['assets'] });
    },
  });

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold text-gray-900">Bulk import</h1>
      <p className="text-sm text-gray-600">
        Pull all matching repos from a GitHub org, register them, clone, parse poms, and auto-wire
        the Maven dep graph.
      </p>

      <section className="rounded border border-gray-200 bg-white p-4 space-y-3 text-sm">
        <label>
          <span className="block text-xs font-medium text-gray-700">Org / owner</span>
          <input
            value={owner}
            onChange={(e) => setOwner(e.target.value)}
            className="w-full rounded border border-gray-300 px-3 py-1.5"
          />
        </label>

        <fieldset>
          <legend className="block text-xs font-medium text-gray-700">Languages</legend>
          <div className="mt-1 flex flex-wrap gap-2">
            {LANGS.map((lang) => (
              <label key={lang} className="flex items-center gap-1 text-xs">
                <input
                  type="checkbox"
                  checked={selectedLangs.includes(lang)}
                  onChange={(e) => {
                    setSelectedLangs((prev) =>
                      e.target.checked ? [...prev, lang] : prev.filter((l) => l !== lang)
                    );
                  }}
                />
                {lang}
              </label>
            ))}
          </div>
        </fieldset>

        <div className="grid grid-cols-2 gap-3">
          <label>
            <span className="block text-xs font-medium text-gray-700">
              Include patterns (regex, space- or comma-separated)
            </span>
            <input
              value={includeText}
              onChange={(e) => setIncludeText(e.target.value)}
              placeholder="^hitorro- ^aoee$"
              className="w-full rounded border border-gray-300 px-3 py-1.5 font-mono text-xs"
            />
          </label>
          <label>
            <span className="block text-xs font-medium text-gray-700">Exclude patterns</span>
            <input
              value={excludeText}
              onChange={(e) => setExcludeText(e.target.value)}
              placeholder="-archive$ ^scratch-"
              className="w-full rounded border border-gray-300 px-3 py-1.5 font-mono text-xs"
            />
          </label>
        </div>

        <div className="flex flex-wrap gap-4 text-xs">
          <label className="flex items-center gap-1">
            <input type="checkbox" checked={skipArchived} onChange={(e) => setSkipArchived(e.target.checked)} />
            Skip archived
          </label>
          <label className="flex items-center gap-1">
            <input type="checkbox" checked={skipForks} onChange={(e) => setSkipForks(e.target.checked)} />
            Skip forks
          </label>
          <label className="flex items-center gap-1">
            <input
              type="checkbox"
              checked={cloneAndAnalyze}
              onChange={(e) => setCloneAndAnalyze(e.target.checked)}
            />
            Clone &amp; analyze each match
          </label>
          <label className="flex items-center gap-1">
            <input type="checkbox" checked={autoWire} onChange={(e) => setAutoWire(e.target.checked)} />
            Auto-wire Maven deps after import
          </label>
        </div>

        <div className="flex gap-2">
          <button
            onClick={() => preview.refetch()}
            className="rounded border border-gray-300 px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100"
          >
            {preview.isFetching ? 'Listing…' : 'Preview repos'}
          </button>
          <button
            onClick={() => start.mutate()}
            disabled={start.isPending}
            className="rounded bg-blue-600 px-3 py-1.5 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {start.isPending ? 'Starting…' : 'Run import'}
          </button>
        </div>
        {preview.error && <p className="text-sm text-red-600">{(preview.error as Error).message}</p>}
        {start.error && <p className="text-sm text-red-600">{(start.error as Error).message}</p>}
      </section>

      {preview.data && (
        <section className="rounded border border-gray-200 bg-white p-4 text-sm">
          <h2 className="mb-2 text-sm font-medium text-gray-900">
            Preview — {preview.data.length} repos in {owner}
          </h2>
          <ul className="max-h-60 overflow-auto text-xs font-mono">
            {preview.data.map((r) => (
              <li key={r.fullName} className="border-t border-gray-100 py-1">
                {r.fullName}{' '}
                <span className="text-gray-500">
                  {r.primaryLanguage ?? '?'} {r.archived ? '· archived' : ''} {r.fork ? '· fork' : ''}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {activeJobId !== null && <JobView id={activeJobId} />}
    </div>
  );
}

function JobView({ id }: { id: number }) {
  const job = useQuery({
    queryKey: ['import-job', id],
    queryFn: () => api.getImportJob(id),
    refetchInterval: (q) => {
      const data = q.state.data as ImportJob | undefined;
      return data?.status === 'running' || data?.status === 'queued' ? 1500 : false;
    },
  });

  if (!job.data) return null;
  const j = job.data;
  const pct = j.totalMatched > 0 ? Math.round((j.processed / j.totalMatched) * 100) : 0;

  return (
    <section className="rounded border border-gray-200 bg-white p-4 text-sm">
      <header className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-gray-900">Import job #{j.id}</h2>
        <span
          className={`rounded px-2 py-0.5 text-xs font-medium ${
            j.status === 'succeeded'
              ? 'bg-green-100 text-green-800'
              : j.status === 'failed'
              ? 'bg-red-100 text-red-800'
              : 'bg-blue-100 text-blue-800 animate-pulse'
          }`}
        >
          {j.status}
        </span>
      </header>

      <div className="mb-2 grid grid-cols-3 gap-x-3 text-xs">
        <Stat label="matched" value={j.totalMatched} />
        <Stat label="processed" value={`${j.processed} (${pct}%)`} />
        <Stat label="current" value={j.currentRepo ?? '—'} mono />
        <Stat label="registered (new)" value={j.registered} />
        <Stat label="already registered" value={j.alreadyRegistered} />
        <Stat label="analyzed (pom)" value={j.analyzed} />
        <Stat label="auto-wired edges" value={j.autoWired} />
        <Stat label="started" value={j.startedAt ?? '—'} />
        <Stat label="finished" value={j.finishedAt ?? '—'} />
      </div>

      {j.error && <p className="mb-2 text-sm text-red-600">{j.error}</p>}

      <details open className="text-xs">
        <summary className="cursor-pointer text-gray-700">Per-repo results ({j.results.length})</summary>
        <div className="mt-1 max-h-72 overflow-auto rounded bg-gray-900 px-2 py-2 font-mono text-gray-100">
          {j.results.map((r, i) => (
            <div key={i} className="whitespace-pre">
              {JSON.stringify(r)}
            </div>
          ))}
        </div>
      </details>

      <details className="mt-2 text-xs">
        <summary className="cursor-pointer text-gray-700">Log ({j.log.length} lines)</summary>
        <pre className="mt-1 max-h-72 overflow-auto rounded bg-gray-900 px-2 py-2 text-gray-100 whitespace-pre-wrap">
          {j.log.join('\n')}
        </pre>
      </details>
    </section>
  );
}

function Stat({ label, value, mono }: { label: string; value: string | number; mono?: boolean }) {
  return (
    <div>
      <div className="text-xs font-medium text-gray-500">{label}</div>
      <div className={mono ? 'font-mono text-xs break-all' : 'text-sm'}>{value}</div>
    </div>
  );
}
