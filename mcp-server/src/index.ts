#!/usr/bin/env node
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';
import { portal } from './portal.js';

const server = new Server(
  { name: 'devportal', version: '0.1.0' },
  { capabilities: { tools: {} } }
);

const tools = [
  {
    name: 'list_assets',
    description: 'List registered assets, optionally filtered by query, type, or lifecycle.',
    inputSchema: {
      type: 'object',
      properties: {
        q: { type: 'string', description: 'Substring search over id/name/description' },
        type: { type: 'string', enum: ['library', 'service', 'meta-asset'] },
        lifecycle: { type: 'string', enum: ['experimental', 'stable', 'deprecated'] },
      },
    },
  },
  {
    name: 'get_asset',
    description: 'Fetch one asset by id.',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
    },
  },
  {
    name: 'register_from_github',
    description:
      'Register a GitHub repo as a dev_portal asset. If the repo has a devportal.yaml at HEAD, the manifest seeds the registration.',
    inputSchema: {
      type: 'object',
      properties: {
        fullName: { type: 'string', description: 'GitHub full name, e.g. geekychris/hitorro-util' },
        overrideId: { type: 'string', description: 'Force a specific asset id' },
      },
      required: ['fullName'],
    },
  },
  {
    name: 'add_dependency',
    description: 'Add a dependency edge: consumer asset depends on producer asset.',
    inputSchema: {
      type: 'object',
      properties: {
        consumerId: { type: 'string' },
        producerId: { type: 'string' },
        versionRef: { type: 'string', default: 'main' },
        kind: { type: 'string', enum: ['build', 'runtime'], default: 'build' },
      },
      required: ['consumerId', 'producerId'],
    },
  },
  {
    name: 'get_graph',
    description: 'Return the dependency graph reachable from an asset (producers + immediate consumers).',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
    },
  },
  {
    name: 'kick_build',
    description:
      'Kick off a build for an asset. Use commandName to look up the command from the manifest, or commandLine to pass a raw shell line.',
    inputSchema: {
      type: 'object',
      properties: {
        assetId: { type: 'string' },
        mode: { type: 'string', enum: ['shallow', 'deep'], default: 'shallow' },
        commandName: { type: 'string', default: 'build' },
        commandLine: { type: 'string' },
        ref: { type: 'string' },
      },
      required: ['assetId'],
    },
  },
  {
    name: 'list_builds',
    description: 'List recent builds for an asset.',
    inputSchema: {
      type: 'object',
      properties: { assetId: { type: 'string' } },
      required: ['assetId'],
    },
  },
  {
    name: 'get_build_log',
    description: 'Read the captured stdout/stderr log of a build.',
    inputSchema: {
      type: 'object',
      properties: { buildId: { type: 'number' } },
      required: ['buildId'],
    },
  },
  {
    name: 'allocate_ports',
    description:
      'Allocate concrete ports from the registry for an asset, based on slots in its devportal.yaml.',
    inputSchema: {
      type: 'object',
      properties: {
        assetId: { type: 'string' },
        scope: { type: 'string', enum: ['local', 'k8s-nodeport'], default: 'local' },
        reallocate: { type: 'boolean', default: false },
      },
      required: ['assetId'],
    },
  },
  {
    name: 'list_meta_assets',
    description: 'List meta-assets (shared infra: postgres-shared, redis-shared, ...).',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'attach_consumes',
    description: 'Attach an asset to consume a meta-asset (e.g. asset uses postgres-shared as primary-db).',
    inputSchema: {
      type: 'object',
      properties: {
        assetId: { type: 'string' },
        metaAssetId: { type: 'string' },
        role: { type: 'string' },
      },
      required: ['assetId', 'metaAssetId'],
    },
  },
  {
    name: 'audit_asset',
    description:
      'Run drift audit on an asset: missing manifest, schema errors, missing docs, missing Dockerfile/k8s manifests, no port slots, etc.',
    inputSchema: {
      type: 'object',
      properties: { assetId: { type: 'string' } },
      required: ['assetId'],
    },
  },
  {
    name: 'state_git_sync',
    description: 'Export portal state to YAML and commit to the state git repo.',
    inputSchema: {
      type: 'object',
      properties: { message: { type: 'string', default: 'snapshot' } },
    },
  },
  {
    name: 'workspace_status',
    description:
      'Inspect an asset workspace: current branch, ahead/behind origin, list of dirty/untracked files. Use this before kicking a build if you suspect uncommitted edits.',
    inputSchema: {
      type: 'object',
      properties: { assetId: { type: 'string' } },
      required: ['assetId'],
    },
  },
  {
    name: 'workspace_diff',
    description:
      'Unified diff between HEAD and the working tree for one path in an asset workspace.',
    inputSchema: {
      type: 'object',
      properties: { assetId: { type: 'string' }, path: { type: 'string' } },
      required: ['assetId', 'path'],
    },
  },
  {
    name: 'commit_workspace_changes',
    description:
      'Commit selected workspace files to a side branch and optionally push to origin. Refuses main/master. Use this after editing files in an asset workspace (e.g. to fix a build).',
    inputSchema: {
      type: 'object',
      properties: {
        assetId: { type: 'string' },
        branch: {
          type: 'string',
          description: 'Branch name; if omitted, devportal/fix-<asset>-<ts> is used.',
        },
        message: { type: 'string' },
        paths: {
          type: 'array',
          items: { type: 'string' },
          description: 'Repo-relative paths to stage. Leave empty for nothing (no-op).',
        },
        push: { type: 'boolean', default: false },
      },
      required: ['assetId', 'paths'],
    },
  },
];

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools }));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const name = req.params.name;
  const args = (req.params.arguments ?? {}) as Record<string, unknown>;
  try {
    const result = await dispatch(name, args);
    return {
      content: [{ type: 'text', text: typeof result === 'string' ? result : JSON.stringify(result, null, 2) }],
    };
  } catch (err) {
    return {
      isError: true,
      content: [{ type: 'text', text: (err as Error).message }],
    };
  }
});

async function dispatch(name: string, a: Record<string, unknown>): Promise<unknown> {
  switch (name) {
    case 'list_assets':
      return portal.listAssets(a.q as string, a.type as string, a.lifecycle as string);
    case 'get_asset':
      return portal.getAsset(a.id as string);
    case 'register_from_github':
      return portal.registerFromGitHub(a.fullName as string, a.overrideId as string | undefined);
    case 'add_dependency':
      return portal.addDependency(
        a.consumerId as string,
        a.producerId as string,
        (a.versionRef as string) ?? 'main',
        (a.kind as string) ?? 'build'
      );
    case 'get_graph':
      return portal.getGraph(a.id as string);
    case 'kick_build':
      return portal.kickBuild(a.assetId as string, {
        mode: a.mode as string | undefined,
        commandName: a.commandName as string | undefined,
        commandLine: a.commandLine as string | undefined,
        ref: a.ref as string | undefined,
      });
    case 'list_builds':
      return portal.listBuilds(a.assetId as string);
    case 'get_build_log':
      return portal.getBuildLog(a.buildId as number);
    case 'allocate_ports':
      return portal.allocatePorts(
        a.assetId as string,
        (a.scope as string) ?? 'local',
        Boolean(a.reallocate)
      );
    case 'list_meta_assets':
      return portal.listMetaAssets();
    case 'attach_consumes':
      return portal.attachConsumes(
        a.assetId as string,
        a.metaAssetId as string,
        a.role as string | undefined
      );
    case 'audit_asset':
      return portal.audit(a.assetId as string);
    case 'state_git_sync':
      return portal.stateGitSync((a.message as string) ?? 'snapshot');
    case 'workspace_status':
      return portal.workspaceStatus(a.assetId as string);
    case 'workspace_diff':
      return portal.workspaceDiff(a.assetId as string, a.path as string);
    case 'commit_workspace_changes':
      return portal.commitWorkspace(a.assetId as string, {
        branch: a.branch as string | undefined,
        message: a.message as string | undefined,
        paths: (a.paths as string[]) ?? [],
        push: Boolean(a.push),
      });
    default:
      throw new Error(`Unknown tool: ${name}`);
  }
}

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // No console.log on stdio (it would corrupt the protocol). Use stderr if needed.
  process.stderr.write('devportal-mcp server connected (stdio)\n');
}

main().catch((err) => {
  process.stderr.write(`devportal-mcp fatal: ${err}\n`);
  process.exit(1);
});
