// @vitest-environment jsdom

import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it, vi } from "vitest";

import WorkspaceLayout from "../src/layouts/WorkspaceLayout.vue";
import { createRuntimeHealthClient } from "../src/runtimeHealth";

describe("runtime health", () => {
  it("reads lightweight backend health through the shared response transport", async () => {
    const client = createRuntimeHealthClient({
      baseUrl: "http://api.test",
      fetchImpl: vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ ok: true, data: { service: "super-ai-backend", status: "ok", version: "0.1.0" } }), { status: 200 })),
      getAccessToken: () => null
    });

    await expect(client.health()).resolves.toMatchObject({ status: "ok" });
  });

  it("renders a degraded header state after lightweight health fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("backend unavailable")));
    const pinia = createPinia();
    setActivePinia(pinia);
    const router = createRouter({ history: createMemoryHistory(), routes: ["/", "/chat", "/knowledge", "/aiops", "/mcp"].map(path => ({ path, component: { template: "<div />" } })) });
    await router.push("/");
    await router.isReady();
    const wrapper = mount(WorkspaceLayout, {
      global: { plugins: [pinia, router], stubs: { RouterView: true } }
    });

    expect(wrapper.text()).toContain("检测服务中");
    await flushPromises();
    expect(wrapper.text()).toContain("服务连接异常");
    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
