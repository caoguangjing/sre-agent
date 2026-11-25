import { readFileSync } from "node:fs";
import { fileURLToPath, URL } from "node:url";

import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

export default defineConfig(({ mode }) => {
  // Test runs only need deterministic public settings.
  let publicConfig = { title: "智能运维平台", apiBaseUrl: "http://127.0.0.1:8000" };
  if (mode !== "test") {
    const base = JSON.parse(readFileSync(new URL("../../config/project.json", import.meta.url), "utf8"));
    const user = JSON.parse(readFileSync(new URL("../../config/user.project.json", import.meta.url), "utf8"));
    publicConfig = {
      title: user.frontend?.title ?? base.frontend.title,
      apiBaseUrl: user.frontend?.apiBaseUrl ?? base.frontend.apiBaseUrl
    };
  }

  return {
    define: { __PUBLIC_CONFIG__: JSON.stringify(publicConfig) },
    plugins: [vue()],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
        "@agent-java/api-contracts": fileURLToPath(
          new URL("../../packages/api-contracts/src/index.ts", import.meta.url)
        )
      }
    },
    test: {
      include: ["tests/**/*.test.ts"]
    }
  };
});
