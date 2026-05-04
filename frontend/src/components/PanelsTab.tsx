import { useQuery } from '@tanstack/react-query';
import { api, type Panel } from '../api';

export function PanelsTab({ assetId }: { assetId: string }) {
  const panels = useQuery({
    queryKey: ['panels', assetId],
    queryFn: () => api.panels(assetId),
    refetchInterval: 10_000,
  });

  if (panels.isLoading) return <p className="text-sm text-gray-500">Loading…</p>;
  if (panels.error) return <p className="text-sm text-red-600">{(panels.error as Error).message}</p>;
  if (!panels.data) return null;

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      {panels.data.map((p) => (
        <PanelCard key={p.id} panel={p} />
      ))}
    </div>
  );
}

function PanelCard({ panel }: { panel: Panel }) {
  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <h3 className="mb-2 text-sm font-medium text-gray-900">{panel.title}</h3>
      <PanelBody panel={panel} />
    </section>
  );
}

function PanelBody({ panel }: { panel: Panel }) {
  switch (panel.layout) {
    case 'kv':
      return (
        <dl className="grid grid-cols-3 gap-x-3 gap-y-1 text-sm">
          {panel.items?.map((it) => (
            <div key={it.key} className="contents">
              <dt className="col-span-1 text-xs font-medium text-gray-500">{it.key}</dt>
              <dd className="col-span-2 break-all text-gray-900">{it.value}</dd>
            </div>
          ))}
        </dl>
      );
    case 'list':
      return (
        <ul className="space-y-1 text-sm text-gray-800">
          {panel.list?.map((line, i) => (
            <li key={i} className="font-mono whitespace-pre-wrap">
              {line}
            </li>
          ))}
        </ul>
      );
    case 'code':
      return (
        <pre className="max-h-64 overflow-auto rounded bg-gray-900 px-3 py-2 font-mono text-xs text-gray-100">
          {panel.code}
        </pre>
      );
    case 'links':
      return (
        <ul className="space-y-1 text-sm">
          {panel.links?.map((l) => (
            <li key={l.url}>
              <a href={l.url} target="_blank" rel="noreferrer" className="text-blue-700 hover:underline">
                {l.label}
              </a>
            </li>
          ))}
        </ul>
      );
    default:
      return <p className="text-xs text-gray-500">Unknown layout: {panel.layout}</p>;
  }
}
