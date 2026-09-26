/// <reference types="vitest" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
  build: {
    rollupOptions: {
      output: {
        /**
         * Splits the two heavy dependencies out of the app bundle. Recharts alone
         * is well over half the previous single 650 kB chunk, and it is only
         * needed by the dashboard - keeping it separate means the login and
         * table screens are not paying for it, and it stays cached across
         * app-code deploys.
         */
        manualChunks: {
          charts: ["recharts"],
          vendor: ["react", "react-dom", "react-router-dom", "axios", "zustand"],
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: "./src/test/setup.ts",
  },
});
