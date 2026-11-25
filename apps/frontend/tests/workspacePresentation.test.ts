// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { KnowledgeDocument, McpConnection } from "@agent-java/api-contracts";
import KnowledgeView from "../src/views/KnowledgeView.vue";
import McpView from "../src/views/McpView.vue";
import ChatView from "../src/views/ChatView.vue";
import KnowledgeDocumentList from "../src/components/KnowledgeDocumentList.vue";
import { useKnowledgeStore } from "../src/stores/knowledge";
import { useMcpStore } from "../src/stores/mcp";
import { useChatStore } from "../src/stores/chat";

beforeEach(() => setActivePinia(createPinia()));
afterEach(() => vi.restoreAllMocks());
async function routerFor(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: { template: "<div/>" } }] });
  await router.push(path);
  await router.isReady();
  return router;
}
function document(id: string, filename: string, indexStatus: KnowledgeDocument["indexStatus"]): KnowledgeDocument {
  return { id, filename, indexStatus, knowledgeBaseId: "kb", ownerUserId: "user", sizeBytes: 42, mimeType: "text/markdown", contentHash: "sha256:test", status: "ready", source: "upload", uploadedAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:00:00Z" };
}
function connection(id: string, name: string): McpConnection {
  return { id, name, ownerUserId: "user", url: `http://local.test/${id}`, enabled: true, transport: "sse", timeoutSeconds: 15, retries: 1, createdAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:00:00Z", lastCheck: null };
}
describe("workspace presentation interactions", () => {
  it("combines document search and status filtering without changing stored documents", async () => {
    const store = useKnowledgeStore();
    vi.spyOn(store, "initialize").mockResolvedValue();
    store.documents = [document("1", "database-sop.md", "indexed"), document("2", "database-review.md", "failed"), document("3", "cache-sop.md", "indexed")];
    const wrapper = mount(KnowledgeView, { global: { plugins: [await routerFor("/knowledge")] } });
    await flushPromises();
    await wrapper.get('[aria-label="搜索文档"]').setValue("DATABASE");
    expect(wrapper.getComponent(KnowledgeDocumentList).props("documents")).toHaveLength(2);
    await wrapper.get('[aria-label="筛选索引状态"]').setValue("failed");
    expect(wrapper.getComponent(KnowledgeDocumentList).props("documents")).toEqual([store.documents[1]]);
    await wrapper.get('[aria-label="搜索文档"]').setValue("missing");
    expect(wrapper.text()).toContain("没有匹配的文档");
    expect(store.documents).toHaveLength(3);
    wrapper.unmount();
  });

  it("searches connections and tools while preserving the selected connection and full descriptions", async () => {
    const store = useMcpStore();
    vi.spyOn(store, "initialize").mockResolvedValue();
    store.connections = [{ ...connection("sls", "SLS"), lastCheck: { ok: true, toolCount: 2, checkedAt: null, error: null, tools: [
      { name: "sls_query_logs", description: "日志查询说明".repeat(30), inputSchema: {}, serverName: "SLS" },
      { name: "QueryMetric", description: "指标查询", inputSchema: {}, serverName: "SLS" }
    ] } }, connection("other", "Other")];
    store.selectedId = "sls";
    const wrapper = mount(McpView);
    await flushPromises();
    await wrapper.get('[aria-label="搜索 MCP 连接"]').setValue("missing");
    expect(wrapper.text()).toContain("没有匹配的连接");
    expect(store.selectedId).toBe("sls");
    await wrapper.get('[aria-label="搜索工具"]').setValue("sls_query_logs");
    expect(wrapper.findAll("details")).toHaveLength(1);
    expect(wrapper.get("details p").text()).toBe("日志查询说明".repeat(30));
    expect(wrapper.text()).not.toContain("QueryMetric");
    expect(store.connections).toHaveLength(2);
    wrapper.unmount();
  });

  it("keeps the chosen prompt selection when switching settings or collapsing the inspector", async () => {
    const store = useChatStore();
    vi.spyOn(store, "initialize").mockResolvedValue();
    store.configuration = {
      prompts: [
        { id: "prompt_1", label: "默认提示词", content: "原始提示词", isDefault: true, createdAt: "2026-11-24T00:00:00Z", updatedAt: "2026-11-24T00:00:00Z" },
        { id: "prompt_2", label: "自定义提示词", content: "备用提示词", isDefault: false, createdAt: "2026-11-24T00:00:00Z", updatedAt: "2026-11-24T00:00:00Z" }
      ],
      skills: [],
      selection: { systemPromptId: "prompt_1", skillIds: [], updatedAt: "2026-11-24T00:00:00Z" }
    };
    const wrapper = mount(ChatView, { global: { plugins: [await routerFor("/chat")] } });
    await flushPromises();
    const radios = () => wrapper.findAll('input[type="radio"]');
    expect((radios()[0]!.element as HTMLInputElement).checked).toBe(true);
    await radios()[1]?.setValue(true);
    await wrapper.get('.chat-view__tabs button:nth-child(2)').trigger("click");
    await wrapper.get('[aria-controls="chat-settings"]').trigger("click");
    await wrapper.get('[aria-controls="chat-settings"]').trigger("click");
    await wrapper.get('.chat-view__tabs button:first-child').trigger("click");
    expect((radios()[1]!.element as HTMLInputElement).checked).toBe(true);
    wrapper.unmount();
  });
});
