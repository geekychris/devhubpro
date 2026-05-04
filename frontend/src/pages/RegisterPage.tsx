import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';

export function RegisterPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<'github' | 'manual'>('github');

  const [fullName, setFullName] = useState('');
  const [overrideId, setOverrideId] = useState('');

  const [id, setId] = useState('');
  const [name, setName] = useState('');
  const [type, setType] = useState('library');
  const [language, setLanguage] = useState('');
  const [repoUrl, setRepoUrl] = useState('');

  const ghMutation = useMutation({
    mutationFn: () => api.registerFromGitHub(fullName, overrideId || undefined),
    onSuccess: (asset) => {
      queryClient.invalidateQueries({ queryKey: ['assets'] });
      navigate(`/assets/${asset.id}`);
    },
  });

  const manualMutation = useMutation({
    mutationFn: () =>
      api.createAsset({
        id,
        name,
        type,
        language: language || null,
        repoUrl,
      } as never),
    onSuccess: (asset) => {
      queryClient.invalidateQueries({ queryKey: ['assets'] });
      navigate(`/assets/${asset.id}`);
    },
  });

  return (
    <div className="max-w-xl space-y-4">
      <h1 className="text-2xl font-semibold text-gray-900">Register asset</h1>

      <div className="flex gap-2 text-sm">
        <button
          onClick={() => setMode('github')}
          className={`rounded px-3 py-1.5 ${
            mode === 'github' ? 'bg-blue-600 text-white' : 'bg-gray-200'
          }`}
        >
          From GitHub
        </button>
        <button
          onClick={() => setMode('manual')}
          className={`rounded px-3 py-1.5 ${
            mode === 'manual' ? 'bg-blue-600 text-white' : 'bg-gray-200'
          }`}
        >
          Manual
        </button>
      </div>

      {mode === 'github' && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            ghMutation.mutate();
          }}
          className="space-y-3 rounded border border-gray-200 bg-white p-4"
        >
          <Field label="GitHub full name (e.g. geekychris/hitorro-util)">
            <input
              required
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
            />
          </Field>
          <Field label="Override id (optional)">
            <input
              value={overrideId}
              onChange={(e) => setOverrideId(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
            />
          </Field>
          <button
            type="submit"
            disabled={ghMutation.isPending}
            className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {ghMutation.isPending ? 'Registering…' : 'Register'}
          </button>
          {ghMutation.error && (
            <p className="text-sm text-red-600">{(ghMutation.error as Error).message}</p>
          )}
        </form>
      )}

      {mode === 'manual' && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            manualMutation.mutate();
          }}
          className="space-y-3 rounded border border-gray-200 bg-white p-4"
        >
          <Field label="Id (slug)">
            <input
              required
              value={id}
              onChange={(e) => setId(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
            />
          </Field>
          <Field label="Name">
            <input
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
            />
          </Field>
          <Field label="Type">
            <select
              value={type}
              onChange={(e) => setType(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
            >
              <option value="library">library</option>
              <option value="service">service</option>
              <option value="meta-asset">meta-asset</option>
            </select>
          </Field>
          <Field label="Language">
            <input
              value={language}
              onChange={(e) => setLanguage(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
            />
          </Field>
          <Field label="Repo URL">
            <input
              required
              value={repoUrl}
              onChange={(e) => setRepoUrl(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
            />
          </Field>
          <button
            type="submit"
            disabled={manualMutation.isPending}
            className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {manualMutation.isPending ? 'Creating…' : 'Create'}
          </button>
          {manualMutation.error && (
            <p className="text-sm text-red-600">{(manualMutation.error as Error).message}</p>
          )}
        </form>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block space-y-1">
      <span className="text-xs font-medium text-gray-700">{label}</span>
      {children}
    </label>
  );
}
