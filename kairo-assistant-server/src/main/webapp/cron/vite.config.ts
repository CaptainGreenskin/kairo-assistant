import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// Build into the Maven target/classes/static/cron/ so Spring auto-serves at /cron/.
// During `npm run dev` we still proxy /api to localhost:8080 for convenience.
export default defineConfig({
  plugins: [react()],
  base: "/cron/",
  build: {
    outDir: path.resolve(__dirname, "../../../../target/classes/static/cron"),
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
