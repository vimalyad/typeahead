import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In dev the two backends run on separate ports; mirror the nginx path routing so the
// browser sees a single origin and there is no CORS. Override targets via env when the
// services run elsewhere (e.g. inside compose).
const suggestTarget = process.env.SUGGEST_TARGET ?? "http://localhost:8081";
const searchTarget = process.env.SEARCH_TARGET ?? "http://localhost:8082";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api/suggest": suggestTarget,
      "/api/trending": suggestTarget,
      "/api/search": searchTarget,
    },
  },
});
