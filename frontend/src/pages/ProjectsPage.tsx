import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Asset, type Project } from '../api';

/**
 * Projects page — manually-curated tree of containers for assets. The tree on the left
 * is editable (add child / rename / delete / re-parent via drag); the main pane shows
 * the selected project's metadata + its assets. Drag an asset card between projects to
 * move it; hold Alt while dropping to copy (assets can belong to multiple projects).
 */
export function ProjectsPage() {
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => api.listProjects() });
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const tree = useMemo(() => buildTree(projects.data ?? []), [projects.data]);
  const selected = (projects.data ?? []).find((p) => p.id === selectedId) ?? null;

  // First load: auto-select the first root project so the main pane isn't empty.
  if (selectedId === null && tree.length > 0) {
    queueMicrotask(() => setSelectedId(tree[0].id));
  }

  return (
    <div className="grid grid-cols-[260px_1fr_300px] gap-4">
      <aside className="rounded border border-gray-200 bg-white">
        <header className="flex items-center justify-between border-b border-gray-200 px-3 py-2">
          <h2 className="text-sm font-medium text-gray-900">Projects</h2>
          <NewProjectButton parentId={null} label="+ root" />
        </header>
        <div className="max-h-[70vh] overflow-auto p-2">
          {projects.isLoading && <p className="text-xs text-gray-500">Loading…</p>}
          {projects.error && <p className="text-xs text-red-600">{(projects.error as Error).message}</p>}
          {tree.length === 0 && projects.data && (
            <p className="text-xs text-gray-500">
              No projects yet. Click <strong>+ root</strong> to create one.
            </p>
          )}
          <ul className="space-y-0.5">
            {tree.map((n) => (
              <TreeNode
                key={n.id}
                node={n}
                depth={0}
                selectedId={selectedId}
                onSelect={setSelectedId}
              />
            ))}
          </ul>
        </div>
      </aside>

      <section>
        {selected ? (
          // Remount on selection change so the editable inputs re-hydrate from the new project.
          <ProjectDetail key={selected.id} project={selected} />
        ) : (
          <div className="rounded border border-gray-200 bg-white p-6 text-sm text-gray-500">
            Select a project on the left, or create one with <strong>+ root</strong>.
          </div>
        )}
      </section>

      <AssetPalette />
    </div>
  );
}

/* ─── asset palette (right rail — drag rows onto a project tree node or detail drop zone) ─── */

function AssetPalette() {
  const [q, setQ] = useState('');
  const [type, setType] = useState('');
  const { data, isLoading, error } = useQuery({
    queryKey: ['assets', q, type, false],
    queryFn: () => api.listAssets(q || undefined, type || undefined, undefined, undefined),
    staleTime: 30_000,
  });

  return (
    <aside className="rounded border border-gray-200 bg-white">
      <header className="border-b border-gray-200 px-3 py-2">
        <h2 className="text-sm font-medium text-gray-900">Assets</h2>
        <p className="mt-0.5 text-[11px] text-gray-500">
          Drag onto a project (Alt = copy when moving between projects).
        </p>
      </header>
      <div className="space-y-2 p-2">
        <input
          type="search"
          placeholder="Search assets…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="w-full rounded border border-gray-300 px-2 py-1 text-xs focus:border-blue-500 focus:outline-none"
        />
        <select
          value={type}
          onChange={(e) => setType(e.target.value)}
          className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
        >
          <option value="">All types</option>
          <option value="library">Library</option>
          <option value="service">Service</option>
          <option value="meta-asset">Meta-asset</option>
        </select>
        <div className="max-h-[60vh] overflow-auto">
          {isLoading && <p className="text-xs text-gray-500">Loading…</p>}
          {error && <p className="text-xs text-red-600">{(error as Error).message}</p>}
          {data && data.length === 0 && (
            <p className="text-xs text-gray-500">No assets match.</p>
          )}
          <ul className="space-y-1">
            {(data ?? []).map((a) => (
              <PaletteAssetRow key={a.id} asset={a} />
            ))}
          </ul>
        </div>
      </div>
    </aside>
  );
}

function PaletteAssetRow({ asset }: { asset: Asset }) {
  return (
    <li
      draggable
      onDragStart={(e) => {
        // Empty fromId — TreeNode's drop handler treats this as "add to project"
        // rather than a cross-project move. effectAllowed must include 'move' so
        // the tree's default dropEffect='move' is compatible (otherwise the
        // browser rejects the drop with a no-drop cursor).
        e.dataTransfer.setData('application/x-devportal-asset-id', `|${asset.id}`);
        e.dataTransfer.effectAllowed = 'copyMove';
      }}
      className="group flex cursor-grab items-center gap-1.5 rounded border border-gray-100 bg-white px-2 py-1 hover:border-gray-300 active:cursor-grabbing"
      title={asset.description ?? asset.id}
    >
      <span className="text-gray-400">⠿</span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-xs font-medium text-gray-900">{asset.name}</div>
        <div className="truncate text-[10px] text-gray-500">{asset.id}</div>
      </div>
      <span className="rounded border border-gray-200 bg-gray-50 px-1 py-0.5 text-[9px] uppercase tracking-wide text-gray-600">
        {asset.type}
      </span>
    </li>
  );
}

/* ─── tree ─── */

interface TreeShape extends Project { children: TreeShape[] }

function buildTree(flat: Project[]): TreeShape[] {
  const byId = new Map<number, TreeShape>();
  for (const p of flat) byId.set(p.id, { ...p, children: [] });
  const roots: TreeShape[] = [];
  for (const p of flat) {
    const node = byId.get(p.id)!;
    if (p.parentId == null) roots.push(node);
    else byId.get(p.parentId)?.children.push(node);
  }
  const sortRec = (xs: TreeShape[]) => {
    xs.sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
    for (const x of xs) sortRec(x.children);
  };
  sortRec(roots);
  return roots;
}

function TreeNode({
  node, depth, selectedId, onSelect,
}: {
  node: TreeShape;
  depth: number;
  selectedId: number | null;
  onSelect: (id: number) => void;
}) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(true);
  const setParent = useMutation({
    mutationFn: (parentId: number | null) => api.setProjectParent(node.id, parentId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['projects'] }),
  });

  return (
    <li>
      <div
        draggable
        onDragStart={(e) => {
          e.dataTransfer.setData('application/x-devportal-project-id', String(node.id));
          e.dataTransfer.effectAllowed = 'move';
        }}
        onDragOver={(e) => {
          if (Array.from(e.dataTransfer.types).some((t) =>
            t === 'application/x-devportal-project-id' ||
            t === 'application/x-devportal-asset-id')) {
            e.preventDefault();
            e.dataTransfer.dropEffect = e.altKey ? 'copy' : 'move';
          }
        }}
        onDrop={(e) => {
          // Drop a project here → re-parent it to this node.
          const projId = e.dataTransfer.getData('application/x-devportal-project-id');
          if (projId && Number(projId) !== node.id) {
            e.preventDefault();
            setParent.mutate(node.id);
            return;
          }
          // Drop an asset here → add to / move to this project.
          const assetPayload = e.dataTransfer.getData('application/x-devportal-asset-id');
          if (assetPayload) {
            e.preventDefault();
            const [fromId, assetId] = assetPayload.split('|');
            const copy = e.altKey;
            const from = fromId ? Number(fromId) : null;
            (async () => {
              if (from && from !== node.id) {
                await api.moveAssetBetweenProjects(from, node.id, assetId, copy);
              } else {
                await api.addAssetToProject(node.id, assetId);
              }
              qc.invalidateQueries({ queryKey: ['project-assets'] });
              qc.invalidateQueries({ queryKey: ['projects'] });
            })();
          }
        }}
        onClick={() => onSelect(node.id)}
        className={`group flex items-center gap-1 rounded px-2 py-1 text-sm ${
          selectedId === node.id ? 'bg-blue-100 text-blue-900' : 'hover:bg-gray-100'
        }`}
        style={{ paddingLeft: 8 + depth * 12 }}
        title="Drag to re-parent · drop assets here to add"
      >
        <button
          aria-label={open ? 'Collapse' : 'Expand'}
          onClick={(e) => { e.stopPropagation(); setOpen((v) => !v); }}
          className={`inline-flex h-4 w-4 items-center justify-center text-gray-400 ${
            node.children.length === 0 ? 'invisible' : 'hover:text-gray-700'
          }`}
        >
          <span className={`transition-transform ${open ? 'rotate-90' : ''}`}>▸</span>
        </button>
        <span className="truncate">{node.name}</span>
      </div>
      {open && node.children.length > 0 && (
        <ul className="space-y-0.5">
          {node.children.map((c) => (
            <TreeNode
              key={c.id}
              node={c}
              depth={depth + 1}
              selectedId={selectedId}
              onSelect={onSelect}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

function NewProjectButton({ parentId, label }: { parentId: number | null; label: string }) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const create = useMutation({
    mutationFn: () => api.createProject({ parentId: parentId ?? undefined, name: name.trim() }),
    onSuccess: () => {
      setName(''); setOpen(false);
      qc.invalidateQueries({ queryKey: ['projects'] });
    },
  });
  return open ? (
    <span className="flex items-center gap-1">
      <input
        autoFocus
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && name.trim() && !create.isPending) create.mutate();
          if (e.key === 'Escape') { setOpen(false); setName(''); }
        }}
        placeholder="project name"
        className="w-32 rounded border border-gray-300 px-1 py-0.5 text-xs"
      />
      <button
        onClick={() => create.mutate()}
        disabled={!name.trim() || create.isPending}
        className="rounded bg-blue-600 px-1.5 py-0.5 text-[11px] text-white hover:bg-blue-700 disabled:opacity-50"
      >
        {create.isPending ? '…' : 'add'}
      </button>
      <button
        onClick={() => { setOpen(false); setName(''); }}
        className="text-[11px] text-gray-500 hover:text-gray-700"
      >
        ×
      </button>
    </span>
  ) : (
    <button
      onClick={() => setOpen(true)}
      className="text-[11px] text-blue-700 hover:underline"
    >
      {label}
    </button>
  );
}

/* ─── detail pane ─── */

function ProjectDetail({ project }: { project: Project }) {
  const qc = useQueryClient();
  const assets = useQuery({
    queryKey: ['project-assets', project.id],
    queryFn: () => api.projectAssets(project.id),
  });

  const [name, setName] = useState(project.name);
  const [description, setDescription] = useState(project.description ?? '');
  const [metadataText, setMetadataText] = useState(
    project.metadata ? JSON.stringify(project.metadata, null, 2) : ''
  );
  const [metadataError, setMetadataError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () => {
      let metadata: any = undefined;
      if (metadataText.trim()) {
        try { metadata = JSON.parse(metadataText); }
        catch (e) { setMetadataError((e as Error).message); throw e; }
      } else {
        metadata = null;
      }
      setMetadataError(null);
      return api.updateProject(project.id, {
        name: name.trim(),
        description: description.trim() || null,
        metadata,
      });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['projects'] }),
  });
  const remove = useMutation({
    mutationFn: () => api.deleteProject(project.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['projects'] }),
  });
  const moveToRoot = useMutation({
    mutationFn: () => api.setProjectParent(project.id, null),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['projects'] }),
  });

  return (
    <div className="space-y-4 rounded border border-gray-200 bg-white p-4">
      <header className="flex items-start justify-between gap-2">
        <div className="flex-1">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            onBlur={() => name.trim() !== project.name && save.mutate()}
            className="w-full rounded border border-transparent text-xl font-semibold text-gray-900 hover:border-gray-200 focus:border-blue-400 focus:outline-none"
          />
          <p className="text-xs text-gray-500">
            project #{project.id}
            {project.parentId != null && <> · child of #{project.parentId}</>}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <NewProjectButton parentId={project.id} label="+ child" />
          {project.parentId != null && (
            <button
              onClick={() => moveToRoot.mutate()}
              className="text-xs text-gray-600 hover:text-gray-900"
              title="Move this project back to the top level"
            >
              move to root
            </button>
          )}
          <button
            onClick={() => {
              if (confirm(`Delete project "${project.name}" and any sub-projects? Asset rows aren't deleted.`)) {
                remove.mutate();
              }
            }}
            className="rounded border border-red-300 px-2 py-1 text-xs text-red-700 hover:bg-red-50"
          >
            Delete
          </button>
        </div>
      </header>

      <section>
        <label className="text-xs text-gray-600">Description</label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          onBlur={() => (description.trim() || '') !== (project.description ?? '') && save.mutate()}
          rows={3}
          placeholder="What is this project? Owners, links, status…"
          className="mt-0.5 w-full rounded border border-gray-300 p-2 text-sm"
        />
      </section>

      <section>
        <label className="text-xs text-gray-600">
          Metadata (JSON — arbitrary fields like owner, dashboard URL, status, etc.)
        </label>
        <textarea
          value={metadataText}
          onChange={(e) => setMetadataText(e.target.value)}
          onBlur={() => save.mutate()}
          rows={4}
          spellCheck={false}
          placeholder='{ "owner": "platform-team", "status": "stable" }'
          className="mt-0.5 w-full rounded border border-gray-300 p-2 font-mono text-xs"
        />
        {metadataError && <p className="text-xs text-red-600">JSON parse: {metadataError}</p>}
      </section>

      <section
        onDragOver={(e) => {
          if (e.dataTransfer.types.includes('application/x-devportal-asset-id')) {
            e.preventDefault();
            e.dataTransfer.dropEffect = e.altKey ? 'copy' : 'move';
          }
        }}
        onDrop={(e) => {
          const payload = e.dataTransfer.getData('application/x-devportal-asset-id');
          if (!payload) return;
          e.preventDefault();
          const [fromId, assetId] = payload.split('|');
          const from = fromId ? Number(fromId) : null;
          const copy = e.altKey;
          (async () => {
            if (from && from !== project.id) {
              await api.moveAssetBetweenProjects(from, project.id, assetId, copy);
            } else {
              await api.addAssetToProject(project.id, assetId);
            }
            qc.invalidateQueries({ queryKey: ['project-assets', project.id] });
            qc.invalidateQueries({ queryKey: ['projects'] });
          })();
        }}
        className="rounded border border-dashed border-transparent transition-colors hover:border-blue-200"
      >
        <header className="mb-2 flex items-center justify-between">
          <h3 className="text-sm font-medium text-gray-900">Assets in this project</h3>
          <span className="text-xs text-gray-500">
            {assets.data?.length ?? '…'} asset{(assets.data?.length ?? 0) === 1 ? '' : 's'}
          </span>
        </header>
        {assets.isLoading && <p className="text-xs text-gray-500">Loading…</p>}
        {assets.data && assets.data.length === 0 && (
          <p className="rounded border border-dashed border-gray-300 bg-gray-50 p-4 text-center text-xs text-gray-500">
            Drag assets here from the right rail, or use <em>Add to project</em> on the assets page.
          </p>
        )}
        <ul className="space-y-1">
          {(assets.data ?? []).map((a) => (
            <ProjectAssetRow key={a.id} asset={a} projectId={project.id} />
          ))}
        </ul>
      </section>
    </div>
  );
}

function ProjectAssetRow({ asset, projectId }: { asset: Asset; projectId: number }) {
  const qc = useQueryClient();
  const remove = useMutation({
    mutationFn: () => api.removeAssetFromProject(projectId, asset.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['project-assets', projectId] });
    },
  });
  return (
    <li
      draggable
      onDragStart={(e) => {
        // Encode "from project | asset id" so the drop target can move (or copy with Alt).
        e.dataTransfer.setData('application/x-devportal-asset-id', `${projectId}|${asset.id}`);
        e.dataTransfer.effectAllowed = 'copyMove';
      }}
      className="group flex items-center justify-between gap-2 rounded border border-gray-100 bg-white p-2 hover:border-gray-300"
    >
      <div className="flex min-w-0 items-center gap-2">
        <span className="text-gray-400">⠿</span>
        <Link to={`/assets/${asset.id}`} className="font-medium text-gray-900 hover:underline">
          {asset.name}
        </Link>
        <span className="text-xs text-gray-500">{asset.id}</span>
      </div>
      <button
        onClick={() => remove.mutate()}
        className="text-xs text-gray-400 opacity-0 hover:text-red-600 group-hover:opacity-100"
        title="Remove from this project"
      >
        ×
      </button>
    </li>
  );
}
