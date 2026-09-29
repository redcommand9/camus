import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";
export default defineConfig({
  plugins: [react()],
  base: "/",
  resolve: { alias: { "@": path.resolve(__dirname) } },
  build: { outDir: "../composeApp/src/androidMain/assets/web", emptyOutDir: true, target: "es2022" },
});
