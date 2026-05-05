import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';
import { DependencyGraph } from '../components/DependencyGraph';
import { BuildsTab } from '../components/BuildsTab';
import { RuntimeTab } from '../components/RuntimeTab';
import { PanelsTab } from '../components/PanelsTab';
import { AnalyzeTab } from '../components/AnalyzeTab';
import { EditAssetForm } from '../components/EditAssetForm';
import { AssetOverview } from '../components/AssetOverview';
import { DocsTab } from '../components/DocsTab';
import { AskClaudeButton } from '../components/AskClaudeButton';
import { ClusterTab } from '../components/ClusterTab';
import { ChangesTab } from '../components/ChangesTab';
import { FixturesTab } from '../components/FixturesTab';

type Tab = 'overview' | 'dependencies' | 'graph' | 'builds' | 'runtime' | 'cluster' | 'analyze' | 'changes' | 'fixtures' | 'docs' | 'panels';

export function AssetDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<Tab>('overview');
  const [editing, setEditing] = useState(false);

  // Cross-component navigation: components can dispatch a 'devportal:goto-tab' CustomEvent to switch tabs.
  useEffect(() => {
    function onGoto(e: Event) {
      const detail = (e as CustomEvent).detail as Tab;
      if (
        detail === 'overview' || detail === 'dependencies' || detail === 'graph' ||
        detail === 'builds' || detail === 'runtime' || detail === 'cluster' ||
        detail === 'analyze' || detail === 'changes' || detail === 'fixtures' ||
        detail === 'docs' || detail === 'panels'
      ) {
        setTab(detail);
      }
    }
    window.addEventListener('devportal:goto-tab', onGoto);
    return () => window.removeEventListener('devportal:goto-tab', onGoto);
  }, []);

  const asset = useQuery({ queryKey: ['asset', id], queryFn: () => api.getAsset(id), enabled: !!id });
  const deps = useQuery({
    queryKey: ['asset-deps', id],
    queryFn: () => api.getDependencies(id),
    enabled: !!id && tab === 'dependencies',
  });
  const consumers = useQuery({
    queryKey: ['asset-consumers', id],
    queryFn: () => api.getConsumers(id),
    enabled: !!id && tab === 'dependencies',
  });
  const [graphDirection, setGraphDirection] = useState<'producers' | 'consumers' | 'both'>('both');
  const [graphProducerDepth, setGraphProducerDepth] = useState<number>(-1);
  const [graphConsumerDepth, setGraphConsumerDepth] = useState<number>(1);
  const graph = useQuery({
    queryKey: ['asset-graph', id, graphDirection, graphProducerDepth, graphConsumerDepth],
    queryFn: () =>
      api.getGraph(id, {
        direction: graphDirection,
        producerDepth: graphProducerDepth,
        consumerDepth: graphConsumerDepth,
      }),
    enabled: !!id && tab === 'graph',
  });

  const [producerId, setProducerId] = useState('');
  const addDep = useMutation({
    mutationFn: () => api.addDependency(id, producerId),
    onSuccess: () => {
      setProducerId('');
      queryClient.invalidateQueries({ queryKey: ['asset-deps', id] });
      queryClient.invalidateQueries({ queryKey: ['asset-graph', id] });
    },
  });
  const removeDep = useMutation({
    mutationFn: (producer: string) => api.removeDependency(id, producer),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['asset-deps', id] });
      queryClient.invalidateQueries({ queryKey: ['asset-graph', id] });
    },
  });
  const remove = useMutation({
    mutationFn: () => api.deleteAsset(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['assets'] });
      navigate('/assets');
    },
  });

  if (asset.isLoading) return <p className="text-gray-500">Loading…</p>;
  if (asset.error) return <p className="text-red-600">{(asset.error as Error).message}</p>;
  if (!asset.data) return null;
  const a = asset.data;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <Link to="/assets" className="text-sm text-blue-700 hover:underline">
            ← All assets
          </Link>
          <h1 className="text-2xl font-semibold text-gray-900">{a.name}</h1>
          <p className="text-sm text-gray-500">{a.id}</p>
        </div>
        <div className="flex gap-2">
          <AskClaudeButton assetId={a.id} />
          <button
            onClick={() => setEditing((v) => !v)}
            className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-100"
          >
            {editing ? 'Cancel edit' : 'Edit'}
          </button>
          <button
            onClick={() => {
              if (confirm(`Delete asset ${a.id}? This cannot be undone.`)) remove.mutate();
            }}
            className="rounded border border-red-300 px-3 py-1.5 text-sm text-red-700 hover:bg-red-50"
          >
            Delete
          </button>
        </div>
      </div>

      {editing && <EditAssetForm asset={a} onClose={() => setEditing(false)} />}

      <nav className="flex gap-4 border-b border-gray-200 text-sm">
        {(['overview', 'dependencies', 'graph', 'builds', 'runtime', 'cluster', 'analyze', 'changes', 'fixtures', 'docs', 'panels'] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`-mb-px border-b-2 px-1 pb-2 ${
              tab === t
                ? 'border-blue-600 text-blue-700 font-medium'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
          >
            {t}
          </button>
        ))}
      </nav>

      {tab === 'overview' && <AssetOverview asset={a} />}

      {tab === 'dependencies' && (
        <div className="space-y-4">
          <section className="rounded border border-gray-200 bg-white p-4">
            <h2 className="mb-2 text-sm font-medium text-gray-900">This asset depends on</h2>
            {deps.data && deps.data.length === 0 && <p className="text-sm text-gray-500">No dependencies.</p>}
            {deps.data && deps.data.length > 0 && (
              <ul className="divide-y divide-gray-100">
                {deps.data.map((d) => (
                  <li key={d.id} className="flex items-center justify-between py-2 text-sm">
                    <Link to={`/assets/${d.producerId}`} className="text-blue-700 hover:underline">
                      {d.producerId}
                    </Link>
                    <div className="flex items-center gap-2 text-gray-500">
                      <span>{d.versionRef}</span>
                      <span className="text-xs uppercase">{d.kind}</span>
                      <button
                        onClick={() => removeDep.mutate(d.producerId)}
                        className="text-xs text-red-600 hover:underline"
                      >
                        remove
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (producerId) addDep.mutate();
              }}
              className="mt-3 flex gap-2"
            >
              <input
                placeholder="add producer id (e.g. hitorro-util)"
                value={producerId}
                onChange={(e) => setProducerId(e.target.value)}
                className="flex-1 rounded border border-gray-300 px-3 py-1.5 text-sm"
              />
              <button
                type="submit"
                className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
              >
                Add
              </button>
            </form>
            {addDep.error && (
              <p className="mt-2 text-sm text-red-600">{(addDep.error as Error).message}</p>
            )}
          </section>

          <section className="rounded border border-gray-200 bg-white p-4">
            <h2 className="mb-2 text-sm font-medium text-gray-900">Used by</h2>
            {consumers.data && consumers.data.length === 0 && (
              <p className="text-sm text-gray-500">Nothing depends on this asset.</p>
            )}
            {consumers.data && consumers.data.length > 0 && (
              <ul className="divide-y divide-gray-100">
                {consumers.data.map((d) => (
                  <li key={d.id} className="flex items-center justify-between py-2 text-sm">
                    <Link to={`/assets/${d.consumerId}`} className="text-blue-700 hover:underline">
                      {d.consumerId}
                    </Link>
                    <span className="text-xs uppercase text-gray-500">{d.kind}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}

      {tab === 'graph' && (
        <div className="space-y-3">
          <div className="rounded border border-gray-200 bg-white p-3 flex flex-wrap items-end gap-3 text-sm">
            <label className="space-y-1">
              <span className="block text-xs font-medium text-gray-700">Direction</span>
              <select
                value={graphDirection}
                onChange={(e) => setGraphDirection(e.target.value as 'producers' | 'consumers' | 'both')}
                className="rounded border border-gray-300 px-3 py-1.5 text-sm"
              >
                <option value="both">both (deps + consumers)</option>
                <option value="producers">producers only (what I depend on)</option>
                <option value="consumers">consumers only (who depends on me)</option>
              </select>
            </label>
            <label className="space-y-1">
              <span className="block text-xs font-medium text-gray-700">Producer depth</span>
              <select
                value={graphProducerDepth}
                onChange={(e) => setGraphProducerDepth(parseInt(e.target.value, 10))}
                className="rounded border border-gray-300 px-3 py-1.5 text-sm"
                disabled={graphDirection === 'consumers'}
              >
                <option value={0}>0 (root only)</option>
                <option value={1}>1 hop</option>
                <option value={2}>2 hops</option>
                <option value={3}>3 hops</option>
                <option value={5}>5 hops</option>
                <option value={-1}>all (transitive)</option>
              </select>
            </label>
            <label className="space-y-1">
              <span className="block text-xs font-medium text-gray-700">Consumer depth</span>
              <select
                value={graphConsumerDepth}
                onChange={(e) => setGraphConsumerDepth(parseInt(e.target.value, 10))}
                className="rounded border border-gray-300 px-3 py-1.5 text-sm"
                disabled={graphDirection === 'producers'}
              >
                <option value={0}>0 (none)</option>
                <option value={1}>1 hop</option>
                <option value={2}>2 hops</option>
                <option value={3}>3 hops</option>
                <option value={5}>5 hops</option>
                <option value={-1}>all (transitive)</option>
              </select>
            </label>
            {graph.data && (
              <span className="ml-auto text-xs text-gray-500">
                {graph.data.nodes.length} nodes, {graph.data.edges.length} edges
              </span>
            )}
          </div>
          {graph.data && <DependencyGraph graph={graph.data} />}
        </div>
      )}
      {tab === 'builds' && <BuildsTab assetId={id} />}
      {tab === 'runtime' && <RuntimeTab assetId={id} />}
      {tab === 'cluster' && <ClusterTab assetId={id} />}
      {tab === 'analyze' && <AnalyzeTab assetId={id} />}
      {tab === 'changes' && <ChangesTab assetId={id} />}
      {tab === 'fixtures' && <FixturesTab assetId={id} />}
      {tab === 'docs' && <DocsTab assetId={id} />}
      {tab === 'panels' && <PanelsTab assetId={id} />}
    </div>
  );
}

