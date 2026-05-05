import { useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Asset, type TagSummary } from '../api';

/**
 * Inline tag editor: shows current tags as chips with × buttons, plus a typeahead input that
 * suggests existing tags from /api/tags (sorted by recency / usage count). Pressing Enter or
 * picking a suggestion adds the tag and persists via PATCH /api/assets/{id}.
 *
 * Tags are normalized to lowercase, alphanumeric + dash + dot + slash.
 */
export function TagEditor({ asset }: { asset: Asset }) {
  const qc = useQueryClient();
  const [draft, setDraft] = useState('');
  const [open, setOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const catalog = useQuery({
    queryKey: ['tags-catalog'],
    queryFn: () => api.listTags(),
    staleTime: 30_000,
  });

  const persist = useMutation({
    mutationFn: (next: string[]) => api.updateAsset(asset.id, { tags: next }),
    onMutate: async (next) => {
      await qc.cancelQueries({ queryKey: ['asset', asset.id] });
      const prev = qc.getQueryData<Asset>(['asset', asset.id]);
      if (prev) qc.setQueryData<Asset>(['asset', asset.id], { ...prev, tags: next });
      return { prev };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(['asset', asset.id], ctx.prev);
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['asset', asset.id] });
      qc.invalidateQueries({ queryKey: ['tags-catalog'] });
      qc.invalidateQueries({ queryKey: ['assets'] });
    },
  });

  const suggestions = useMemo(() => {
    if (!catalog.data) return [];
    const draftLower = draft.trim().toLowerCase();
    const have = new Set(asset.tags.map((t) => t.toLowerCase()));
    return catalog.data
      .filter((t: TagSummary) => !have.has(t.tag.toLowerCase()))
      .filter((t) => !draftLower || t.tag.toLowerCase().startsWith(draftLower))
      .slice(0, 12);
  }, [catalog.data, draft, asset.tags]);

  const addTag = (raw: string) => {
    const tag = raw.trim().toLowerCase().replace(/[^a-z0-9./-]/g, '');
    if (!tag) return;
    if (asset.tags.includes(tag)) return;
    persist.mutate([...asset.tags, tag]);
    setDraft('');
    setOpen(true);
    inputRef.current?.focus();
  };

  const removeTag = (tag: string) => {
    persist.mutate(asset.tags.filter((t) => t !== tag));
  };

  return (
    <div className="space-y-2 text-sm">
      <div className="flex flex-wrap items-center gap-1">
        {asset.tags.length === 0 && (
          <span className="text-xs text-gray-400">No tags yet.</span>
        )}
        {asset.tags.map((t) => (
          <span
            key={t}
            className="inline-flex items-center gap-1 rounded bg-blue-50 px-2 py-0.5 text-xs text-blue-800"
          >
            {t}
            <button
              type="button"
              onClick={() => removeTag(t)}
              className="text-blue-500 hover:text-blue-800"
              title="Remove tag"
            >
              ×
            </button>
          </span>
        ))}
      </div>
      <div className="relative">
        <input
          ref={inputRef}
          type="text"
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 120)}  // let click on suggestion register
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              addTag(draft);
            } else if (e.key === 'Escape') {
              setOpen(false);
            }
          }}
          placeholder="Add a tag (Enter to add)"
          className="w-full max-w-sm rounded border border-gray-300 px-3 py-1.5 text-sm"
        />
        {open && suggestions.length > 0 && (
          <ul className="absolute left-0 top-full z-10 mt-1 max-h-64 w-full max-w-sm overflow-auto rounded border border-gray-200 bg-white shadow">
            {suggestions.map((s) => (
              <li key={s.tag}>
                <button
                  type="button"
                  onMouseDown={(e) => e.preventDefault()}  // prevent input blur
                  onClick={() => addTag(s.tag)}
                  className="flex w-full items-center justify-between px-3 py-1.5 text-left text-sm hover:bg-gray-100"
                >
                  <span className="font-mono">{s.tag}</span>
                  <span className="text-xs text-gray-500">
                    {s.usageCount} use{s.usageCount === 1 ? '' : 's'}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      {persist.error && (
        <p className="text-xs text-red-600">{(persist.error as Error).message}</p>
      )}
    </div>
  );
}
