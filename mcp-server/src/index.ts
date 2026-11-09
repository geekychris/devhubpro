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
  {
    name: 'list_peer_runtimes',
    description:
      'List peer runtime systems registered with Dev Portal (OrionMesh, KQueue, ...). Optionally filter by kind.',
    inputSchema: {
      type: 'object',
      properties: {
        kind: {
          type: 'string',
          description: "Filter by kind ('orionmesh', 'kqueue', ...). Omit for all.",
        },
      },
    },
  },
  {
    name: 'register_peer_runtime',
    description:
      'Register (or update) a peer runtime — e.g. an OrionMesh controller or a KQueue instance. Idempotent on `name`.',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string', description: "Stable slug, e.g. 'orionmesh-belmont'." },
        kind: { type: 'string', description: "'orionmesh' | 'kqueue' | future kinds." },
        baseUrl: { type: 'string', description: 'Controller / API base URL.' },
        adminUiUrl: { type: 'string', description: 'Optional UI URL for deep-link/embed.' },
        config: { type: 'object', description: 'Peer-specific config (e.g. natsUrl).' },
      },
      required: ['name', 'kind', 'baseUrl'],
    },
  },
  {
    name: 'list_deployments',
    description:
      'Deployment registry: what is running where. Lists reported deployments (asset, target such as wopr/mp2/m1/spark, namespace, version, URLs, health status). Filter by target, assetId, status, source or phase; set targetsOnly for a per-target roll-up instead.',
    inputSchema: {
      type: 'object',
      properties: {
        target: { type: 'string', description: "Target name as reported, e.g. 'wopr', 'mp2', 'm1', 'spark'" },
        assetId: { type: 'string' },
        status: { type: 'string', enum: ['healthy', 'degraded', 'down', 'unknown', 'reported', 'retired'] },
        source: { type: 'string', description: "Reporter, e.g. 'cloud-stack saaspack', 'manual'" },
        phase: { type: 'string', enum: ['running', 'delivered'] },
        q: { type: 'string', description: 'Substring over asset, target, namespace, version, source' },
        includeRetired: { type: 'boolean', default: false },
        targetsOnly: { type: 'boolean', default: false, description: 'Return per-target counts instead of rows' },
      },
    },
  },
  {
    name: 'asset_cloudstack_activity',
    description:
      "cloud-stack (the agent/SDLC platform) activity for an asset's GitHub repo: open PRs with what each needs and its checks (passing/failing/pending/none), the latest Jenkins result, active and recent agent runs (with links), and newTaskUrl - the cloud-stack page to start a new agent task on this repo. available=false with reasonCode (not-configured | no-github-repo | unreachable | unauthorized | bad-response) when it cannot be fetched; newTaskUrl may still be set.",
    inputSchema: {
      type: 'object',
      properties: { assetId: { type: 'string', description: 'Asset id, e.g. devhubpro' } },
      required: ['assetId'],
    },
  },
  {
    name: 'get_deployment',
    description: 'Fetch one deployment by numeric id (from list_deployments).',
    inputSchema: {
      type: 'object',
      properties: { id: { type: 'number' } },
      required: ['id'],
    },
  },
  {
    name: 'report_deployment',
    description:
      'Report (upsert) a deployment into the registry - idempotent on (assetId, target, namespace, name). If assetId is unknown, a minimal asset is created from asset.name / asset.repoUrl. Use phase "delivered" for something built or delivered as a PR but not known to be running. Store hostnames/URLs exactly as you reach them; never resolve them to IPs.',
    inputSchema: {
      type: 'object',
      properties: {
        assetId: { type: 'string', description: 'Existing or new asset id (slug: lowercase letters, digits, dashes)' },
        asset: {
          type: 'object',
          description: 'Used only to create the asset when it does not exist yet',
          properties: {
            name: { type: 'string' },
            repoUrl: { type: 'string' },
            type: { type: 'string', enum: ['library', 'service', 'meta-asset'] },
            description: { type: 'string' },
            tags: { type: 'array', items: { type: 'string' } },
          },
        },
        target: { type: 'string', description: "Where it runs: 'wopr', 'mp2', 'm1', 'spark', ..." },
        targetKind: { type: 'string', enum: ['k8s-cluster', 'host', 'faas', 'docker', 'mac', 'other'] },
        namespace: { type: 'string' },
        name: { type: 'string', description: 'Instance name when an asset runs more than once on a target' },
        version: { type: 'string' },
        commit: { type: 'string' },
        ref: { type: 'string' },
        phase: { type: 'string', enum: ['running', 'delivered'], default: 'running' },
        urls: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              label: { type: 'string' },
              url: { type: 'string' },
              primary: { type: 'boolean' },
            },
            required: ['url'],
          },
        },
        healthUrl: { type: 'string', description: 'HTTP URL the registry polls (default: the primary URL)' },
        status: { type: 'string', enum: ['healthy', 'degraded', 'down', 'unknown', 'reported'] },
        labels: { type: 'object', additionalProperties: { type: 'string' } },
        notes: { type: 'string' },
        source: { type: 'string', default: 'claude-code', description: 'Who is reporting' },
      },
      required: ['target'],
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
    case 'list_peer_runtimes':
      return portal.listPeerRuntimes(a.kind as string | undefined);
    case 'register_peer_runtime':
      return portal.registerPeerRuntime({
        name: a.name as string,
        kind: a.kind as string,
        baseUrl: a.baseUrl as string,
        adminUiUrl: a.adminUiUrl as string | undefined,
        config: a.config as Record<string, unknown> | undefined,
      });
    case 'list_deployments': {
      if (a.targetsOnly) return portal.listDeploymentTargets();
      return portal.listDeployments({
        target: a.target as string | undefined,
        assetId: a.assetId as string | undefined,
        status: a.status as string | undefined,
        source: a.source as string | undefined,
        phase: a.phase as string | undefined,
        q: a.q as string | undefined,
        includeRetired: Boolean(a.includeRetired),
      });
    }
    case 'asset_cloudstack_activity':
      return portal.assetCloudStackActivity(a.assetId as string);
    case 'get_deployment':
      return portal.getDeployment(a.id as number);
    case 'report_deployment': {
      const { source, ...item } = a;
      const res = (await portal.reportDeployments({
        source: (source as string) || 'claude-code',
        deployments: [item],
      })) as { deployments?: unknown[] };
      return res.deployments?.[0] ?? res;
    }
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
