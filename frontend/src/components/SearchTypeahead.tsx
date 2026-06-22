import { useEffect, useId, useRef, useState } from "react";
import { fetchSuggestions } from "../api";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import type { Suggestion } from "../types";
import { SearchIcon, Spinner } from "./icons";

interface SearchTypeaheadProps {
  query: string;
  onQueryChange: (value: string) => void;
  onSubmit: (query: string) => void;
}

const DEBOUNCE_MS = 140;

export function SearchTypeahead({ query, onQueryChange, onSubmit }: SearchTypeaheadProps) {
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  // True once a fetch for the current debounced prefix has resolved — lets us tell
  // "still loading" apart from "loaded, genuinely no matches".
  const [resolved, setResolved] = useState(false);

  const debounced = useDebouncedValue(query.trim(), DEBOUNCE_MS);
  const listboxId = useId();
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (debounced.length === 0) {
      setSuggestions([]);
      setLoading(false);
      setError(false);
      setResolved(false);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setError(false);
    fetchSuggestions(debounced, controller.signal)
      .then((rows) => {
        setSuggestions(rows);
        setActiveIndex(-1);
        setResolved(true);
        setLoading(false);
      })
      .catch((err) => {
        if (controller.signal.aborted || err?.name === "AbortError") return;
        setError(true);
        setSuggestions([]);
        setLoading(false);
      });
    return () => controller.abort();
  }, [debounced]);

  const showPanel = open && query.trim().length > 0;

  function commit(value: string) {
    const q = value.trim();
    if (q.length === 0) return;
    onQueryChange(value);
    setOpen(false);
    setActiveIndex(-1);
    onSubmit(q);
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!showPanel) {
        setOpen(true);
        return;
      }
      if (suggestions.length > 0) {
        setActiveIndex((i) => (i + 1) % suggestions.length);
      }
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (suggestions.length > 0) {
        setActiveIndex((i) => (i <= 0 ? suggestions.length - 1 : i - 1));
      }
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (showPanel && activeIndex >= 0 && suggestions[activeIndex]) {
        commit(suggestions[activeIndex].query);
      } else {
        commit(query);
      }
    } else if (e.key === "Escape") {
      setOpen(false);
      setActiveIndex(-1);
    }
  }

  return (
    <div className="relative">
      <div
        className={`flex items-center gap-3 rounded-xl2 border bg-surface px-5 py-4 shadow-card transition ${
          showPanel ? "border-accent-ring ring-4 ring-accent/10" : "border-black/[0.06]"
        }`}
      >
        <SearchIcon className="h-5 w-5 shrink-0 text-ink-faint" />
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(e) => {
            onQueryChange(e.target.value);
            setOpen(true);
            setActiveIndex(-1);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setOpen(false)}
          onKeyDown={onKeyDown}
          placeholder="Search products…"
          autoComplete="off"
          spellCheck={false}
          role="combobox"
          aria-expanded={showPanel}
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={
            activeIndex >= 0 ? `${listboxId}-opt-${activeIndex}` : undefined
          }
          className="w-full bg-transparent text-lg text-ink placeholder:text-ink-faint focus:outline-none"
        />
        {loading && <Spinner className="h-5 w-5 shrink-0 animate-spin text-accent" />}
      </div>

      {showPanel && (
        <div className="absolute inset-x-0 top-full z-20 mt-2 overflow-hidden rounded-xl2 border border-black/[0.06] bg-surface shadow-pop animate-slide-up">
          {error ? (
            <p className="px-5 py-4 text-sm text-ink-soft">
              Suggestions are unavailable right now — you can still press Enter to search.
            </p>
          ) : suggestions.length > 0 ? (
            <ul id={listboxId} role="listbox" className="max-h-80 overflow-y-auto py-1.5">
              {suggestions.map((s, i) => (
                <li key={s.query} id={`${listboxId}-opt-${i}`} role="option" aria-selected={i === activeIndex}>
                  <button
                    type="button"
                    // Keep focus on the input so onBlur doesn't close the panel before the click lands.
                    onMouseDown={(e) => e.preventDefault()}
                    onMouseEnter={() => setActiveIndex(i)}
                    onClick={() => commit(s.query)}
                    className={`flex w-full items-center gap-3 px-5 py-2.5 text-left text-[15px] transition ${
                      i === activeIndex ? "bg-accent-soft text-ink" : "text-ink-soft"
                    }`}
                  >
                    <SearchIcon className="h-4 w-4 shrink-0 text-ink-faint" />
                    <span className="truncate">{renderMatch(s.query, debounced)}</span>
                  </button>
                </li>
              ))}
            </ul>
          ) : resolved && !loading ? (
            <p className="px-5 py-4 text-sm text-ink-soft">
              No matches for “{debounced}”. Press Enter to search anyway.
            </p>
          ) : (
            <p className="px-5 py-4 text-sm text-ink-faint">Searching…</p>
          )}
        </div>
      )}
    </div>
  );
}

/** Bold the leading prefix the user typed; the suggestion is a prefix match of it. */
function renderMatch(text: string, prefix: string) {
  if (prefix.length > 0 && text.toLowerCase().startsWith(prefix.toLowerCase())) {
    return (
      <>
        <mark>{text.slice(0, prefix.length)}</mark>
        {text.slice(prefix.length)}
      </>
    );
  }
  return text;
}
