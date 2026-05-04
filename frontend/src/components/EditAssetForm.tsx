import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Asset, type UpdateAssetBody } from '../api';

export function EditAssetForm({ asset, onClose }: { asset: Asset; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState<UpdateAssetBody>({
    name: asset.name,
    description: asset.description ?? '',
    owner: asset.owner ?? '',
    type: asset.type,
    language: asset.language ?? '',
    repoUrl: asset.repoUrl,
    repoDefaultBranch: asset.repoDefaultBranch,
    tags: asset.tags,
    lifecycle: asset.lifecycle,
  });
  const [tagsText, setTagsText] = useState(asset.tags.join(', '));

  const save = useMutation({
    mutationFn: () =>
      api.updateAsset(asset.id, {
        ...form,
        description: form.description === '' ? null : form.description,
        owner: form.owner === '' ? null : form.owner,
        language: form.language === '' ? null : form.language,
        tags: tagsText.split(',').map((t) => t.trim()).filter(Boolean),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['asset', asset.id] });
      queryClient.invalidateQueries({ queryKey: ['assets'] });
      onClose();
    },
  });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
      className="space-y-3 rounded border border-blue-300 bg-blue-50 p-4 text-sm"
    >
      <h3 className="text-sm font-medium text-gray-900">Edit asset</h3>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Name">
          <input
            value={form.name ?? ''}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            className="w-full rounded border border-gray-300 px-3 py-1.5"
          />
        </Field>
        <Field label="Owner">
          <input
            value={form.owner ?? ''}
            onChange={(e) => setForm({ ...form, owner: e.target.value })}
            className="w-full rounded border border-gray-300 px-3 py-1.5"
          />
        </Field>
        <Field label="Type">
          <select
            value={form.type}
            onChange={(e) => setForm({ ...form, type: e.target.value })}
            className="w-full rounded border border-gray-300 px-3 py-1.5"
          >
            <option value="library">library</option>
            <option value="service">service</option>
            <option value="meta-asset">meta-asset</option>
          </select>
        </Field>
        <Field label="Lifecycle">
          <select
            value={form.lifecycle}
            onChange={(e) => setForm({ ...form, lifecycle: e.target.value })}
            className="w-full rounded border border-gray-300 px-3 py-1.5"
          >
            <option value="experimental">experimental</option>
            <option value="stable">stable</option>
            <option value="deprecated">deprecated</option>
          </select>
        </Field>
        <Field label="Language">
          <input
            value={form.language ?? ''}
            onChange={(e) => setForm({ ...form, language: e.target.value })}
            className="w-full rounded border border-gray-300 px-3 py-1.5"
          />
        </Field>
        <Field label="Default branch">
          <input
            value={form.repoDefaultBranch ?? ''}
            onChange={(e) => setForm({ ...form, repoDefaultBranch: e.target.value })}
            className="w-full rounded border border-gray-300 px-3 py-1.5"
          />
        </Field>
        <Field label="Repo URL" wide>
          <input
            value={form.repoUrl ?? ''}
            onChange={(e) => setForm({ ...form, repoUrl: e.target.value })}
            className="w-full rounded border border-gray-300 px-3 py-1.5 font-mono text-xs"
          />
        </Field>
        <Field label="Description" wide>
          <textarea
            value={form.description ?? ''}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            rows={2}
            className="w-full rounded border border-gray-300 px-3 py-1.5"
          />
        </Field>
        <Field label="Tags (comma-separated)" wide>
          <input
            value={tagsText}
            onChange={(e) => setTagsText(e.target.value)}
            className="w-full rounded border border-gray-300 px-3 py-1.5"
          />
        </Field>
      </div>
      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={save.isPending}
          className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {save.isPending ? 'Saving…' : 'Save'}
        </button>
        <button
          type="button"
          onClick={onClose}
          className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-100"
        >
          Cancel
        </button>
        {save.error && <p className="text-sm text-red-600">{(save.error as Error).message}</p>}
      </div>
    </form>
  );
}

function Field({
  label,
  children,
  wide,
}: {
  label: string;
  children: React.ReactNode;
  wide?: boolean;
}) {
  return (
    <label className={`space-y-1 ${wide ? 'col-span-2' : ''}`}>
      <span className="block text-xs font-medium text-gray-700">{label}</span>
      {children}
    </label>
  );
}
