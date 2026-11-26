import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // changeOrigin: false keeps the browser's Host header (localhost:5173). Spring builds the
      // OAuth redirect URI from it, and Google only accepts the registered :5173 callback.
      // (The string shorthand "/api": "http://..." silently sets changeOrigin: true.)
      "/api": { target: "http://localhost:8080", changeOrigin: false },
    },
  },
});
