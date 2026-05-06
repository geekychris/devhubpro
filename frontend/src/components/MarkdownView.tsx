import { useEffect, useMemo, useRef } from 'react';
import { marked } from 'marked';

// Tracks whether mermaid.initialize() has been called for the current page session.
let mermaidInitialized = false;

/**
 * Renders a markdown string with mermaid support. marked converts the source to HTML, then a
 * post-mount effect finds every {@code <code class="language-mermaid">} block and asks mermaid
 * to render it into an SVG. Failed renders fall back to a styled error block so users can fix
 * the diagram source without losing the rest of the page.
 *
 * <p>Mermaid is loaded lazily via {@code import('mermaid')} so it only ships when a markdown
 * document actually contains a diagram (saves ~500 KB on the main bundle for the dashboard /
 * asset list / etc).
 */
export function MarkdownView({
  source,
  className,
}: {
  source: string;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);

  // Memoize the parsed HTML so we don't re-run marked on every re-render.
  const html = useMemo(() => marked.parse(source, { async: false }) as string, [source]);

  useEffect(() => {
    if (!ref.current) return;
    const mermaidBlocks = ref.current.querySelectorAll<HTMLElement>('code.language-mermaid');
    if (mermaidBlocks.length === 0) return;            // no diagrams → don't load mermaid
    let cancelled = false;

    (async () => {
      // Dynamic import — Vite/Rolldown splits this into its own chunk that's only fetched the
      // first time the user opens a doc with a mermaid block.
      const mermaidModule = await import('mermaid');
      if (cancelled) return;
      const mermaid = mermaidModule.default;
      if (!mermaidInitialized) {
        mermaid.initialize({
          startOnLoad: false,
          theme: 'default',
          securityLevel: 'loose',  // allow standard mermaid features (tooltips, click handlers)
          fontFamily: 'ui-sans-serif, system-ui, -apple-system, sans-serif',
        });
        mermaidInitialized = true;
      }
      let n = 0;
      for (const codeEl of Array.from(mermaidBlocks)) {
        const pre = codeEl.parentElement;
        const definition = codeEl.textContent ?? '';
        const id = `mermaid-${Date.now()}-${n++}`;
        try {
          const { svg } = await mermaid.render(id, definition);
          if (cancelled) return;
          if (pre && pre.parentElement) {
            const wrapper = document.createElement('div');
            wrapper.className = 'mermaid-rendered my-4 overflow-x-auto';
            wrapper.innerHTML = svg;
            pre.parentElement.replaceChild(wrapper, pre);
          }
        } catch (e) {
          if (cancelled) return;
          if (pre) {
            pre.classList.add('mermaid-error');
            const msg = document.createElement('div');
            msg.className = 'mermaid-error-msg';
            msg.textContent = `Mermaid render error: ${(e as Error).message}`;
            pre.parentElement?.insertBefore(msg, pre);
          }
        }
      }
    })();

    return () => { cancelled = true; };
  }, [html]);

  return (
    <div
      ref={ref}
      className={`markdown ${className ?? ''}`}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
