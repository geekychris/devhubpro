import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';

export function SettingsPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold text-gray-900">Settings</h1>
      <GitHubSection />
    </div>
  );
}

function GitHubSection() {
  const queryClient = useQueryClient();
  const info = useQuery({
    queryKey: ['github-token'],
    queryFn: () => api.getGitHubToken(),
  });

  const [token, setToken] = useState('');
  const [reveal, setReveal] = useState(false);

  const save = useMutation({
    mutationFn: () => api.setGitHubToken(token.trim()),
    onSuccess: () => {
      setToken('');
      queryClient.invalidateQueries({ queryKey: ['github-token'] });
    },
  });
  const clear = useMutation({
    mutationFn: () => api.clearGitHubToken(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['github-token'] }),
  });
  const testMut = useMutation({
    mutationFn: () => api.testGitHubToken(),
  });

  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <h2 className="text-sm font-medium text-gray-900">GitHub personal access token</h2>
      <p className="mt-1 text-xs text-gray-500">
        Used by the portal to read repo metadata and clone private repos. Stored at{' '}
        <code>~/.devportal/secrets/github-token</code> (mode 0600). The full token is never returned by the API.
      </p>

      <div className="mt-3 grid grid-cols-2 gap-x-3 gap-y-1 text-sm">
        <div className="text-xs font-medium text-gray-500">Status</div>
        <div>
          {info.data?.hasToken ? (
            <span className="rounded bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">
              configured
            </span>
          ) : (
            <span className="rounded bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-700">
              not set
            </span>
          )}
        </div>
        <div className="text-xs font-medium text-gray-500">Source</div>
        <div className="font-mono text-xs">
          {info.data?.source === 'FILE'
            ? `file: ~/.devportal/secrets/github-token`
            : info.data?.source === 'ENV'
            ? 'env: GITHUB_TOKEN'
            : 'none'}
        </div>
        {info.data?.preview && (
          <>
            <div className="text-xs font-medium text-gray-500">Preview</div>
            <div className="font-mono text-xs">{info.data.preview}</div>
          </>
        )}
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (token.trim()) save.mutate();
        }}
        className="mt-4 space-y-2"
      >
        <label className="block text-xs font-medium text-gray-700">
          New token (classic PAT, scope <code>repo</code>)
        </label>
        <div className="flex gap-2">
          <input
            type={reveal ? 'text' : 'password'}
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="ghp_…"
            className="flex-1 rounded border border-gray-300 px-3 py-1.5 font-mono text-xs"
            autoComplete="off"
          />
          <button
            type="button"
            onClick={() => setReveal((v) => !v)}
            className="rounded border border-gray-300 px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100"
          >
            {reveal ? 'Hide' : 'Reveal'}
          </button>
          <button
            type="submit"
            disabled={!token.trim() || save.isPending}
            className="rounded bg-blue-600 px-3 py-1.5 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {save.isPending ? 'Saving…' : 'Save'}
          </button>
        </div>
        {save.error && (
          <p className="text-xs text-red-600">{(save.error as Error).message}</p>
        )}
      </form>

      <div className="mt-3 flex items-center gap-2">
        <button
          onClick={() => testMut.mutate()}
          disabled={testMut.isPending}
          className="rounded border border-gray-300 px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100"
        >
          {testMut.isPending ? 'Testing…' : 'Test connection'}
        </button>
        <button
          onClick={() => {
            if (confirm('Clear the file-stored GitHub token?')) clear.mutate();
          }}
          disabled={clear.isPending || info.data?.source !== 'FILE'}
          className="rounded border border-red-300 px-3 py-1.5 text-xs text-red-700 hover:bg-red-50 disabled:opacity-50"
        >
          Clear file token
        </button>
      </div>

      {testMut.data && (
        <p className={`mt-2 text-xs ${testMut.data.ok ? 'text-green-700' : 'text-red-600'}`}>
          {testMut.data.message}
        </p>
      )}
      {testMut.error && (
        <p className="mt-2 text-xs text-red-600">{(testMut.error as Error).message}</p>
      )}

      <p className="mt-4 text-xs text-gray-500">
        Generate a token at{' '}
        <a
          href="https://github.com/settings/tokens"
          target="_blank"
          rel="noreferrer"
          className="text-blue-700 hover:underline"
        >
          github.com/settings/tokens
        </a>{' '}
        — Classic, scope <code>repo</code>.
      </p>
    </section>
  );
}
