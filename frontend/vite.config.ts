/// <reference types="vitest/config" />
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

const BACKEND_ORIGIN = process.env.VITE_BACKEND_ORIGIN ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  build: {
    // The bundle ships inside the backend jar; a source map would leak original source to anyone
    // with the jar and buys nothing in production, where nobody attaches a debugger to it.
    sourcemap: false,
  },
  server: {
    proxy: {
      "/api": BACKEND_ORIGIN,
      "/health": BACKEND_ORIGIN,
      "/version": BACKEND_ORIGIN,
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
  },
});
