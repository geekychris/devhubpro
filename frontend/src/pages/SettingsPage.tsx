import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';

export function SettingsPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold text-gray-900">Settings</h1>
      <OllamaSection />
      <GitHubSection />
      <TelegramSection />
    </div>
  );
}

function OllamaSection() {
  const qc = useQueryClient();
  const cfg = useQuery({ queryKey: ['ollama-settings'], queryFn: () => api.getOllamaSettings() });
  const [endpoint, setEndpoint] = useState('');
  const [model, setModel] = useState('');
  const [edited, setEdited] = useState(false);

  // Hydrate inputs once the saved config loads (or after a save).
  if (cfg.data && !edited) {
    if (endpoint !== cfg.data.endpoint) setEndpoint(cfg.data.endpoint);
    if (model    !== cfg.data.model)    setModel(cfg.data.model);
  }

  const save = useMutation({
    mutationFn: () => api.setOllamaSettings({ endpoint: endpoint.trim(), model: model.trim() }),
    onSuccess: () => {
      setEdited(false);
      qc.invalidateQueries({ queryKey: ['ollama-settings'] });
      qc.invalidateQueries({ queryKey: ['ollama-models'] });
    },
  });
  const test = useMutation({ mutationFn: () => api.testOllama() });
  const models = useQuery({
    queryKey: ['ollama-models'],
    queryFn: () => api.listOllamaModels(),
    refetchOnWindowFocus: false,
  });

  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <header className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-gray-900">Ollama (local LLM)</h2>
        {cfg.data && (
          <span className="text-[11px] text-gray-500">
            source: <span className="font-mono">{cfg.data.source.toLowerCase()}</span>
          </span>
        )}
      </header>
      <p className="mb-3 text-xs text-gray-500">
        Used to suggest tags and generate descriptions for assets from the docs in their workspace.
        Defaults to <code>http://localhost:11434</code>; override here if Ollama lives elsewhere.
      </p>

      <div className="flex flex-wrap items-end gap-2 text-sm">
        <label className="flex flex-col text-xs text-gray-600">
          Endpoint
          <input
            value={endpoint}
            onChange={(e) => { setEndpoint(e.target.value); setEdited(true); }}
            placeholder="http://localhost:11434"
            className="mt-0.5 w-72 rounded border border-gray-300 px-2 py-1 font-mono text-sm"
          />
        </label>
        <label className="flex flex-col text-xs text-gray-600">
          Model
          {models.data?.ok && models.data.models.length > 0 ? (
            <select
              value={model}
              onChange={(e) => { setModel(e.target.value); setEdited(true); }}
              className="mt-0.5 w-56 rounded border border-gray-300 px-2 py-1 font-mono text-sm"
            >
              {!models.data.models.includes(model) && model && (
                <option value={model}>{model}</option>
              )}
              {models.data.models.map((m) => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>
          ) : (
            <input
              value={model}
              onChange={(e) => { setModel(e.target.value); setEdited(true); }}
              placeholder="llama3.2"
              className="mt-0.5 w-56 rounded border border-gray-300 px-2 py-1 font-mono text-sm"
            />
          )}
        </label>
        <button
          onClick={() => save.mutate()}
          disabled={save.isPending || !endpoint.trim() || !model.trim()}
          className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {save.isPending ? 'Saving…' : 'Save'}
        </button>
        <button
          onClick={() => test.mutate()}
          disabled={test.isPending}
          className="rounded border border-gray-300 px-3 py-1 text-xs hover:bg-gray-50 disabled:opacity-50"
        >
          {test.isPending ? 'Testing…' : 'Test connection'}
        </button>
        <button
          onClick={() => models.refetch()}
          className="rounded border border-gray-300 px-3 py-1 text-xs hover:bg-gray-50"
          title="Reload the list of locally-installed models"
        >
          Refresh models
        </button>
      </div>

      {save.error && <p className="mt-2 text-xs text-red-600">{(save.error as Error).message}</p>}
      {test.data && (
        test.data.ok ? (
          <p className="mt-2 text-xs text-green-700">
            ✓ Connected to <span className="font-mono">{test.data.endpoint}</span>
            {test.data.version && <> (v{test.data.version})</>}
          </p>
        ) : (
          <p className="mt-2 text-xs text-red-600">
            ✗ {test.data.error ?? 'Connection failed'}
          </p>
        )
      )}
      {models.data && !models.data.ok && (
        <p className="mt-2 text-xs text-amber-700">
          Model list unavailable: {models.data.error}
        </p>
      )}
      {models.data?.ok && (
        <p className="mt-2 text-[11px] text-gray-500">
          {models.data.models.length} model{models.data.models.length === 1 ? '' : 's'} installed locally.
        </p>
      )}
    </section>
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

function TelegramSection() {
  const queryClient = useQueryClient();
  const settings = useQuery({
    queryKey: ['telegram-settings'],
    queryFn: () => api.getTelegramSettings(),
  });
  const allowlist = useQuery({
    queryKey: ['telegram-allowlist'],
    queryFn: () => api.getTelegramAllowlist(),
    enabled: settings.data?.hasToken === true,
  });

  const [token, setToken] = useState('');
  const [reveal, setReveal] = useState(false);
  const [chatId, setChatId] = useState('');

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['telegram-settings'] });
    queryClient.invalidateQueries({ queryKey: ['telegram-allowlist'] });
  };

  const saveToken = useMutation({
    mutationFn: () => api.setTelegramToken(token.trim()),
    onSuccess: () => { setToken(''); invalidate(); },
  });
  const clearToken = useMutation({
    mutationFn: () => api.clearTelegramToken(),
    onSuccess: invalidate,
  });
  const testConn = useMutation({ mutationFn: () => api.testTelegram() });
  const addId = useMutation({
    mutationFn: () => api.addTelegramAllowlist(Number(chatId.trim())),
    onSuccess: () => { setChatId(''); invalidate(); },
  });
  const removeId = useMutation({
    mutationFn: (id: number) => api.removeTelegramAllowlist(id),
    onSuccess: invalidate,
  });
  const restart = useMutation({
    mutationFn: () => api.restartTelegram(),
    onSuccess: invalidate,
  });

  const s = settings.data;

  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-medium text-gray-900">Telegram bot</h2>
        {s?.running ? (
          <span className="rounded bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">
            running
          </span>
        ) : s?.hasToken ? (
          <span className="rounded bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-900">
            stopped — click Start
          </span>
        ) : (
          <span className="rounded bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-700">
            no token
          </span>
        )}
      </div>
      <p className="mt-1 text-xs text-gray-500">
        Bridge to the same CLI command tree the SSH server exposes — accessed by chat. Token stored
        at <code>{s?.tokenFile ?? '~/.devportal/secrets/telegram-bot-token'}</code> (mode 0600).
        Allowlist at <code>{s?.allowlistFile ?? '~/.devportal/secrets/telegram-allowlist'}</code>.
      </p>

      <div className="mt-3 grid grid-cols-2 gap-x-3 gap-y-1 text-sm">
        <div className="text-xs font-medium text-gray-500">Auto-start at boot</div>
        <div className="font-mono text-xs">
          {s?.enabled ? 'on' : 'off'}{' '}
          <span className="text-gray-500">
            (devportal.telegram.enabled in application.yml — set true to auto-start the bot
            whenever the backend boots)
          </span>
        </div>
        <div className="text-xs font-medium text-gray-500">Token</div>
        <div className="font-mono text-xs">
          {s?.hasToken ? (
            <>
              {s.preview ?? '(set)'}
              <span className="ml-2 text-gray-500">
                (length {s.tokenLength}
                {s.tokenWellFormed ? '' : <span className="text-red-600"> — malformed!</span>})
              </span>
            </>
          ) : (
            <span className="text-gray-400">not set</span>
          )}
        </div>
        <div className="text-xs font-medium text-gray-500">Allowlist</div>
        <div className="font-mono text-xs">
          {s?.allowlistCount ?? 0} chat id{(s?.allowlistCount ?? 0) === 1 ? '' : 's'}
          {s?.allowGroups ? ' (groups allowed)' : ' (DM only)'}
        </div>
        <div className="text-xs font-medium text-gray-500">Long-message mode</div>
        <div className="font-mono text-xs">{s?.longMessageMode ?? 'split'}</div>
      </div>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (token.trim()) saveToken.mutate();
        }}
        className="mt-4 space-y-2"
      >
        <label className="block text-xs font-medium text-gray-700">
          Bot token (from <code>@BotFather</code>)
        </label>
        <div className="flex gap-2">
          <input
            type={reveal ? 'text' : 'password'}
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="1234567890:AAH-…"
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
            disabled={!token.trim() || saveToken.isPending}
            className="rounded bg-blue-600 px-3 py-1.5 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saveToken.isPending ? 'Saving…' : 'Save'}
          </button>
        </div>
        {saveToken.error && (
          <p className="text-xs text-red-600">{(saveToken.error as Error).message}</p>
        )}
      </form>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button
          onClick={() => testConn.mutate()}
          disabled={!s?.hasToken || testConn.isPending}
          className="rounded border border-gray-300 px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100 disabled:opacity-50"
        >
          {testConn.isPending ? 'Testing…' : 'Test connection'}
        </button>
        <button
          onClick={() => restart.mutate()}
          disabled={!s?.hasToken || restart.isPending}
          className="rounded border border-gray-300 px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-100 disabled:opacity-50"
        >
          {s?.running
            ? restart.isPending ? 'Restarting…' : 'Restart bot'
            : restart.isPending ? 'Starting…' : 'Start bot'}
        </button>
        <button
          onClick={() => {
            if (confirm('Clear the stored Telegram token? This stops the bot.')) clearToken.mutate();
          }}
          disabled={!s?.hasToken || clearToken.isPending}
          className="rounded border border-red-300 px-3 py-1.5 text-xs text-red-700 hover:bg-red-50 disabled:opacity-50"
        >
          Clear token
        </button>
      </div>

      {testConn.data && (
        <p className={`mt-2 text-xs ${testConn.data.ok ? 'text-green-700' : 'text-red-600'}`}>
          {testConn.data.message}
        </p>
      )}

      {/* allowlist editor */}
      <div className="mt-5">
        <h3 className="text-xs font-medium text-gray-700">Authorized chat ids</h3>
        <p className="mt-1 text-xs text-gray-500">
          Only these chats can run commands. Empty list = bot rejects every message (and reveals the
          sender's chat id once so they can be added). Group chat ids are negative — Telegram uses
          <code> -100…</code> for supergroups.
        </p>

        <div className="mt-2 space-y-1">
          {(allowlist.data?.chatIds ?? []).map((id) => (
            <div
              key={id}
              className="flex items-center justify-between rounded border border-gray-200 px-3 py-1.5 text-sm"
            >
              <span className="font-mono text-xs">{id}</span>
              <button
                onClick={() => removeId.mutate(id)}
                disabled={removeId.isPending}
                className="rounded border border-red-200 px-2 py-0.5 text-xs text-red-700 hover:bg-red-50 disabled:opacity-50"
              >
                Remove
              </button>
            </div>
          ))}
          {allowlist.data && allowlist.data.chatIds.length === 0 && (
            <p className="text-xs italic text-gray-500">(empty — no chat is authorized)</p>
          )}
        </div>

        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (chatId.trim() && !Number.isNaN(Number(chatId))) addId.mutate();
          }}
          className="mt-2 flex gap-2"
        >
          <input
            type="text"
            value={chatId}
            onChange={(e) => setChatId(e.target.value)}
            placeholder="chat id (numeric)"
            className="flex-1 rounded border border-gray-300 px-3 py-1.5 font-mono text-xs"
            autoComplete="off"
          />
          <button
            type="submit"
            disabled={!chatId.trim() || Number.isNaN(Number(chatId)) || addId.isPending}
            className="rounded bg-blue-600 px-3 py-1.5 text-xs text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {addId.isPending ? 'Adding…' : 'Add'}
          </button>
        </form>
        {addId.error && (
          <p className="mt-1 text-xs text-red-600">{(addId.error as Error).message}</p>
        )}
      </div>

      <p className="mt-4 text-xs text-gray-500">
        Don't know your chat id?  Open the bot in Telegram and send any message — the bot replies
        once with your chat id, which you can paste above. Or run{' '}
        <code>scripts/devportal-telegram-setup.sh</code> for a guided walkthrough.
        Reference:{' '}
        <a
          href="https://t.me/BotFather"
          target="_blank"
          rel="noreferrer"
          className="text-blue-700 hover:underline"
        >
          @BotFather
        </a>
        {' · '}
        <a
          href="https://core.telegram.org/bots/api"
          target="_blank"
          rel="noreferrer"
          className="text-blue-700 hover:underline"
        >
          Bot API docs
        </a>
        .
      </p>
    </section>
  );
}
