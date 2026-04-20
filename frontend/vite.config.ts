import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      "/api": {
        target: "http://172.29.75.228:8080",
        changeOrigin: true,
      },
    },
  },
});
