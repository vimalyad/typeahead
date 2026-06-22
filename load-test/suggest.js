import http from "k6/http";
import { check } from "k6";
import { Rate, Trend, Counter } from "k6/metrics";
import { SharedArray } from "k6/data";

// Single origin by default (nginx). Override to hit a service directly, e.g.
//   BASE_URL=http://host.docker.internal:8081  (suggestion-service)
// When k6 runs in Docker against the host stack, use host.docker.internal.
const BASE = __ENV.BASE_URL || "http://localhost:8080";
const SEARCH_RATIO = Number(__ENV.SEARCH_RATIO || "0.1"); // share of iterations that also POST /search
const TARGET_VUS = Number(__ENV.VUS || "200");

// Build the inverse-CDF table once in init; SharedArray keeps a single copy across VUs.
const prefixCdf = new SharedArray("prefixes", () => {
  const rows = JSON.parse(open("./prefixes.json"));
  let total = 0;
  for (const r of rows) total += r.w;
  let acc = 0;
  return rows.map((r) => {
    acc += r.w;
    return { p: r.p, cdf: acc / total };
  });
});

const cacheHit = new Rate("cache_hit");
const hitLatency = new Trend("suggest_hit_ms", true);
const missLatency = new Trend("suggest_miss_ms", true);
const searches = new Counter("searches_submitted");

// Inverse-CDF sample: a uniform draw lands in a prefix's interval in proportion to its
// weight, so hot prefixes are picked far more often — the Zipfian traffic shape.
function pickPrefix() {
  const x = Math.random();
  let lo = 0;
  let hi = prefixCdf.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (prefixCdf[mid].cdf < x) lo = mid + 1;
    else hi = mid;
  }
  return prefixCdf[lo].p;
}

export const options = {
  scenarios: {
    typeahead: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "30s", target: TARGET_VUS },
        { duration: "2m", target: TARGET_VUS },
        { duration: "30s", target: 0 },
      ],
    },
  },
  thresholds: {
    // Targets from IMPLEMENTATION §5.3 (measure against the service for the tightest read).
    "http_req_duration{kind:suggest}": ["p(95)<25", "p(99)<60"],
    cache_hit: ["rate>0.80"],
    checks: ["rate>0.99"],
  },
};

export default function () {
  const p = pickPrefix();
  const res = http.get(`${BASE}/api/suggest?q=${encodeURIComponent(p)}`, {
    tags: { kind: "suggest" },
  });
  const hit = res.headers["X-Cache"] === "HIT";
  cacheHit.add(hit);
  (hit ? hitLatency : missLatency).add(res.timings.duration);
  check(res, { "suggest 200": (r) => r.status === 200 });

  if (Math.random() < SEARCH_RATIO) {
    const sres = http.post(`${BASE}/api/search`, JSON.stringify({ query: p }), {
      headers: { "Content-Type": "application/json" },
      tags: { kind: "search" },
    });
    searches.add(1);
    check(sres, { "search 200": (r) => r.status === 200 });
  }
}
