import { useEffect, useState } from "react";
import { fetchCacheDebug, type CacheDebug } from "../api";
import { useDebouncedValue } from "../hooks/useDebouncedValue";

interface CacheInspectorProps {
  prefix: string;
}

// Stable per-node colour so the same node always reads the same — makes it obvious when two
// prefixes route to different Redis nodes (the consistent-hash ring at work).
const NODE_DOT: Record<string, string> = {
  "1": "bg-rose-500",
  "2": "bg-emerald-500",
  "3": "bg-sky-500",
};

function nodeKey(label: string): string {
  const m = label.match(/redis-(\d)/);
  return m ? m[1] : "0";
}

export function CacheInspector({ prefix }: CacheInspectorProps) {
  const debounced = useDebouncedValue(prefix.trim().toLowerCase(), 200);
  const [data, setData] = useState<CacheDebug | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (debounced.length === 0) {
      setData(null);
      setError(false);
      return;
    }
    const controller = new AbortController();
    fetchCacheDebug(debounced, controller.signal)
      .then((d) => {
        setData(d);
        setError(false);
      })
      .catch(() => {
        if (!controller.signal.aborted) setError(true);
      });
    return () => controller.abort();
  }, [debounced]);

  return (
    <div className="rounded-xl2 border border-black/[0.06] bg-surface-raised px-5 py-4 text-sm shadow-sm animate-fade-in">
      <div className="mb-3 flex items-center gap-2 text-ink-faint">
        <span className="text-xs font-semibold uppercase tracking-[0.14em]">Cache routing</span>
        <span className="text-ink-faint/70">— consistent-hash ring over 3 Redis nodes</span>
      </div>

      {debounced.length === 0 ? (
        <p className="text-ink-faint">Type a prefix to see which node owns it.</p>
      ) : error ? (
        <p className="text-ink-soft">Cache debug unavailable.</p>
      ) : data ? (
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
          <Field label="prefix">
            <code className="rounded bg-surface-sunken px-1.5 py-0.5 text-ink">{data.prefix}</code>
          </Field>
          <Field label="owner node">
            <span className="inline-flex items-center gap-1.5 font-medium text-ink">
              <span className={`h-2.5 w-2.5 rounded-full ${NODE_DOT[nodeKey(data.ownerNode)] ?? "bg-ink-faint"}`} />
              {data.ownerNode}
            </span>
          </Field>
          <Field label="status">
            <span
              className={`rounded-full px-2 py-0.5 text-xs font-semibold ${
                data.status === "HIT"
                  ? "bg-emerald-100 text-emerald-700"
                  : "bg-amber-100 text-amber-700"
              }`}
            >
              {data.status}
            </span>
          </Field>
          <Field label="generation">
            <span className="font-medium text-ink">v{data.generation}</span>
          </Field>
          <Field label="key">
            <code className="text-ink-soft">{data.key}</code>
          </Field>
        </div>
      ) : (
        <p className="text-ink-faint">Looking up…</p>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <span className="inline-flex items-center gap-2">
      <span className="text-ink-faint">{label}</span>
      {children}
    </span>
  );
}
