import { useCallback, useState } from "react";
import { submitSearch } from "./api";
import { SearchTypeahead } from "./components/SearchTypeahead";
import { Toast } from "./components/Toast";
import { Trending } from "./components/Trending";

interface ToastState {
  message: string;
  query: string;
  nonce: number;
}

export default function App() {
  const [query, setQuery] = useState("");
  const [toast, setToast] = useState<ToastState | null>(null);

  const submit = useCallback(async (raw: string) => {
    const q = raw.trim();
    if (q.length === 0) return;
    try {
      const message = await submitSearch(q);
      setToast({ message, query: q, nonce: Date.now() });
    } catch {
      setToast({ message: "Couldn’t reach search", query: q, nonce: Date.now() });
    }
  }, []);

  const pickTrending = useCallback(
    (q: string) => {
      setQuery(q);
      void submit(q);
    },
    [submit],
  );

  return (
    <div className="mx-auto flex min-h-full max-w-2xl flex-col px-5 pb-24 pt-[14vh]">
      <header className="mb-9 text-center">
        <h1 className="text-3xl font-semibold tracking-tight text-ink">
          What are you looking for?
        </h1>
        <p className="mt-2 text-ink-soft">
          Start typing — suggestions rank by popularity and recency.
        </p>
      </header>

      <SearchTypeahead query={query} onQueryChange={setQuery} onSubmit={(q) => void submit(q)} />

      <Trending onPick={pickTrending} />

      {toast && (
        <Toast
          key={toast.nonce}
          message={toast.message}
          query={toast.query}
          onDismiss={() => setToast(null)}
        />
      )}
    </div>
  );
}
