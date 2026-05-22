import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// Build into target/classes/static/ so Spring's WelcomePageHandlerMapping
// auto-serves the SPA at the root URL "/". The Java side adds a SPA
// fallback (ConsoleWebUiConfig) that forwards every non-asset / non-API
// path to /index.html so React Router's deep links work.
//
// During `npm run dev` we proxy /api to localhost:8089 for HMR convenience.
export default defineConfig({
  plugins: [react()],
  base: "/",
  build: {
    outDir: path.resolve(__dirname, "../../../../target/classes/static"),
    emptyOutDir: false,
    sourcemap: false,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8089",
        changeOrigin: true,
      },
    },
  },
});
