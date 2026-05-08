import { Link, NavLink, Route, Routes } from 'react-router-dom';
import { AssetsPage } from './pages/AssetsPage';
import { AssetDetailPage } from './pages/AssetDetailPage';
import { RegisterPage } from './pages/RegisterPage';
import { MetaAssetsPage } from './pages/MetaAssetsPage';
import { SettingsPage } from './pages/SettingsPage';
import { BulkImportPage } from './pages/BulkImportPage';
import { PortsPage } from './pages/PortsPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { ProxyRoutesPage } from './pages/ProxyRoutesPage';
import { DashboardPage } from './pages/DashboardPage';
import { SearchBar } from './components/SearchBar';

export function App() {
  return (
    <div className="min-h-screen flex flex-col">
      <header className="border-b border-gray-800 bg-slate-900 text-gray-100">
        <div className="max-w-7xl mx-auto px-6 py-3 flex items-center gap-6">
          <Link to="/" className="shrink-0" aria-label="DevHub Pro home">
            <img
              src="/devhubpro.png"
              alt="DevHub Pro"
              className="h-auto w-[300px]"
            />
          </Link>
          <SearchBar />
          <nav className="flex gap-4 text-sm ml-auto">
            <NavLink
              to="/dashboard"
              className={({ isActive }) =>
                isActive ? 'text-blue-400 font-medium' : 'text-gray-300 hover:text-white'
              }
            >
              Dashboard
            </NavLink>
            <NavLink
              to="/assets"
              className={({ isActive }) =>
                isActive ? 'text-blue-400 font-medium' : 'text-gray-300 hover:text-white'
              }
            >
              Assets
            </NavLink>
            <NavLink
              to="/projects"
              className={({ isActive }) =>
                isActive ? 'text-blue-400 font-medium' : 'text-gray-300 hover:text-white'
              }
            >
              Projects
            </NavLink>
            <NavLink
              to="/meta-assets"
              className={({ isActive }) =>
                isActive ? 'text-blue-400 font-medium' : 'text-gray-300 hover:text-white'
              }
            >
              Meta-assets
            </NavLink>
            <NavLink
              to="/ports"
              className={({ isActive }) =>
                isActive ? 'text-blue-400 font-medium' : 'text-gray-300 hover:text-white'
              }
            >
              Ports
            </NavLink>
            <NavLink
              to="/proxy-routes"
              className={({ isActive }) =>
                isActive ? 'text-blue-400 font-medium' : 'text-gray-300 hover:text-white'
              }
            >
              Proxy
            </NavLink>
            <NavLink
              to="/register"
              className={({ isActive }) =>
                isActive ? 'text-blue-400 font-medium' : 'text-gray-300 hover:text-white'
              }
            >
              Register
            </NavLink>
            <NavLink
              to="/bulk-import"
              className={({ isActive }) =>
                isActive ? 'text-blue-400 font-medium' : 'text-gray-300 hover:text-white'
              }
            >
              Bulk import
            </NavLink>
            <NavLink
              to="/settings"
              className={({ isActive }) =>
                isActive ? 'text-blue-400 font-medium' : 'text-gray-300 hover:text-white'
              }
            >
              Settings
            </NavLink>
          </nav>
        </div>
      </header>
      <main className="flex-1 max-w-6xl mx-auto w-full px-6 py-6">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/assets" element={<AssetsPage />} />
          <Route path="/assets/:id" element={<AssetDetailPage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/meta-assets" element={<MetaAssetsPage />} />
          <Route path="/ports" element={<PortsPage />} />
          <Route path="/proxy-routes" element={<ProxyRoutesPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/bulk-import" element={<BulkImportPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </main>
    </div>
  );
}
