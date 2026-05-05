import { useEffect, useRef } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';

/**
 * xterm.js terminal connected to a host shell at the asset's workspace dir
 * (~/.devportal/workspace/{assetId}). Whatever git branch is currently checked out
 * is what the shell sees.
 */
export function WorkspaceTerminal({
  assetId,
  shell,
}: {
  assetId: string;
  shell?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!ref.current) return;
    const term = new Terminal({
      convertEol: true,
      fontSize: 13,
      theme: { background: '#0f172a' },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(ref.current);
    fit.fit();

    const params = new URLSearchParams();
    if (shell) params.set('shell', shell);
    const url = `ws://${window.location.hostname}:8081/ws/assets/${assetId}/workspace/exec?${params}`;
    const ws = new WebSocket(url);

    ws.onmessage = (e) => term.write(typeof e.data === 'string' ? e.data : '');
    ws.onerror = () => term.write('\r\n[ws error]\r\n');
    ws.onclose = () => term.write('\r\n[disconnected]\r\n');

    term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(data);
    });

    const handleResize = () => fit.fit();
    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      ws.close();
      term.dispose();
    };
  }, [assetId, shell]);

  return <div ref={ref} className="h-[60vh] w-full rounded bg-slate-900 p-2" />;
}

export function WorkspaceTerminalModal({
  assetId,
  open,
  onClose,
}: {
  assetId: string;
  open: boolean;
  onClose: () => void;
}) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="flex w-full max-w-5xl flex-col rounded-lg bg-white shadow-xl">
        <header className="flex items-center justify-between border-b border-gray-200 px-4 py-2">
          <h2 className="text-sm font-medium text-gray-900">
            Workspace shell — <span className="font-mono">{assetId}</span>
          </h2>
          <button onClick={onClose} className="text-sm text-gray-500 hover:text-gray-700">
            close ✕
          </button>
        </header>
        <div className="p-3">
          <WorkspaceTerminal assetId={assetId} />
        </div>
        <footer className="border-t border-gray-200 px-4 py-2 text-xs text-gray-500">
          Shell is rooted at <code>~/.devportal/workspace/{assetId}</code>. Type{' '}
          <code>git status</code> / <code>git log --oneline -5</code> / <code>ls k8s/</code> etc.
          Closing this modal terminates the shell.
        </footer>
      </div>
    </div>
  );
}
