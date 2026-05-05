import { useEffect, useRef } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';

export function PodTerminal({
  assetId,
  podName,
  container,
  shell = '/bin/sh',
}: {
  assetId: string;
  podName: string;
  container?: string;
  shell?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!ref.current) return;
    const term = new Terminal({ convertEol: true, fontSize: 13, theme: { background: '#0f172a' } });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(ref.current);
    fit.fit();

    // Build websocket URL: backend at :8081, vite proxy doesn't handle ws by default,
    // so connect direct to the backend.
    const params = new URLSearchParams();
    if (container) params.set('container', container);
    if (shell) params.set('shell', shell);
    const url = `ws://${window.location.hostname}:8081/ws/assets/${assetId}/k8s/pods/${podName}/exec?${params}`;
    const ws = new WebSocket(url);
    wsRef.current = ws;

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
  }, [assetId, podName, container, shell]);

  return <div ref={ref} className="h-[60vh] w-full rounded bg-slate-900 p-2" />;
}
