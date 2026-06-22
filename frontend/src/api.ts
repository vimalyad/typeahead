import type { Suggestion } from "./types";

/**
 * Fetch suggestions for a prefix. The caller passes an AbortSignal so an in-flight request
 * is cancelled when a newer keystroke supersedes it — without that, responses can land
 * out of order and a stale result overwrites a fresh one.
 */
export async function fetchSuggestions(
  prefix: string,
  signal: AbortSignal,
): Promise<Suggestion[]> {
  const res = await fetch(`/api/suggest?q=${encodeURIComponent(prefix)}`, { signal });
  if (!res.ok) {
    throw new Error(`suggest failed: ${res.status}`);
  }
  return (await res.json()) as Suggestion[];
}

export async function fetchTrending(signal?: AbortSignal): Promise<Suggestion[]> {
  const res = await fetch("/api/trending", { signal });
  if (!res.ok) {
    throw new Error(`trending failed: ${res.status}`);
  }
  return (await res.json()) as Suggestion[];
}

export interface CacheDebug {
  prefix: string;
  generation: number;
  key: string;
  ownerNode: string;
  status: "HIT" | "MISS";
}

/** Which Redis node owns this prefix on the consistent-hash ring, and whether it's cached. */
export async function fetchCacheDebug(prefix: string, signal?: AbortSignal): Promise<CacheDebug> {
  const res = await fetch(`/api/cache/debug?prefix=${encodeURIComponent(prefix)}`, { signal });
  if (!res.ok) {
    throw new Error(`cache debug failed: ${res.status}`);
  }
  return (await res.json()) as CacheDebug;
}

export async function submitSearch(query: string): Promise<string> {
  const res = await fetch("/api/search", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query }),
  });
  if (!res.ok) {
    throw new Error(`search failed: ${res.status}`);
  }
  const body = (await res.json()) as { message?: string };
  return body.message ?? "Searched";
}
