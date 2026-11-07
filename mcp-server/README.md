# devportal-mcp

MCP stdio server exposing dev_portal operations to Claude Code.

## Build

```sh
pnpm install
pnpm build
```

## Use with Claude Code

Add to `~/.claude/mcp.json`:

```json
{
  "mcpServers": {
    "devportal": {
      "command": "node",
      "args": ["/Users/chris/code/claude_world/dev_portal/mcp-server/dist/index.js"],
      "env": {
        "DEVPORTAL_URL": "http://localhost:8081"
      }
    }
  }
}
```

Then `claude` will see tools prefixed `mcp__devportal__*`.

## Tools

| Name                  | What it does                                            |
|-----------------------|---------------------------------------------------------|
| `list_assets`         | Filter by query / type / lifecycle.                     |
| `get_asset`           | Fetch one asset.                                        |
| `register_from_github`| Onboard a repo (uses devportal.yaml if present).        |
| `add_dependency`      | Add an edge between assets.                             |
| `get_graph`           | Reachable dep graph for an asset.                       |
| `kick_build`          | Run a build (shallow or deep).                          |
| `list_builds`         | Recent builds.                                          |
| `get_build_log`       | Captured stdout/stderr.                                 |
| `allocate_ports`      | Allocate registry ports per manifest slots.             |
| `list_meta_assets`    | Shared-infra inventory.                                 |
| `attach_consumes`     | Wire asset → meta-asset (role).                         |
| `audit_asset`         | Drift report against portal conventions.                |
| `state_git_sync`      | YAML export + commit to state repo.                     |
| `list_deployments`    | What is running where (filters; `targetsOnly` roll-up). |
| `get_deployment`      | One deployment by id.                                   |
| `report_deployment`   | Upsert a deployment into the registry.                  |

Registry writes send `X-DevHub-Token` when `DEVHUB_REGISTRY_TOKEN` is set in the server's env (needed only if the backend sets the same variable).
