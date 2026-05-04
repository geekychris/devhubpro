import { Link, NavLink, Route, Routes } from 'react-router-dom';
import { AssetsPage } from './pages/AssetsPage';
import { AssetDetailPage } from './pages/AssetDetailPage';
import { RegisterPage } from './pages/RegisterPage';
import { MetaAssetsPage } from './pages/MetaAssetsPage';
import { SettingsPage } from './pages/SettingsPage';
import { BulkImportPage } from './pages/BulkImportPage';
import { SearchBar } from './components/SearchBar';

export function App() {
  return (
    <div className="min-h-screen flex flex-col">
      <header className="border-b border-gray-200 bg-white">
        <div className="max-w-7xl mx-auto px-6 py-3 flex items-center gap-6">
          <Link to="/" className="shrink-0" aria-label="DevHub Pro home">
            <img
              src="/devhubpro.png"
              alt="DevHub Pro"
              className="h-12 w-auto"
            />
          </Link>
          <SearchBar />
          <nav className="flex gap-4 text-sm ml-auto">
            <NavLink
              to="/assets"
              className={({ isActive }) =>
                isActive ? 'text-blue-700 font-medium' : 'text-gray-600 hover:text-gray-900'
              }
            >
              Assets
            </NavLink>
            <NavLink
              to="/meta-assets"
              className={({ isActive }) =>
                isActive ? 'text-blue-700 font-medium' : 'text-gray-600 hover:text-gray-900'
              }
            >
              Meta-assets
            </NavLink>
            <NavLink
              to="/register"
              className={({ isActive }) =>
                isActive ? 'text-blue-700 font-medium' : 'text-gray-600 hover:text-gray-900'
              }
            >
              Register
            </NavLink>
            <NavLink
              to="/bulk-import"
              className={({ isActive }) =>
                isActive ? 'text-blue-700 font-medium' : 'text-gray-600 hover:text-gray-900'
              }
            >
              Bulk import
            </NavLink>
            <NavLink
              to="/settings"
              className={({ isActive }) =>
                isActive ? 'text-blue-700 font-medium' : 'text-gray-600 hover:text-gray-900'
              }
            >
              Settings
            </NavLink>
          </nav>
        </div>
      </header>
      <main className="flex-1 max-w-6xl mx-auto w-full px-6 py-6">
        <Routes>
          <Route path="/" element={<AssetsPage />} />
          <Route path="/assets" element={<AssetsPage />} />
          <Route path="/assets/:id" element={<AssetDetailPage />} />
          <Route path="/meta-assets" element={<MetaAssetsPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/bulk-import" element={<BulkImportPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </main>
    </div>
  );
}
