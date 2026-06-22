import { useEffect, useState } from "react";
import { fetchTrending } from "../api";
import type { Suggestion } from "../types";
import { TrendingIcon } from "./icons";

interface TrendingProps {
  onPick: (query: string) => void;
}

export function Trending({ onPick }: TrendingProps) {
  const [items, setItems] = useState<Suggestion[]>([]);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetchTrending(controller.signal)
      .then((rows) => setItems(rows.slice(0, 8)))
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      });
    return () => controller.abort();
  }, []);

  if (failed || items.length === 0) {
    return null;
  }

  return (
    <section className="mt-10 animate-fade-in">
      <div className="mb-3 flex items-center gap-2 text-ink-faint">
        <TrendingIcon className="h-4 w-4" />
        <h2 className="text-xs font-semibold uppercase tracking-[0.14em]">Trending now</h2>
      </div>
      <div className="flex flex-wrap gap-2">
        {items.map((item) => (
          <button
            key={item.query}
            type="button"
            onClick={() => onPick(item.query)}
            className="rounded-full border border-black/[0.06] bg-surface-raised px-3.5 py-1.5 text-sm text-ink-soft shadow-sm transition hover:border-accent-ring hover:text-ink hover:shadow-pop focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-ring"
          >
            {item.query}
          </button>
        ))}
      </div>
    </section>
  );
}
