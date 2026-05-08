import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Asset } from '../api';

/**
 * Review modal for batched LLM suggestions. Walks the selected assets one by one
 * (sequentially — keeps small models from getting hammered), shows current vs
 * suggested for each, lets the admin edit/skip, then applies the accepted set.
 */
type Kind = 'tags' | 'description';

interface RowState {
  assetId: string;
  status: 'pending' | 'loading' | 'ok' | 'error';
  error?: string;
  currentText: string;
  suggestedText: string;
  /** What the user will actually save. Pre-filled with `suggestedText`; edited freely. */
  draftText: string;
  accepted: boolean;
}

export function SuggestionsModal({
  kind, assets, onClose,
}: {
  kind: Kind;
  assets: Asset[];
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const [rows, setRows] = useState<RowState[]>(() =>
    assets.map((a) => ({
      assetId: a.id,
      status: 'pending',
      currentText: kind === 'tags' ? a.tags.join(', ') : a.description ?? '',
      suggestedText: '',
      draftText: '',
      accepted: false,
    })),
  );
  const [running, setRunning] = useState(true);
  const [appliedCount, setAppliedCount] = useState<number | null>(null);

  // Run suggestions sequentially. Inside an async loop so the modal can render
  // intermediate states while later rows are still pending.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      for (let i = 0; i < assets.length; i++) {
        if (cancelled) return;
        setRows((prev) => prev.map((r, j) => (j === i ? { ...r, status: 'loading' } : r)));
        try {
          if (kind === 'description') {
            const s = await api.suggestAssetDescription(assets[i].id);
            if (cancelled) return;
            setRows((prev) =>
              prev.map((r, j) =>
                j === i
                  ? {
                      ...r,
                      status: 'ok',
                      suggestedText: s.suggested,
                      draftText: s.suggested,
                      accepted: true,
                    }
                  : r,
              ),
            );
          } else {
            const s = await api.suggestAssetTags(assets[i].id);
            if (cancelled) return;
            // Merge suggestions with current — admin can prune.
            const merged = Array.from(new Set([...(s.current ?? []), ...s.suggested]));
            const text = merged.join(', ');
            setRows((prev) =>
              prev.map((r, j) =>
                j === i
                  ? {
                      ...r,
                      status: 'ok',
                      suggestedText: s.suggested.join(', '),
                      draftText: text,
                      accepted: true,
                    }
                  : r,
              ),
            );
          }
        } catch (e) {
          if (cancelled) return;
          setRows((prev) =>
            prev.map((r, j) =>
              j === i ? { ...r, status: 'error', error: (e as Error).message } : r,
            ),
          );
        }
      }
      if (!cancelled) setRunning(false);
    })();
    return () => { cancelled = true; };
  }, [assets, kind]);

  const apply = useMutation({
    mutationFn: async () => {
      let n = 0;
      for (const r of rows) {
        if (!r.accepted || r.status !== 'ok') continue;
        if (kind === 'description') {
          await api.updateAsset(r.assetId, { description: r.draftText.trim() || null });
        } else {
          const tags = r.draftText.split(',').map((t) => t.trim()).filter(Boolean);
          await api.updateAsset(r.assetId, { tags });
        }
        n++;
      }
      return n;
    },
    onSuccess: (n) => {
      setAppliedCount(n);
      qc.invalidateQueries({ queryKey: ['assets'] });
      qc.invalidateQueries({ queryKey: ['favorites'] });
    },
  });

  const acceptedCount = rows.filter((r) => r.accepted && r.status === 'ok').length;
  const errorCount = rows.filter((r) => r.status === 'error').length;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
      <div className="flex max-h-[90vh] w-full max-w-3xl flex-col rounded-lg bg-white shadow-xl">
        <header className="flex items-start justify-between border-b border-gray-200 px-5 py-3">
          <div>
            <h2 className="text-base font-semibold text-gray-900">
              ✨ {kind === 'tags' ? 'Suggest tags' : 'Generate descriptions'}
            </h2>
            <p className="text-xs text-gray-500">
              {assets.length} asset{assets.length === 1 ? '' : 's'} · using local Ollama. Edit any
              row before applying; uncheck to skip.
            </p>
          </div>
          <button
            onClick={onClose}
            className="rounded text-gray-400 hover:text-gray-700"
            aria-label="Close"
          >
            ✕
          </button>
        </header>

        <div className="flex-1 overflow-auto px-5 py-3">
          <ul className="space-y-3">
            {rows.map((r, i) => (
              <li key={r.assetId} className="rounded border border-gray-200 p-3">
                <header className="mb-2 flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={r.accepted}
                    disabled={r.status !== 'ok'}
                    onChange={(e) =>
                      setRows((prev) =>
                        prev.map((x, j) => (j === i ? { ...x, accepted: e.target.checked } : x)),
                      )
                    }
                  />
                  <span className="font-mono text-xs">{r.assetId}</span>
                  {r.status === 'pending' && <span className="text-xs text-gray-400">queued…</span>}
                  {r.status === 'loading' && (
                    <span className="text-xs text-blue-600">running model…</span>
                  )}
                  {r.status === 'error' && (
                    <span className="text-xs text-red-600">{r.error}</span>
                  )}
                </header>
                <div className="grid grid-cols-2 gap-3 text-xs">
                  <div>
                    <div className="text-gray-500">Current</div>
                    <pre className="whitespace-pre-wrap break-words rounded bg-gray-50 p-2 text-gray-700">
                      {r.currentText || <span className="text-gray-400">(empty)</span>}
                    </pre>
                  </div>
                  <div>
                    <div className="text-gray-500">Suggested (editable)</div>
                    <textarea
                      value={r.draftText}
                      onChange={(e) =>
                        setRows((prev) =>
                          prev.map((x, j) => (j === i ? { ...x, draftText: e.target.value } : x)),
                        )
                      }
                      disabled={r.status !== 'ok'}
                      rows={kind === 'description' ? 4 : 2}
                      className="w-full rounded border border-gray-300 p-2 text-xs disabled:bg-gray-50"
                    />
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>

        <footer className="flex items-center justify-between border-t border-gray-200 px-5 py-3">
          <div className="text-xs text-gray-600">
            {running ? 'running…' : 'done'} · {acceptedCount} accepted
            {errorCount > 0 && <span className="text-red-600"> · {errorCount} failed</span>}
            {appliedCount != null && (
              <span className="ml-2 text-green-700">applied to {appliedCount}.</span>
            )}
          </div>
          <div className="flex items-center gap-2">
            {apply.error && (
              <span className="text-xs text-red-600">{(apply.error as Error).message}</span>
            )}
            <button
              onClick={onClose}
              className="rounded border border-gray-300 px-3 py-1 text-xs hover:bg-gray-50"
            >
              {appliedCount != null ? 'Close' : 'Cancel'}
            </button>
            <button
              onClick={() => apply.mutate()}
              disabled={apply.isPending || acceptedCount === 0 || appliedCount != null}
              className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {apply.isPending ? 'Applying…' : `Apply to ${acceptedCount}`}
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}
