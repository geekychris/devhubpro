import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api';
import { MarkdownView } from './MarkdownView';

export function DocsTab({ assetId }: { assetId: string }) {
  const list = useQuery({
    queryKey: ['docs', assetId],
    queryFn: () => api.listDocs(assetId),
  });
  const [selected, setSelected] = useState<string | null>(null);

  // Default to README.md if available
  const defaultPath =
    list.data?.find((d) => d.name.toLowerCase() === 'readme.md')?.path ?? list.data?.[0]?.path;
  const activePath = selected ?? defaultPath ?? null;

  return (
    <div className="grid grid-cols-4 gap-4">
      <aside className="col-span-1 rounded border border-gray-200 bg-white p-3 text-sm">
        <h2 className="mb-2 text-xs font-medium uppercase text-gray-500">Markdown files</h2>
        {list.isLoading && <p className="text-xs text-gray-500">Loading…</p>}
        {list.error && <p className="text-xs text-red-600">{(list.error as Error).message}</p>}
        {list.data && list.data.length === 0 && (
          <p className="text-xs text-gray-500">No .md files found in this workspace.</p>
        )}
        {list.data && (
          <ul className="space-y-0.5">
            {list.data.map((d) => (
              <li key={d.path}>
                <button
                  onClick={() => setSelected(d.path)}
                  className={`w-full text-left text-xs font-mono px-2 py-1 rounded hover:bg-gray-100 ${
                    activePath === d.path ? 'bg-blue-50 text-blue-800' : ''
                  }`}
                >
                  {d.path}
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>
      <div className="col-span-3">
        {activePath ? (
          <DocViewer assetId={assetId} path={activePath} />
        ) : (
          <p className="text-sm text-gray-500">Select a doc on the left.</p>
        )}
      </div>
    </div>
  );
}

function DocViewer({ assetId, path }: { assetId: string; path: string }) {
  const [raw, setRaw] = useState(false);
  const doc = useQuery({
    queryKey: ['doc', assetId, path],
    queryFn: () => api.readDoc(assetId, path),
  });
  if (doc.isLoading) return <p className="text-sm text-gray-500">Loading…</p>;
  if (doc.error) return <p className="text-sm text-red-600">{(doc.error as Error).message}</p>;
  if (!doc.data) return null;

  return (
    <article className="rounded border border-gray-200 bg-white">
      <header className="flex items-center justify-between border-b border-gray-200 px-4 py-2 text-sm">
        <span className="font-mono text-xs">{path}</span>
        <button
          onClick={() => setRaw((v) => !v)}
          className="text-xs text-blue-700 hover:underline"
        >
          {raw ? 'Rendered' : 'Raw'}
        </button>
      </header>
      {raw ? (
        <pre className="max-h-[70vh] overflow-auto px-4 py-3 font-mono text-xs whitespace-pre-wrap">
          {doc.data}
        </pre>
      ) : (
        <MarkdownView
          source={doc.data}
          className="max-w-none px-6 py-4 max-h-[70vh] overflow-auto"
        />
      )}
    </article>
  );
}
