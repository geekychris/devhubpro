import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api';
import { DeploymentsTable } from './DeploymentsTable';

/** Where this asset runs (or was delivered), from the deployment registry. */
export function AssetDeploymentsTab({ assetId }: { assetId: string }) {
  const [includeRetired, setIncludeRetired] = useState(false);
  const q = useQuery({
    queryKey: ['asset-deployments', assetId, includeRetired],
    queryFn: () => api.listAssetDeployments(assetId, includeRetired),
    refetchInterval: 30_000,
  });
  return (
    <section className="rounded border border-gray-200 bg-white p-4">
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-medium text-gray-900">Deployments</h2>
        <div className="flex items-center gap-3 text-xs">
          <label className="flex items-center gap-1 text-gray-600">
            <input type="checkbox" checked={includeRetired} onChange={(e) => setIncludeRetired(e.target.checked)} />
            include retired
          </label>
          <Link to="/deployments" className="text-blue-700 hover:underline">all deployments →</Link>
        </div>
      </div>
      {q.isLoading && <p className="text-sm text-gray-500">Loading…</p>}
      {q.error && <p className="text-sm text-red-600">{(q.error as Error).message}</p>}
      {q.data && q.data.length === 0 && (
        <p className="text-sm text-gray-500">
          No deployment of this asset has been reported. Reporters (cloud-stack, the MCP tool
          <code className="mx-1">report_deployment</code>, or <code>POST /api/deployments/report</code>) add them.
        </p>
      )}
      {q.data && q.data.length > 0 && <DeploymentsTable rows={q.data} showAsset={false} showTarget />}
    </section>
  );
}
