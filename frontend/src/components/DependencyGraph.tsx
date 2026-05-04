import { useMemo } from 'react';
import {
  Background,
  Controls,
  ReactFlow,
  type Edge,
  type Node,
} from '@xyflow/react';
import type { AssetGraph } from '../api';

export function DependencyGraph({ graph }: { graph: AssetGraph }) {
  const nodes = useMemo<Node[]>(() => {
    // Simple layered layout: producers (no deps in graph) on the left,
    // consumers (with deps) on the right; cluster the rest in the middle.
    const producerSet = new Set(graph.edges.map((e) => e.producerId));
    const consumerSet = new Set(graph.edges.map((e) => e.consumerId));
    const cols: Record<string, Node[]> = { left: [], middle: [], right: [] };
    for (const a of graph.nodes) {
      const isProducer = producerSet.has(a.id);
      const isConsumer = consumerSet.has(a.id);
      const col = isProducer && !isConsumer ? 'left' : !isProducer && isConsumer ? 'right' : 'middle';
      cols[col].push({
        id: a.id,
        position: { x: 0, y: 0 },
        data: { label: `${a.name}\n(${a.type})` },
        style: {
          padding: 10,
          border: a.id === graph.rootId ? '2px solid #2563eb' : '1px solid #d1d5db',
          background: a.id === graph.rootId ? '#dbeafe' : '#ffffff',
          fontSize: 12,
          whiteSpace: 'pre-line' as const,
          minWidth: 160,
        },
      });
    }
    const colWidth = 220;
    const rowHeight = 80;
    const result: Node[] = [];
    Object.entries(cols).forEach(([key, group], colIdx) => {
      group.forEach((n, rowIdx) => {
        result.push({
          ...n,
          position: { x: colIdx * colWidth, y: rowIdx * rowHeight },
        });
      });
      void key;
    });
    return result;
  }, [graph]);

  const edges = useMemo<Edge[]>(
    () =>
      graph.edges.map((d) => ({
        id: `${d.consumerId}->${d.producerId}-${d.kind}`,
        source: d.consumerId,
        target: d.producerId,
        label: d.kind,
        animated: d.kind === 'runtime',
        style: { stroke: d.kind === 'runtime' ? '#0284c7' : '#6b7280' },
      })),
    [graph]
  );

  return (
    <div className="h-[460px] rounded border border-gray-200 bg-white">
      <ReactFlow nodes={nodes} edges={edges} fitView nodesDraggable={false}>
        <Background />
        <Controls />
      </ReactFlow>
    </div>
  );
}
