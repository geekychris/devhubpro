import { useEffect, useMemo, useState } from 'react';
import dagre from 'dagre';
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  type Edge,
  type Node,
} from '@xyflow/react';
import type { AssetGraph } from '../api';

const NODE_W = 200;
const NODE_H = 56;

type Direction = 'LR' | 'TB';

export function DependencyGraph({ graph }: { graph: AssetGraph }) {
  const [direction, setDirection] = useState<Direction>('LR');
  const [fullscreen, setFullscreen] = useState(false);
  const { nodes, edges } = useMemo(() => layout(graph, direction), [graph, direction]);

  const toolbar = (
    <div className="flex items-center justify-between rounded border border-gray-200 bg-white p-2 text-sm">
      <div className="flex items-center gap-2">
        <span className="text-xs text-gray-500">Layout:</span>
        {(['LR', 'TB'] as const).map((d) => (
          <button
            key={d}
            onClick={() => setDirection(d)}
            className={`rounded px-2 py-0.5 text-xs ${
              direction === d
                ? 'bg-blue-600 text-white'
                : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            }`}
            title={d === 'LR' ? 'Left → Right' : 'Top → Bottom'}
          >
            {d === 'LR' ? '→ producers right' : '↓ producers down'}
          </button>
        ))}
      </div>
      <div className="flex items-center gap-3">
        <span className="text-xs text-gray-500">
          {graph.nodes.length} nodes · {graph.edges.length} edges
        </span>
        <button
          onClick={() => setFullscreen((v) => !v)}
          className="rounded border border-gray-300 px-2 py-0.5 text-xs text-gray-700 hover:bg-gray-100"
          title={fullscreen ? 'Exit fullscreen (Esc)' : 'Open in fullscreen viewer'}
        >
          {fullscreen ? 'Exit fullscreen' : '⛶ Fullscreen'}
        </button>
      </div>
    </div>
  );

  const flow = (
    <ReactFlow
      key={`${direction}-${fullscreen}`}    // force re-fit when these change
      nodes={nodes}
      edges={edges}
      fitView
      fitViewOptions={{ padding: 0.15 }}
      nodesDraggable={false}
      minZoom={0.1}
      maxZoom={2}
    >
      <Background gap={16} />
      <Controls position="top-right" />
      <MiniMap
        position="bottom-right"
        zoomable
        pannable
        nodeStrokeColor="#94a3b8"
        nodeColor={(n) => (n.style?.background as string) ?? '#fff'}
      />
    </ReactFlow>
  );

  if (fullscreen) {
    return (
      <FullscreenGraph onClose={() => setFullscreen(false)} toolbar={toolbar}>
        {flow}
      </FullscreenGraph>
    );
  }

  return (
    <div className="space-y-2">
      {toolbar}
      <div className="h-[75vh] rounded border border-gray-200 bg-white">{flow}</div>
    </div>
  );
}

function FullscreenGraph({
  onClose,
  toolbar,
  children,
}: {
  onClose: () => void;
  toolbar: React.ReactNode;
  children: React.ReactNode;
}) {
  // Esc to exit
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-white">
      <div className="border-b border-gray-200 px-3 py-2">{toolbar}</div>
      <div className="flex-1">{children}</div>
    </div>
  );
}

function layout(graph: AssetGraph, direction: Direction): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph();
  g.setGraph({
    rankdir: direction,
    ranksep: direction === 'LR' ? 80 : 50,
    nodesep: 30,
    marginx: 16,
    marginy: 16,
  });
  g.setDefaultEdgeLabel(() => ({}));

  for (const a of graph.nodes) {
    g.setNode(a.id, { width: NODE_W, height: NODE_H });
  }
  for (const e of graph.edges) {
    // Skip edges to/from nodes that aren't in the graph (defensive).
    if (!g.hasNode(e.consumerId) || !g.hasNode(e.producerId)) continue;
    g.setEdge(e.consumerId, e.producerId);
  }
  dagre.layout(g);

  const nodes: Node[] = graph.nodes.map((a) => {
    const pos = g.node(a.id);
    const isRoot = a.id === graph.rootId;
    return {
      id: a.id,
      position: { x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2 },
      data: { label: nodeLabel(a) },
      style: {
        width: NODE_W,
        height: NODE_H,
        padding: 6,
        border: isRoot ? '2px solid #2563eb' : '1px solid #d1d5db',
        background: isRoot ? '#dbeafe' : '#ffffff',
        fontSize: 12,
        whiteSpace: 'pre-line' as const,
        textAlign: 'left' as const,
      },
      sourcePosition: direction === 'LR' ? ('right' as any) : ('bottom' as any),
      targetPosition: direction === 'LR' ? ('left' as any) : ('top' as any),
    };
  });

  // Dedupe edges (the backend graph endpoint can include the same edge twice
  // when consumers are also walked from the root).
  const seen = new Set<string>();
  const edges: Edge[] = [];
  for (const e of graph.edges) {
    const key = `${e.consumerId}->${e.producerId}-${e.kind}`;
    if (seen.has(key)) continue;
    seen.add(key);
    edges.push({
      id: key,
      source: e.consumerId,
      target: e.producerId,
      label: e.kind === 'runtime' ? 'runtime' : undefined,
      animated: e.kind === 'runtime',
      style: { stroke: e.kind === 'runtime' ? '#0284c7' : '#6b7280' },
    });
  }

  return { nodes, edges };
}

function nodeLabel(asset: { id: string; name: string; type: string }): string {
  // Trim long ids so they don't overflow the box.
  const short = asset.id.length > 32 ? asset.id.slice(0, 30) + '…' : asset.id;
  return `${short}\n${asset.type}`;
}
