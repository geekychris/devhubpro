import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { api } from '../api';

const PROBLEMS = [
  { value: 'general', label: 'General help' },
  { value: 'manifest.missing', label: 'No devportal.yaml — onboard the repo' },
  { value: 'workspace.empty', label: 'Workspace not cloned yet' },
  { value: 'build.failed', label: 'Build failed — diagnose and fix' },
  { value: 'manifest.invalid', label: 'devportal.yaml fails schema validation' },
  { value: 'ports.allocate-failed', label: 'Port allocation failed' },
];

export function AskClaudeButton({
  assetId,
  defaultProblem = 'general',
  defaultDetails,
  variant = 'header',
}: {
  assetId: string;
  defaultProblem?: string;
  defaultDetails?: string;
  variant?: 'header' | 'inline';
}) {
  const [open, setOpen] = useState(false);
  const [problem, setProblem] = useState(defaultProblem);
  const [details, setDetails] = useState(defaultDetails ?? '');
  const [copied, setCopied] = useState(false);

  const promptM = useMutation({
    mutationFn: () => api.helpPrompt(assetId, problem, details || undefined),
  });

  const cls =
    variant === 'header'
      ? 'rounded border border-purple-300 bg-purple-50 px-3 py-1.5 text-sm text-purple-800 hover:bg-purple-100'
      : 'rounded bg-purple-600 px-2 py-1 text-xs text-white hover:bg-purple-700';

  return (
    <>
      <button onClick={() => { setOpen(true); setCopied(false); promptM.mutate(); }} className={cls}>
        Ask Claude
      </button>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="max-w-3xl w-full max-h-[85vh] overflow-hidden flex flex-col rounded-lg bg-white shadow-xl">
            <header className="flex items-center justify-between border-b border-gray-200 px-4 py-3">
              <h2 className="text-sm font-medium text-gray-900">
                Ask Claude — <span className="font-mono">{assetId}</span>
              </h2>
              <button
                onClick={() => setOpen(false)}
                className="text-sm text-gray-500 hover:text-gray-700"
                aria-label="close"
              >
                ✕
              </button>
            </header>
            <div className="space-y-3 overflow-auto px-4 py-3 text-sm">
              <div>
                <label className="block text-xs font-medium text-gray-700">
                  What's the problem?
                </label>
                <select
                  value={problem}
                  onChange={(e) => {
                    setProblem(e.target.value);
                    setCopied(false);
                    promptM.mutate();
                  }}
                  className="mt-1 w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
                >
                  {PROBLEMS.map((p) => (
                    <option key={p.value} value={p.value}>
                      {p.label}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-700">
                  Extra details (optional)
                </label>
                <input
                  value={details}
                  onChange={(e) => setDetails(e.target.value)}
                  onBlur={() => promptM.mutate()}
                  placeholder="error message, command, etc."
                  className="mt-1 w-full rounded border border-gray-300 px-3 py-1.5 text-sm"
                />
              </div>

              {promptM.isPending && <p className="text-xs text-gray-500">Generating prompt…</p>}
              {promptM.error && (
                <p className="text-xs text-red-600">{(promptM.error as Error).message}</p>
              )}

              {promptM.data && (
                <pre className="max-h-[50vh] overflow-auto whitespace-pre-wrap rounded bg-gray-900 px-3 py-3 font-mono text-xs text-gray-100">
                  {promptM.data.text}
                </pre>
              )}
            </div>

            <footer className="flex items-center justify-end gap-3 border-t border-gray-200 px-4 py-3">
              <p className="mr-auto text-xs text-gray-500">
                Paste this into a Claude Code session that has the <code>devportal</code> MCP server connected.
              </p>
              <button
                onClick={() => {
                  if (promptM.data) {
                    navigator.clipboard.writeText(promptM.data.text);
                    setCopied(true);
                  }
                }}
                disabled={!promptM.data}
                className="rounded bg-purple-600 px-3 py-1.5 text-sm text-white hover:bg-purple-700 disabled:opacity-50"
              >
                {copied ? 'Copied!' : 'Copy to clipboard'}
              </button>
              <button
                onClick={() => setOpen(false)}
                className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-100"
              >
                Close
              </button>
            </footer>
          </div>
        </div>
      )}
    </>
  );
}
