import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Asset } from '../api';
import { StarRating } from '../components/StarRating';
import { SuggestionsModal } from '../components/SuggestionsModal';

/**
 * Asset list with inline tag chip-editor, multi-select, tag filter, and group-by mode.
 *
 * <p>Tag edits PATCH the asset directly (single source of truth: the asset DB row); the
 * existing manifest-edit pipeline picks up the change on the next workspace sync. The
 * batch toolbar at the bottom drives operations that work on N selected assets at once.
 */
export function AssetsPage() {
  const [q, setQ] = useState('');
  const [type, setType] = useState('');
  const [favoritesOnly, setFavoritesOnly] = useState(false);
  const [activeTags, setActiveTags] = useState<Set<string>>(new Set());
  const [groupBy, setGroupBy] = useState<'none' | 'type' | 'tag'>('none');
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const { data, isLoading, error } = useQuery({
    queryKey: ['assets', q, type, favoritesOnly],
    queryFn: () =>
      api.listAssets(q || undefined, type || undefined, undefined, favoritesOnly || undefined),
  });
  const favorites = useQuery({
    queryKey: ['favorites'],
    queryFn: () => api.listAssets(undefined, undefined, undefined, true),
    staleTime: 30_000,
  });

  // Tag filter is applied client-side on top of the server's search/type filter.
  const filtered = useMemo(() => {
    if (!data) return [];
    if (activeTags.size === 0) return data;
    return data.filter((a) => Array.from(activeTags).every((t) => a.tags.includes(t)));
  }, [data, activeTags]);

  // Distinct tags drawn from whatever passed the server filter — stays in sync as you search.
  const allTags = useMemo(() => {
    const m = new Map<string, number>();
    for (const a of data ?? []) for (const t of a.tags) m.set(t, (m.get(t) ?? 0) + 1);
    return Array.from(m.entries()).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  }, [data]);

  const toggleTag = (t: string) => {
    setActiveTags((prev) => {
      const next = new Set(prev);
      if (next.has(t)) next.delete(t); else next.add(t);
      return next;
    });
  };

  const visibleIds = filtered.map((a) => a.id);
  const allVisibleSelected = visibleIds.length > 0 && visibleIds.every((id) => selected.has(id));
  const toggleAllVisible = () => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (allVisibleSelected) {
        for (const id of visibleIds) next.delete(id);
      } else {
        for (const id of visibleIds) next.add(id);
      }
      return next;
    });
  };
  const toggleOne = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });

  return (
    <div className="space-y-4 pb-24">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-gray-900">Assets</h1>
        <Link
          to="/register"
          className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700"
        >
          Register asset
        </Link>
      </div>

      {favorites.data && favorites.data.length > 0 && !favoritesOnly && !q && (
        <FavoritesStrip assets={favorites.data} />
      )}

      <div className="flex flex-wrap items-center gap-3">
        <input
          type="search"
          placeholder='Search — multiple words OR by default; use AND for "all of"'
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="flex-1 min-w-[260px] rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none"
        />
        <select
          value={type}
          onChange={(e) => setType(e.target.value)}
          className="rounded border border-gray-300 px-3 py-1.5 text-sm"
        >
          <option value="">All types</option>
          <option value="library">Library</option>
          <option value="service">Service</option>
          <option value="meta-asset">Meta-asset</option>
        </select>
        <label className="inline-flex items-center gap-1 text-sm">
          <input
            type="checkbox"
            checked={favoritesOnly}
            onChange={(e) => setFavoritesOnly(e.target.checked)}
            className="rounded border-gray-300"
          />
          <span>♥ Favorites only</span>
        </label>
        <div className="ml-auto flex items-center gap-1 text-xs">
          <span className="text-gray-500">Group by:</span>
          {(['none', 'type', 'tag'] as const).map((g) => (
            <button
              key={g}
              onClick={() => setGroupBy(g)}
              className={`rounded px-2 py-1 ${
                groupBy === g
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              {g}
            </button>
          ))}
        </div>
      </div>

      {allTags.length > 0 && (
        <TagFilterBar
          tags={allTags}
          active={activeTags}
          onToggle={toggleTag}
          onClear={() => setActiveTags(new Set())}
        />
      )}

      {isLoading && <p className="text-gray-500">Loading…</p>}
      {error && <p className="text-red-600">{(error as Error).message}</p>}
      {data && filtered.length === 0 && (
        <p className="text-gray-500">
          {data.length === 0 ? 'No assets yet. Register one.' : 'No assets match the filter.'}
        </p>
      )}

      {filtered.length > 0 && (
        <AssetTree
          assets={filtered}
          groupBy={groupBy}
          selected={selected}
          onToggleOne={toggleOne}
          onToggleAllVisible={toggleAllVisible}
          allVisibleSelected={allVisibleSelected}
        />
      )}

      {selected.size > 0 && (
        <BatchToolbar
          ids={Array.from(selected)}
          allAssets={data ?? []}
          onClear={() => setSelected(new Set())}
        />
      )}
    </div>
  );
}

/* ─── filter / group / batch components ─── */

function TagFilterBar({
  tags, active, onToggle, onClear,
}: {
  tags: [string, number][];
  active: Set<string>;
  onToggle: (t: string) => void;
  onClear: () => void;
}) {
  return (
    <div className="flex flex-wrap items-center gap-1 rounded border border-gray-200 bg-gray-50 p-2">
      <span className="mr-1 text-xs text-gray-500">Filter by tag:</span>
      {tags.map(([t, n]) => (
        <button
          key={t}
          onClick={() => onToggle(t)}
          className={`rounded px-1.5 py-0.5 text-[11px] ${
            active.has(t)
              ? 'bg-blue-600 text-white'
              : 'bg-white text-gray-700 hover:bg-gray-100 border border-gray-200'
          }`}
          title={`${n} asset${n === 1 ? '' : 's'}`}
        >
          {t} <span className="opacity-60">{n}</span>
        </button>
      ))}
      {active.size > 0 && (
        <button onClick={onClear} className="ml-1 text-[11px] text-gray-500 underline hover:text-gray-700">
          clear
        </button>
      )}
    </div>
  );
}

function AssetTree({
  assets, groupBy, selected, onToggleOne, onToggleAllVisible, allVisibleSelected,
}: {
  assets: Asset[];
  groupBy: 'none' | 'type' | 'tag';
  selected: Set<string>;
  onToggleOne: (id: string) => void;
  onToggleAllVisible: () => void;
  allVisibleSelected: boolean;
}) {
  if (groupBy === 'none') {
    return (
      <ListWrapper allVisibleSelected={allVisibleSelected} onToggleAllVisible={onToggleAllVisible}>
        {assets.map((a) => (
          <AssetRow
            key={a.id}
            asset={a}
            depth={0}
            ancestors={new Set([a.id])}
            selected={selected.has(a.id)}
            onToggleSelect={() => onToggleOne(a.id)}
          />
        ))}
      </ListWrapper>
    );
  }
  // Bucket by type or by tag — when by tag, an asset shows up under each of its tags.
  const groups = new Map<string, Asset[]>();
  for (const a of assets) {
    if (groupBy === 'type') {
      const key = a.type ?? 'unknown';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(a);
    } else {
      if (a.tags.length === 0) {
        if (!groups.has('(untagged)')) groups.set('(untagged)', []);
        groups.get('(untagged)')!.push(a);
      } else {
        for (const t of a.tags) {
          if (!groups.has(t)) groups.set(t, []);
          groups.get(t)!.push(a);
        }
      }
    }
  }
  const keys = Array.from(groups.keys()).sort();

  return (
    <div className="space-y-3">
      {keys.map((k) => (
        <section key={k} className="rounded border border-gray-200 bg-white">
          <header className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-4 py-2">
            <h2 className="text-sm font-medium text-gray-900">{k}</h2>
            <span className="text-xs text-gray-500">
              {groups.get(k)!.length} asset{groups.get(k)!.length === 1 ? '' : 's'}
            </span>
          </header>
          <ul className="divide-y divide-gray-200">
            {groups.get(k)!.map((a) => (
              <AssetRow
                key={`${k}:${a.id}`}
                asset={a}
                depth={0}
                ancestors={new Set([a.id])}
                selected={selected.has(a.id)}
                onToggleSelect={() => onToggleOne(a.id)}
              />
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

function ListWrapper({
  children, allVisibleSelected, onToggleAllVisible,
}: {
  children: React.ReactNode;
  allVisibleSelected: boolean;
  onToggleAllVisible: () => void;
}) {
  return (
    <div className="rounded border border-gray-200 bg-white">
      <div className="flex items-center gap-2 border-b border-gray-200 bg-gray-50 px-4 py-1.5 text-xs text-gray-600">
        <input
          type="checkbox"
          checked={allVisibleSelected}
          onChange={onToggleAllVisible}
          aria-label="Select all visible"
        />
        <span>select all visible</span>
      </div>
      <ul className="divide-y divide-gray-200">{children}</ul>
    </div>
  );
}

function BatchToolbar({
  ids, allAssets, onClear,
}: {
  ids: string[];
  allAssets: Asset[];
  onClear: () => void;
}) {
  const qc = useQueryClient();
  const selected = allAssets.filter((a) => ids.includes(a.id));
  const [mode, setMode] = useState<'idle' | 'addTag' | 'removeTag' | 'addToProject'>('idle');
  const [tag, setTag] = useState('');
  const [suggestKind, setSuggestKind] = useState<null | 'tags' | 'description'>(null);
  const [projectId, setProjectId] = useState<number | ''>('');
  const projects = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.listProjects(),
    enabled: mode === 'addToProject',
  });
  const addToProject = useMutation({
    mutationFn: async () => {
      if (typeof projectId !== 'number') return;
      await Promise.all(selected.map((a) => api.addAssetToProject(projectId, a.id)));
    },
    onSuccess: () => {
      setMode('idle'); setProjectId('');
      qc.invalidateQueries({ queryKey: ['project-assets'] });
    },
  });

  const apply = useMutation({
    mutationFn: async () => {
      if (!tag.trim() || mode === 'idle') return;
      const t = tag.trim();
      await Promise.all(
        selected.map((a) => {
          const next = mode === 'addTag'
            ? Array.from(new Set([...a.tags, t]))
            : a.tags.filter((x) => x !== t);
          return api.updateAsset(a.id, { tags: next });
        }),
      );
    },
    onSuccess: () => {
      setMode('idle'); setTag('');
      qc.invalidateQueries({ queryKey: ['assets'] });
      qc.invalidateQueries({ queryKey: ['favorites'] });
    },
  });

  return (
    <div className="fixed inset-x-0 bottom-0 z-30 border-t border-gray-200 bg-white shadow-lg">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-2 px-4 py-2 text-sm">
        <span className="font-medium">{ids.length} selected</span>
        {mode === 'idle' && (
          <>
            <button
              onClick={() => setMode('addTag')}
              className="rounded bg-blue-600 px-2 py-1 text-xs text-white hover:bg-blue-700"
            >
              + Add tag to all
            </button>
            <button
              onClick={() => setMode('removeTag')}
              className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50"
            >
              − Remove tag from all
            </button>
            <button
              onClick={() => setSuggestKind('tags')}
              className="rounded border border-purple-300 bg-purple-50 px-2 py-1 text-xs text-purple-800 hover:bg-purple-100"
              title="Use the local Ollama model to propose tags from each asset's docs"
            >
              ✨ Suggest tags…
            </button>
            <button
              onClick={() => setSuggestKind('description')}
              className="rounded border border-purple-300 bg-purple-50 px-2 py-1 text-xs text-purple-800 hover:bg-purple-100"
              title="Use the local Ollama model to draft a description from each asset's docs"
            >
              ✨ Generate descriptions…
            </button>
            <button
              onClick={() => setMode('addToProject')}
              className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50"
              title="Add the selected assets to a project"
            >
              ↳ Add to project
            </button>
          </>
        )}
        {mode === 'addToProject' && (
          <>
            <select
              value={projectId}
              onChange={(e) => setProjectId(e.target.value ? Number(e.target.value) : '')}
              className="rounded border border-gray-300 px-2 py-1 text-xs"
            >
              <option value="">— pick a project —</option>
              {(projects.data ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.parentId != null ? '↳ ' : ''}{p.name}
                </option>
              ))}
            </select>
            <button
              onClick={() => addToProject.mutate()}
              disabled={typeof projectId !== 'number' || addToProject.isPending}
              className="rounded bg-blue-600 px-2 py-1 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {addToProject.isPending ? 'Adding…' : `Add ${ids.length} to project`}
            </button>
            <button
              onClick={() => { setMode('idle'); setProjectId(''); }}
              className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50"
            >
              Cancel
            </button>
            {addToProject.error && (
              <span className="text-xs text-red-600">{(addToProject.error as Error).message}</span>
            )}
          </>
        )}
        {(mode === 'addTag' || mode === 'removeTag') && (
          <>
            <input
              autoFocus
              value={tag}
              onChange={(e) => setTag(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && tag.trim() && !apply.isPending) apply.mutate();
                if (e.key === 'Escape') { setMode('idle'); setTag(''); }
              }}
              placeholder={mode === 'addTag' ? 'tag to add' : 'tag to remove'}
              className="rounded border border-gray-300 px-2 py-1 text-xs"
            />
            <button
              onClick={() => apply.mutate()}
              disabled={!tag.trim() || apply.isPending}
              className="rounded bg-blue-600 px-2 py-1 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {apply.isPending ? 'Applying…' : 'Apply'}
            </button>
            <button
              onClick={() => { setMode('idle'); setTag(''); }}
              className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50"
            >
              Cancel
            </button>
            {apply.error && (
              <span className="text-xs text-red-600">{(apply.error as Error).message}</span>
            )}
          </>
        )}
        <button onClick={onClear} className="ml-auto text-xs text-gray-500 hover:text-gray-700 underline">
          clear selection
        </button>
      </div>
      {suggestKind && (
        <SuggestionsModal
          kind={suggestKind}
          assets={selected}
          onClose={() => {
            setSuggestKind(null);
            // Refetch assets so freshly-applied changes show up immediately on the list.
            qc.invalidateQueries({ queryKey: ['assets'] });
          }}
        />
      )}
    </div>
  );
}

/* ─── favorites strip + asset row (with inline tag editor) ─── */

function FavoritesStrip({ assets }: { assets: Asset[] }) {
  const top = [...assets]
    .sort((a, b) => (b.rating ?? 0) - (a.rating ?? 0) || a.id.localeCompare(b.id))
    .slice(0, 8);
  return (
    <section className="rounded border border-amber-200 bg-amber-50/40 p-3">
      <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-amber-800">
        ♥ Top favorites
      </h2>
      <div className="flex flex-wrap gap-2">
        {top.map((a) => (
          <Link
            key={a.id}
            to={`/assets/${a.id}`}
            className="flex min-w-[180px] max-w-xs flex-1 flex-col rounded border border-amber-200 bg-white px-3 py-2 hover:border-amber-400"
          >
            <span className="truncate text-sm font-medium text-gray-900">{a.name}</span>
            <span className="truncate text-[11px] text-gray-500">{a.id}</span>
            <StarRating value={a.rating} readOnly size={12} />
          </Link>
        ))}
      </div>
    </section>
  );
}

/**
 * One row in the assets list. Recursively renders children when expanded by fetching the
 * 1-hop producer graph for this asset. {@code ancestors} prevents cycles in lazy expansion.
 */
function AssetRow({
  asset, depth, ancestors, selected, onToggleSelect,
}: {
  asset: Asset;
  depth: number;
  ancestors: Set<string>;
  selected: boolean;
  onToggleSelect: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const graph = useQuery({
    queryKey: ['asset-children', asset.id],
    queryFn: () =>
      api.getGraph(asset.id, { direction: 'producers', producerDepth: 1, consumerDepth: 0 }),
    enabled: expanded,
    staleTime: 30_000,
  });

  const children = graph.data?.nodes.filter((n) => n.id !== asset.id) ?? [];
  const hasChildren = expanded ? children.length > 0 : null;

  return (
    <li>
      <div
        className="group flex items-start px-4 py-3 hover:bg-gray-50"
        style={{ paddingLeft: 16 + depth * 24 }}
      >
        {/* Multi-select checkbox — Phase 2 hook for batch operations. */}
        <input
          type="checkbox"
          checked={selected}
          onChange={onToggleSelect}
          aria-label={`Select ${asset.name}`}
          className="mt-1.5 mr-2 shrink-0"
        />

        <button
          aria-label={expanded ? 'Collapse' : 'Expand'}
          onClick={() => setExpanded((v) => !v)}
          className="mt-1 mr-2 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded text-gray-400 hover:bg-gray-200 hover:text-gray-700"
          title="Show producers (what this asset depends on)"
        >
          <span className={`transition-transform ${expanded ? 'rotate-90' : ''}`}>▸</span>
        </button>

        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-3">
            <div className="min-w-0 flex items-center gap-2">
              {asset.favorite && <span title="Favorite" style={{ color: '#dc2626' }}>♥</span>}
              <Link
                to={`/assets/${asset.id}`}
                className="font-medium text-gray-900 hover:underline"
              >
                {asset.name}
              </Link>
              <span className="ml-2 text-xs text-gray-500">{asset.id}</span>
              {asset.rating != null && (
                <span className="ml-1" title={`${asset.rating}/5`}>
                  <StarRating value={asset.rating} readOnly size={12} />
                </span>
              )}
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <Badge>{asset.type}</Badge>
              {asset.language && <Badge variant="muted">{asset.language}</Badge>}
              <Badge variant={asset.lifecycle === 'stable' ? 'good' : 'muted'}>
                {asset.lifecycle}
              </Badge>
            </div>
          </div>

          {asset.description && (
            <p className="mt-1 line-clamp-2 text-xs text-gray-600">{asset.description}</p>
          )}

          <InlineTagEditor asset={asset} />
        </div>
      </div>

      {expanded && (
        <div className="border-t border-gray-100 bg-gray-50">
          {graph.isLoading && (
            <p className="px-4 py-2 text-xs text-gray-500" style={{ paddingLeft: 16 + (depth + 1) * 24 }}>
              Loading dependencies…
            </p>
          )}
          {graph.error && (
            <p className="px-4 py-2 text-xs text-red-600" style={{ paddingLeft: 16 + (depth + 1) * 24 }}>
              {(graph.error as Error).message}
            </p>
          )}
          {hasChildren === false && (
            <p className="px-4 py-2 text-xs text-gray-500" style={{ paddingLeft: 16 + (depth + 1) * 24 }}>
              No dependencies.
            </p>
          )}
          {children.length > 0 && (
            <ul className="divide-y divide-gray-100">
              {children.map((child) =>
                ancestors.has(child.id) ? (
                  <li
                    key={child.id}
                    className="px-4 py-2 text-xs text-amber-700"
                    style={{ paddingLeft: 16 + (depth + 1) * 24 }}
                  >
                    ↻ <span className="font-mono">{child.id}</span> (cycle)
                  </li>
                ) : (
                  <AssetRow
                    key={child.id}
                    asset={child}
                    depth={depth + 1}
                    ancestors={new Set([...ancestors, child.id])}
                    selected={false}
                    onToggleSelect={() => {/* nested rows aren't selectable */}}
                  />
                )
              )}
            </ul>
          )}
        </div>
      )}
    </li>
  );
}

/**
 * Inline chips with a hover-x to remove plus a "+" button to add. PATCH on every change;
 * react-query invalidates the assets list so we re-render fresh.
 */
function InlineTagEditor({ asset }: { asset: Asset }) {
  const qc = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [text, setText] = useState('');

  const update = useMutation({
    mutationFn: (tags: string[]) => api.updateAsset(asset.id, { tags }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['assets'] });
      qc.invalidateQueries({ queryKey: ['favorites'] });
    },
  });

  const add = () => {
    const t = text.trim();
    if (!t) { setAdding(false); return; }
    if (asset.tags.includes(t)) { setText(''); setAdding(false); return; }
    update.mutate([...asset.tags, t]);
    setText(''); setAdding(false);
  };
  const remove = (t: string) => update.mutate(asset.tags.filter((x) => x !== t));

  return (
    <div className="mt-1 flex flex-wrap items-center gap-1">
      {asset.tags.map((t) => (
        <span
          key={t}
          className="group/tag inline-flex items-center gap-1 rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-700"
        >
          {t}
          <button
            onClick={() => remove(t)}
            className="opacity-50 hover:opacity-100 hover:text-red-600"
            title="Remove tag"
            aria-label={`Remove tag ${t}`}
          >
            ×
          </button>
        </span>
      ))}
      {adding ? (
        <input
          autoFocus
          value={text}
          onChange={(e) => setText(e.target.value)}
          onBlur={add}
          onKeyDown={(e) => {
            if (e.key === 'Enter') add();
            if (e.key === 'Escape') { setAdding(false); setText(''); }
          }}
          placeholder="new tag"
          className="w-24 rounded border border-gray-300 px-1 py-0.5 text-[11px]"
        />
      ) : (
        <button
          onClick={() => setAdding(true)}
          className="rounded border border-dashed border-gray-300 px-1.5 py-0.5 text-[11px] text-gray-500 hover:border-gray-400 hover:text-gray-700"
          title="Add tag"
        >
          + tag
        </button>
      )}
      {update.error && (
        <span className="text-[10px] text-red-600">{(update.error as Error).message}</span>
      )}
    </div>
  );
}

function Badge({
  children,
  variant = 'default',
}: {
  children: React.ReactNode;
  variant?: 'default' | 'muted' | 'good';
}) {
  const cls =
    variant === 'good'
      ? 'border-green-300 bg-green-50 text-green-800'
      : variant === 'muted'
      ? 'border-gray-200 bg-gray-50 text-gray-600'
      : 'border-gray-300 bg-white text-gray-700';
  return (
    <span className={`rounded border px-1.5 py-0.5 text-[11px] ${cls}`}>{children}</span>
  );
}
