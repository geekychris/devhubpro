import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api';

export function SearchBar() {
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const ref = useRef<HTMLDivElement>(null);

  const debounced = useDebounced(q, 250);
  const results = useQuery({
    queryKey: ['search', debounced],
    queryFn: () => api.search(debounced),
    enabled: debounced.length >= 2,
    staleTime: 5_000,
  });

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('click', onClick);
    return () => document.removeEventListener('click', onClick);
  }, []);

  return (
    <div ref={ref} className="relative w-80">
      <input
        type="search"
        value={q}
        onChange={(e) => {
          setQ(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            navigate(`/assets?q=${encodeURIComponent(q)}`);
            setOpen(false);
          }
          if (e.key === 'Escape') setOpen(false);
        }}
        placeholder="Search assets and docs…"
        className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none"
      />
      {open && debounced.length >= 2 && results.data && (
        <div className="absolute z-50 mt-1 max-h-[60vh] w-[28rem] overflow-auto rounded border border-gray-300 bg-white shadow-lg">
          <Section
            title={`Assets (${results.data.assets.length})`}
            empty={results.data.assets.length === 0}
          >
            {results.data.assets.slice(0, 8).map((a) => (
              <Link
                key={a.id}
                to={`/assets/${a.id}`}
                onClick={() => setOpen(false)}
                className="block border-b border-gray-100 px-3 py-2 text-sm hover:bg-gray-50"
              >
                <div className="font-medium">{a.name}</div>
                <div className="text-xs text-gray-500">
                  {a.id} · {a.type} {a.language ? `· ${a.language}` : ''}
                </div>
              </Link>
            ))}
          </Section>
          <Section
            title={`Docs (${results.data.docs.length})`}
            empty={results.data.docs.length === 0}
          >
            {results.data.docs.slice(0, 12).map((d, i) => (
              <Link
                key={i}
                to={`/assets/${d.assetId}?docs=${encodeURIComponent(d.path)}`}
                onClick={() => setOpen(false)}
                className="block border-b border-gray-100 px-3 py-2 text-xs hover:bg-gray-50"
              >
                <div className="font-mono">
                  {d.assetId} <span className="text-gray-400">·</span> {d.path}
                  <span className="ml-2 text-gray-400">L{d.lineNumber}</span>
                </div>
                <div className="mt-0.5 text-gray-600">{d.snippet}</div>
              </Link>
            ))}
          </Section>
        </div>
      )}
    </div>
  );
}

function Section({
  title,
  empty,
  children,
}: {
  title: string;
  empty: boolean;
  children: React.ReactNode;
}) {
  return (
    <div>
      <div className="border-b border-gray-200 bg-gray-50 px-3 py-1 text-xs font-medium uppercase text-gray-500">
        {title}
      </div>
      {empty ? <div className="px-3 py-2 text-xs text-gray-500">no matches</div> : children}
    </div>
  );
}

function useDebounced<T>(value: T, delay: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return v;
}
