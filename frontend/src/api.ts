export type Asset = {
  id: string;
  name: string;
  description: string | null;
  owner: string | null;
  type: 'library' | 'service' | 'meta-asset';
  language: string | null;
  repoUrl: string;
  repoDefaultBranch: string;
  tags: string[];
  lifecycle: 'experimental' | 'stable' | 'deprecated';
  k8sNamespace: string | null;
  createdAt: string;
  updatedAt: string;
};

export type Dependency = {
  id: number;
  consumerId: string;
  producerId: string;
  versionRef: string;
  kind: 'build' | 'runtime';
};

export type AssetGraph = {
  rootId: string;
  nodes: Asset[];
  edges: Dependency[];
};

export type Build = {
  id: number;
  assetId: string;
  parentBuildId: number | null;
  mode: 'shallow' | 'deep';
  commandName: string;
  commandLine: string;
  gitRef: string;
  gitSha: string | null;
  workspacePath: string;
  logPath: string;
  status: 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled';
  exitCode: number | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
};

export type KickBuildBody = {
  mode?: 'shallow' | 'deep';
  commandName?: string;
  commandLine?: string;
  ref?: string;
};

export type PortReservation = {
  id: number;
  assetId: string;
  slotName: string;
  scope: 'local' | 'k8s-nodeport';
  port: number;
  protocol: string;
};

export type MetaAsset = {
  id: string;
  name: string;
  kind: string;
  config: Record<string, unknown>;
  provisionedByPortal: boolean;
  createdAt: string;
  updatedAt: string;
};

export type Consumes = {
  id: number;
  assetId: string;
  metaAssetId: string;
  role: string | null;
};

export type DockerContainer = {
  id: string;
  name: string;
  image: string;
  status: string;
  ports: string[];
};

export type RunContainerResult = {
  containerId: string;
  name: string;
  image: string;
  portMappings: { slot: string; hostPort: number; containerPort: number; protocol: string }[];
  message: string;
};

export type K8sStatus = {
  namespace: string;
  pods: { name: string; phase: string; node: string | null; startTime: string | null }[];
  services: { name: string; type: string; clusterIp: string | null; ports: number[] }[];
};

export type MonitoringLinks = {
  k9s: string;
  grafana: string | null;
  kubectlLogs: string;
};

export type AuditFinding = {
  code: string;
  severity: 'info' | 'warn' | 'error';
  area: string;
  message: string;
  fixHint: string;
};

export type AuditReport = {
  assetId: string;
  errors: number;
  warnings: number;
  info: number;
  findings: AuditFinding[];
  generatedAt: string;
};

export type ValidationResult = {
  repoUrl: string;
  fullName: string | null;
  reachable: boolean;
  defaultBranchMatches: boolean;
  defaultBranchRemote: string | null;
  hasManifest: boolean;
  hasPom: boolean;
  hasGradle: boolean;
  hasPackageJson: boolean;
  hasDockerfile: boolean;
  hasK8sDir: boolean;
  error: string | null;
};

export type MavenCoord = {
  groupId: string | null;
  artifactId: string;
  version: string | null;
  relativePath: string;
};

export type DependencyMatch = {
  coord: MavenCoord;
  matchedAssetId: string | null;
  matchedRelativePath: string | null;
  alreadyWired: boolean;
};

export type AnalyzeReport = {
  assetId: string;
  flavor: string;
  foundPom: boolean;
  publishedArtifacts: MavenCoord[];
  dependencyMatches: DependencyMatch[];
  warnings: string[];
};

export type AutoWireResult = {
  assetId: string;
  added: number;
  alreadyPresent: number;
  unmatched: number;
  wiredProducers: string[];
};

export type AssetArtifact = {
  id: number;
  assetId: string;
  flavor: string;
  groupId: string | null;
  artifactId: string;
  version: string | null;
  relativePath: string;
  detectedAt: string;
};

export type UpdateAssetBody = Partial<{
  name: string;
  description: string | null;
  owner: string | null;
  type: string;
  language: string | null;
  repoUrl: string;
  repoDefaultBranch: string;
  tags: string[];
  lifecycle: string;
  k8sNamespace: string | null;
}>;

export type GitHubRepoSummary = {
  fullName: string;
  name: string;
  owner: string;
  description: string | null;
  defaultBranch: string;
  htmlUrl: string;
  cloneUrl: string;
  fork: boolean;
  archived: boolean;
  privateRepo: boolean;
  topics: string[];
  pushedAt: string | null;
  primaryLanguage: string | null;
};

export type BulkImportRequest = {
  languages?: string[];
  includePatterns?: string[];
  excludePatterns?: string[];
  skipArchived?: boolean;
  skipForks?: boolean;
  cloneAndAnalyze?: boolean;
  autoWireMavenDeps?: boolean;
};

export type ImportJob = {
  id: number;
  owner: string;
  request: BulkImportRequest;
  status: 'queued' | 'running' | 'succeeded' | 'failed';
  startedAt: string | null;
  finishedAt: string | null;
  currentRepo: string | null;
  totalMatched: number;
  processed: number;
  registered: number;
  alreadyRegistered: number;
  analyzed: number;
  autoWired: number;
  log: string[];
  results: Record<string, unknown>[];
  error: string | null;
};

export type GitHubTokenInfo = {
  hasToken: boolean;
  preview: string | null;
  source: 'FILE' | 'ENV' | 'NONE';
};

export type GitHubTokenTestResult = {
  ok: boolean;
  authenticatedAs: string | null;
  scopes: string | null;
  message: string;
};

export type Panel = {
  id: string;
  title: string;
  layout: 'kv' | 'list' | 'code' | 'links';
  items?: { key: string; value: string }[];
  list?: string[];
  code?: string;
  links?: { label: string; url: string }[];
};

export type GitInfo = {
  fullName: string;
  description: string | null;
  defaultBranch: string;
  homepage: string | null;
  license: string | null;
  stargazersCount: number;
  forksCount: number;
  openIssuesCount: number;
  pushedAt: string | null;
  updatedAt: string | null;
  tags: { name: string; sha: string | null }[];
  topics: string[];
};

export type DocFile = {
  path: string;
  name: string;
  sizeBytes: number;
};

export type SearchResult = {
  query: string;
  assets: Asset[];
  docs: { assetId: string; path: string; lineNumber: number; snippet: string }[];
};

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  health: () => request<{ service: string; status: string; time: string }>('/api/health'),

  listAssets: (q?: string, type?: string, lifecycle?: string) => {
    const params = new URLSearchParams();
    if (q) params.set('q', q);
    if (type) params.set('type', type);
    if (lifecycle) params.set('lifecycle', lifecycle);
    const qs = params.toString();
    return request<Asset[]>(`/api/assets${qs ? `?${qs}` : ''}`);
  },

  getAsset: (id: string) => request<Asset>(`/api/assets/${id}`),

  createAsset: (body: Partial<Asset> & { id: string; name: string; type: string; repoUrl: string }) =>
    request<Asset>('/api/assets', { method: 'POST', body: JSON.stringify(body) }),

  registerFromGitHub: (fullName: string, overrideId?: string) =>
    request<Asset>('/api/assets/from-github', {
      method: 'POST',
      body: JSON.stringify({ fullName, overrideId }),
    }),

  deleteAsset: (id: string) => request<void>(`/api/assets/${id}`, { method: 'DELETE' }),

  getDependencies: (id: string) => request<Dependency[]>(`/api/assets/${id}/dependencies`),
  getConsumers: (id: string) => request<Dependency[]>(`/api/assets/${id}/consumers`),

  addDependency: (consumerId: string, producerId: string, versionRef = 'main', kind = 'build') =>
    request<Dependency>(`/api/assets/${consumerId}/dependencies`, {
      method: 'POST',
      body: JSON.stringify({ producerId, versionRef, kind }),
    }),

  removeDependency: (consumerId: string, producerId: string, kind = 'build') =>
    request<void>(`/api/assets/${consumerId}/dependencies/${producerId}?kind=${kind}`, {
      method: 'DELETE',
    }),

  getGraph: (
    id: string,
    opts: { direction?: 'producers' | 'consumers' | 'both'; producerDepth?: number; consumerDepth?: number } = {}
  ) => {
    const params = new URLSearchParams();
    if (opts.direction) params.set('direction', opts.direction);
    if (opts.producerDepth !== undefined) params.set('producerDepth', String(opts.producerDepth));
    if (opts.consumerDepth !== undefined) params.set('consumerDepth', String(opts.consumerDepth));
    const qs = params.toString();
    return request<AssetGraph>(`/api/assets/${id}/graph${qs ? `?${qs}` : ''}`);
  },

  listBuilds: (assetId: string) =>
    request<Build[]>(`/api/assets/${assetId}/builds`),

  kickBuild: (assetId: string, body: KickBuildBody) =>
    request<Build>(`/api/assets/${assetId}/builds`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  getBuild: (id: number) => request<Build>(`/api/builds/${id}`),

  getBuildLog: async (id: number): Promise<string> => {
    const res = await fetch(`/api/builds/${id}/log`);
    if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
    return res.text();
  },

  getBuildChain: (id: number) => request<Build[]>(`/api/builds/${id}/chain`),
  recentBuilds: (limit = 50) => request<Build[]>(`/api/builds?limit=${limit}`),
  deleteBuild: (id: number) => request<void>(`/api/builds/${id}`, { method: 'DELETE' }),

  // ---- ports ----
  listAllPorts: () => request<PortReservation[]>('/api/ports'),
  listPortsForAsset: (assetId: string) => request<PortReservation[]>(`/api/assets/${assetId}/ports`),
  allocatePorts: (assetId: string, scope: 'local' | 'k8s-nodeport' = 'local', reallocate = false) =>
    request<PortReservation[]>(
      `/api/assets/${assetId}/ports/allocate?scope=${scope}&reallocate=${reallocate}`,
      { method: 'POST' }
    ),
  releasePorts: (assetId: string, scope: 'local' | 'k8s-nodeport' = 'local') =>
    request<{ released: number }>(`/api/assets/${assetId}/ports?scope=${scope}`, { method: 'DELETE' }),

  // ---- meta-assets ----
  listMetaAssets: () => request<MetaAsset[]>('/api/meta-assets'),
  getMetaAsset: (id: string) => request<MetaAsset>(`/api/meta-assets/${id}`),
  createMetaAsset: (body: {
    id: string;
    name: string;
    kind: string;
    config?: Record<string, unknown>;
    provisionedByPortal?: boolean;
  }) => request<MetaAsset>('/api/meta-assets', { method: 'POST', body: JSON.stringify(body) }),
  deleteMetaAsset: (id: string) =>
    request<void>(`/api/meta-assets/${id}`, { method: 'DELETE' }),

  // ---- consumes ----
  consumesFor: (assetId: string) => request<Consumes[]>(`/api/assets/${assetId}/consumes`),
  attachConsumes: (assetId: string, metaAssetId: string, role?: string) =>
    request<Consumes>(`/api/assets/${assetId}/consumes`, {
      method: 'POST',
      body: JSON.stringify({ metaAssetId, role }),
    }),
  detachConsumes: (assetId: string, metaAssetId: string, role?: string) => {
    const qs = role ? `?role=${encodeURIComponent(role)}` : '';
    return request<void>(`/api/assets/${assetId}/consumes/${metaAssetId}${qs}`, { method: 'DELETE' });
  },

  // ---- docker ----
  dockerBuild: (assetId: string) =>
    request<Build>(`/api/assets/${assetId}/docker/build`, { method: 'POST' }),
  dockerRun: (assetId: string) =>
    request<RunContainerResult>(`/api/assets/${assetId}/docker/run`, { method: 'POST' }),
  dockerContainers: (assetId: string) =>
    request<DockerContainer[]>(`/api/assets/${assetId}/docker/containers`),
  dockerStop: (assetId: string, name: string) =>
    request<void>(`/api/assets/${assetId}/docker/containers/${name}`, { method: 'DELETE' }),

  // ---- k8s ----
  k8sApply: (assetId: string) =>
    request<Record<string, unknown>>(`/api/assets/${assetId}/k8s/apply`, { method: 'POST' }),
  k8sDelete: (assetId: string) =>
    request<Record<string, unknown>>(`/api/assets/${assetId}/k8s`, { method: 'DELETE' }),
  k8sStatus: (assetId: string) => request<K8sStatus>(`/api/assets/${assetId}/k8s/status`),
  k8sLinks: (assetId: string) => request<MonitoringLinks>(`/api/assets/${assetId}/k8s/links`),

  endpoints: (assetId: string) =>
    request<{
      assetId: string;
      endpoints: {
        label: string;
        url: string;
        scope: string;
        origin: string;
        live: boolean;
        hostAccessible: boolean;
        exposeHint: { kind: string; podName: string; containerPort: number } | null;
      }[];
    }>(`/api/assets/${assetId}/endpoints`),

  // ---- port-forwards ----
  listPortForwards: () =>
    request<{
      id: number;
      assetId: string;
      namespace: string;
      podName: string;
      containerPort: number;
      hostPort: number;
      status: 'running' | 'stopped' | 'failed';
      error: string | null;
      startedAt: string;
    }[]>('/api/port-forwards'),
  listPortForwardsForAsset: (assetId: string) =>
    request<{
      id: number;
      assetId: string;
      namespace: string;
      podName: string;
      containerPort: number;
      hostPort: number;
      status: 'running' | 'stopped' | 'failed';
      error: string | null;
      startedAt: string;
    }[]>(`/api/assets/${assetId}/port-forwards`),
  startPortForward: (
    assetId: string,
    body: { podName: string; containerPort: number; hostPort?: number }
  ) =>
    request<{
      id: number;
      assetId: string;
      namespace: string;
      podName: string;
      containerPort: number;
      hostPort: number;
      status: 'running' | 'stopped' | 'failed';
      error: string | null;
      startedAt: string;
    }>(`/api/assets/${assetId}/port-forwards`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  stopPortForward: (id: number) =>
    request<void>(`/api/port-forwards/${id}`, { method: 'DELETE' }),

  // ---- bulk import ----
  previewOrgRepos: (owner: string) =>
    request<GitHubRepoSummary[]>(`/api/orgs/${owner}/repos`),
  startBulkImport: (owner: string, body: BulkImportRequest) =>
    request<ImportJob>(`/api/orgs/${owner}/import`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  getImportJob: (id: number) => request<ImportJob>(`/api/import-jobs/${id}`),
  listImportJobs: () => request<ImportJob[]>('/api/import-jobs'),

  // ---- settings ----
  getGitHubToken: () => request<GitHubTokenInfo>('/api/settings/github'),
  setGitHubToken: (token: string) =>
    request<GitHubTokenInfo>('/api/settings/github', {
      method: 'PUT',
      body: JSON.stringify({ token }),
    }),
  clearGitHubToken: () =>
    request<GitHubTokenInfo>('/api/settings/github', { method: 'DELETE' }),
  testGitHubToken: () =>
    request<GitHubTokenTestResult>('/api/settings/github/test', { method: 'POST' }),

  audit: (assetId: string) => request<AuditReport>(`/api/assets/${assetId}/audit`),

  gitInfo: (assetId: string) => request<GitInfo>(`/api/assets/${assetId}/git-info`),
  listDocs: (assetId: string) => request<DocFile[]>(`/api/assets/${assetId}/docs`),
  readDoc: async (assetId: string, path: string): Promise<string> => {
    const res = await fetch(`/api/assets/${assetId}/docs/file?path=${encodeURIComponent(path)}`);
    if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
    return res.text();
  },
  search: (q: string, includeDocs = true) =>
    request<SearchResult>(`/api/search?q=${encodeURIComponent(q)}&includeDocs=${includeDocs}`),

  // ---- cluster monitoring ----
  k8sPods: (assetId: string) =>
    request<{
      name: string;
      namespace: string;
      phase: string;
      node: string | null;
      podIp: string | null;
      readyContainers: number;
      totalContainers: number;
      restartCount: number;
      startTime: string | null;
      labels: Record<string, string>;
      containers: {
        name: string;
        image: string;
        ready: boolean;
        restartCount: number;
        state: string;
        reason: string | null;
        lastTermReason: string | null;
        lastTermExitCode: number | null;
        env: { name: string; value: string }[];
        ports: number[];
      }[];
    }[]>(`/api/assets/${assetId}/k8s/pods`),
  k8sPodLogs: async (assetId: string, podName: string, container?: string, tail = 200) => {
    const params = new URLSearchParams({ tail: String(tail) });
    if (container) params.set('container', container);
    const res = await fetch(`/api/assets/${assetId}/k8s/pods/${podName}/logs?${params}`);
    if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
    return res.text();
  },
  k8sPodDescribe: async (assetId: string, podName: string) => {
    const res = await fetch(`/api/assets/${assetId}/k8s/pods/${podName}/describe`);
    if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
    return res.text();
  },
  k8sPodEvents: (assetId: string) =>
    request<{
      type: string;
      reason: string;
      message: string;
      source: string | null;
      firstSeen: string | null;
      lastSeen: string | null;
      count: number;
      involvedObject: string;
    }[]>(`/api/assets/${assetId}/k8s/events`),

  k8sRender: (assetId: string) =>
    request<{ asset: string; renderedDir: string; source: string }>(
      `/api/assets/${assetId}/k8s/render`,
      { method: 'POST' }
    ),
  k8sScaffold: (assetId: string) =>
    request<{ asset: string; files: string[]; note: string }>(
      `/api/assets/${assetId}/k8s/scaffold`,
      { method: 'POST' }
    ),
  k8sCommitRender: (assetId: string, branch?: string, message?: string, push = false) => {
    const params = new URLSearchParams({ push: String(push) });
    if (branch) params.set('branch', branch);
    if (message) params.set('message', message);
    return request<{
      assetId: string;
      branch: string;
      commit: string | null;
      filesChanged: string[];
      pushed: boolean;
      pushOutput: string | null;
      prSuggestion: string | null;
    }>(`/api/assets/${assetId}/k8s/commit-render?${params}`, { method: 'POST' });
  },
  k8sCommitWorkspace: (assetId: string, branch?: string, message?: string, push = false) => {
    const params = new URLSearchParams({ push: String(push) });
    if (branch) params.set('branch', branch);
    if (message) params.set('message', message);
    return request<{
      assetId: string;
      branch: string;
      commit: string | null;
      filesChanged: string[];
      pushed: boolean;
      pushOutput: string | null;
      prSuggestion: string | null;
    }>(`/api/assets/${assetId}/k8s/commit-workspace?${params}`, { method: 'POST' });
  },
  verifyAsset: (assetId: string, stage: 'docker' | 'k8s' = 'docker') =>
    request<{
      assetId: string;
      stage: string;
      passed: boolean;
      failedAt: string | null;
      steps: { name: string; ok: boolean; durationMs: number; detail: string }[];
      summary: string;
    }>(`/api/assets/${assetId}/verify?stage=${stage}`, { method: 'POST' }),

  helpPrompt: (
    assetId: string,
    problem: string = 'general',
    details?: string
  ) => {
    const params = new URLSearchParams({ problem });
    if (details) params.set('details', details);
    return request<{ assetId: string; problem: string; text: string }>(
      `/api/assets/${assetId}/help-prompt?${params}`
    );
  },
  panels: (assetId: string) => request<Panel[]>(`/api/assets/${assetId}/panels`),

  updateAsset: (id: string, body: UpdateAssetBody) =>
    request<Asset>(`/api/assets/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),

  validateAsset: (id: string) => request<ValidationResult>(`/api/assets/${id}/validate`),
  analyzeAsset: (id: string) =>
    request<AnalyzeReport>(`/api/assets/${id}/analyze`, { method: 'POST' }),
  autoWireAsset: (id: string) =>
    request<AutoWireResult>(`/api/assets/${id}/auto-wire`, { method: 'POST' }),
  listArtifacts: (id: string) => request<AssetArtifact[]>(`/api/assets/${id}/artifacts`),

  exportState: () => request<{ dir: string }>('/api/state/export', { method: 'POST' }),
  gitSync: (message: string) =>
    request<{ dir: string; initialized: boolean; commit: string | null }>(
      `/api/state/git-sync?message=${encodeURIComponent(message)}`,
      { method: 'POST' }
    ),
};
