import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Asset } from '../api';
import { StarRating } from './StarRating';

/**
 * Favorite toggle + 5-star rating, both wired to PATCH /api/assets/{id}.
 * Optimistic UI: cached query for `['asset', id]` is updated before the request resolves.
 */
export function FavoriteRatingBar({ asset }: { asset: Asset }) {
  const qc = useQueryClient();
  const patch = useMutation({
    mutationFn: (body: Partial<{ favorite: boolean; rating: number | null }>) =>
      api.updateAsset(asset.id, body),
    onMutate: async (body) => {
      await qc.cancelQueries({ queryKey: ['asset', asset.id] });
      const prev = qc.getQueryData<Asset>(['asset', asset.id]);
      if (prev) {
        qc.setQueryData<Asset>(['asset', asset.id], { ...prev, ...body } as Asset);
      }
      return { prev };
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(['asset', asset.id], ctx.prev);
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['asset', asset.id] });
      qc.invalidateQueries({ queryKey: ['assets'] });
      qc.invalidateQueries({ queryKey: ['favorites'] });
    },
  });

  return (
    <div className="flex flex-wrap items-center gap-3 rounded border border-gray-200 bg-white px-3 py-2 text-sm">
      <button
        type="button"
        onClick={() => patch.mutate({ favorite: !asset.favorite })}
        className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 hover:bg-gray-50"
        title={asset.favorite ? 'Remove from favorites' : 'Add to favorites'}
      >
        <span style={{ color: asset.favorite ? '#dc2626' : '#9ca3af' }}>♥</span>
        <span className={asset.favorite ? 'font-medium text-gray-900' : 'text-gray-500'}>
          {asset.favorite ? 'Favorite' : 'Add to favorites'}
        </span>
      </button>
      <span className="h-4 w-px bg-gray-200" />
      <span className="text-xs text-gray-500">Rating</span>
      <StarRating
        value={asset.rating}
        onChange={(rating) => patch.mutate({ rating })}
      />
      {patch.error && (
        <span className="text-xs text-red-600">{(patch.error as Error).message}</span>
      )}
    </div>
  );
}
