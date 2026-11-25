<script setup lang="ts">
import { Cable, CheckCircle2, ChevronDown, Plus, RefreshCw, Save, Search, Server, Trash2, TriangleAlert, Wrench } from "lucide-vue-next";
import { computed, onMounted, reactive, ref, watch } from "vue";

import type { McpConnectionMutationRequest, McpTransport } from "@agent-java/api-contracts";

import AppLoadingState from "../components/AppLoadingState.vue";
import { useMcpStore } from "../stores/mcp";

const mcp = useMcpStore();
const draft = reactive<McpConnectionMutationRequest>({
  name: "",
  transport: "sse",
  url: "",
  enabled: true,
  timeoutSeconds: 15,
  retries: 1
});
const search = ref("");
const toolSearch = ref("");
const headersText = ref("");
const headersError = ref("");
const selectedHeaderCount = computed(() => Object.keys(mcp.selected?.headers ?? {}).length);
const filteredTools = computed(() => (mcp.selected?.lastCheck?.tools ?? []).filter(tool =>
  `${tool.name} ${tool.description}`.toLowerCase().includes(toolSearch.value.trim().toLowerCase())
));
const filteredConnections = computed(() => mcp.connections.filter(connection =>
  `${connection.name} ${connection.url}`.toLowerCase().includes(search.value.trim().toLowerCase())
));
const enabledCount = computed(() => mcp.connections.filter(connection => connection.enabled).length);
const healthyCount = computed(() => mcp.connections.filter(connection => connection.lastCheck?.ok).length);
const isNew = computed(() => mcp.selected === null);

watch(() => mcp.selected, (connection) => {
  toolSearch.value = "";
  headersError.value = "";
  headersText.value = Object.entries(connection?.headers ?? {})
    .map(([key, value]) => `${key}: ${value}`)
    .join("\n");
  if (connection === null) return;
  Object.assign(draft, {
    name: connection.name,
    transport: connection.transport,
    url: connection.url,
    enabled: connection.enabled,
    timeoutSeconds: connection.timeoutSeconds,
    retries: connection.retries
  });
}, { immediate: true });

onMounted(() => { void mcp.initialize().catch(() => undefined); });

function newConnection(): void {
  mcp.select("");
  headersText.value = "";
  Object.assign(draft, {
    name: "",
    transport: "sse" as McpTransport,
    url: "http://127.0.0.1:3000/sse",
    enabled: true,
    timeoutSeconds: 15,
    retries: 1
  });
}

function parseHeadersText(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const idx = trimmed.indexOf(":");
    const key = idx > 0 ? trimmed.slice(0, idx).trim() : "";
    const value = idx > 0 ? trimmed.slice(idx + 1).trim() : "";
    if (!key || !value) throw new Error(`自定义 Header 需为「名称: 值」格式：${trimmed}`);
    out[key] = value;
  }
  return out;
}

function submit(): void {
  headersError.value = "";
  let headers: Record<string, string>;
  try {
    headers = parseHeadersText(headersText.value);
  } catch (error) {
    headersError.value = (error as Error).message;
    return;
  }
  Object.assign(draft, { headers });
  run(() => (isNew.value ? mcp.create(draft) : mcp.update(draft)));
}

function run(operation: () => Promise<void>): void {
  void operation().catch(() => undefined);
}
</script>

<template>
  <section class="mcp-view" aria-label="MCP 连接管理">
    <header class="mcp-view__header ui-page-heading">
      <div><h2>连接你的工具生态。</h2><p>管理 MCP 服务，让对话与诊断拥有执行能力。</p></div>
      <button class="ui-button ui-button--primary" type="button" title="新建连接" @click="newConnection"><Plus :size="16" aria-hidden="true" />新建连接</button>
    </header>
    <div class="mcp-view__stats">
      <div><span class="mcp-view__stat-icon"><Server :size="19" aria-hidden="true" /></span><span><small>用户连接</small><strong>{{ mcp.connections.length }}<em>个</em></strong></span></div>
      <div><span class="mcp-view__stat-icon"><Cable :size="19" aria-hidden="true" /></span><span><small>已启用</small><strong>{{ enabledCount }}<em>个</em></strong></span></div>
      <div><span class="mcp-view__stat-icon mcp-view__stat-icon--success"><CheckCircle2 :size="19" aria-hidden="true" /></span><span><small>最近检查正常</small><strong>{{ healthyCount }}<em>个</em></strong></span></div>
    </div>
    <AppLoadingState v-if="mcp.isLoading" label="正在加载 MCP 连接" />
    <div v-else class="mcp-view__workspace">
      <aside class="mcp-view__list" aria-label="MCP 连接列表">
        <div class="mcp-view__list-heading"><h3>连接列表</h3><span>{{ mcp.connections.length }}</span></div>
        <label class="ui-search"><Search :size="15" aria-hidden="true" /><input v-model="search" aria-label="搜索 MCP 连接" placeholder="搜索名称或地址" /></label>
        <button v-for="connection in filteredConnections" :key="connection.id" type="button" :class="{ 'mcp-view__connection--active': mcp.selectedId === connection.id }" @click="mcp.select(connection.id)">
          <span class="mcp-view__connection-icon"><Server :size="16" aria-hidden="true" /></span>
          <span><strong>{{ connection.name }}</strong><small>{{ connection.transport === 'sse' ? 'SSE' : 'Streamable HTTP' }} · {{ connection.enabled ? '已启用' : '已停用' }}</small></span>
          <CheckCircle2 v-if="connection.lastCheck?.ok" :size="15" class="mcp-view__ok" aria-label="连接正常" />
          <TriangleAlert v-else-if="connection.lastCheck" :size="15" class="mcp-view__bad" aria-label="连接异常" />
        </button>
        <p v-if="filteredConnections.length === 0" class="mcp-view__empty">{{ search ? '没有匹配的连接。' : '还没有连接，点击「新建连接」添加服务。' }}</p>
      </aside>

      <main class="mcp-view__editor">
        <form @submit.prevent="submit">
          <header><div><p>{{ isNew ? '新建连接' : '连接配置' }}</p><h3>{{ draft.name || '未命名 MCP Server' }}</h3></div><label class="mcp-view__toggle"><input v-model="draft.enabled" type="checkbox" /><span>启用</span></label></header>
          <div class="mcp-view__fields">
            <label><span>连接名称</span><input v-model.trim="draft.name" required maxlength="120" /></label>
            <label><span>传输协议</span><select v-model="draft.transport"><option value="sse">SSE</option><option value="streamable_http">Streamable HTTP</option></select></label>
            <label class="mcp-view__url"><span>服务地址</span><input v-model.trim="draft.url" type="url" required maxlength="2048" /></label>
            <label><span>超时（秒）</span><input v-model.number="draft.timeoutSeconds" type="number" min="1" max="300" required /></label>
            <label><span>重试次数</span><input v-model.number="draft.retries" type="number" min="0" max="5" required /></label>
            <label class="mcp-view__headers"><span>自定义 Header（每行一条：名称: 值）</span>
              <textarea v-model="headersText" rows="3" spellcheck="false" aria-label="自定义 Header" placeholder="Authorization: Bearer &lt;token&gt;"></textarea>
              <small v-if="headersError" class="mcp-view__headers-error">{{ headersError }}</small>
              <small v-else-if="selectedHeaderCount > 0">已配置 {{ selectedHeaderCount }} 个自定义 Header，值为掩码显示；重新输入完整值才会覆盖，清空该行即删除。</small>
            </label>
          </div>
          <div class="mcp-view__commands">
            <button v-if="mcp.selected" type="button" :disabled="mcp.checkingId === mcp.selected.id" @click="run(() => mcp.check(mcp.selected!.id))"><RefreshCw :size="15" :class="{ spin: mcp.checkingId === mcp.selected.id }" aria-hidden="true" />{{ mcp.checkingId === mcp.selected.id ? '检查中' : '检查连接' }}</button>
            <button v-if="mcp.selected" type="button" class="mcp-view__delete" :disabled="mcp.isSaving" @click="run(() => mcp.remove(mcp.selected!.id))"><Trash2 :size="15" aria-hidden="true" />删除</button>
            <button type="submit" class="mcp-view__save" :disabled="mcp.isSaving"><Save :size="15" aria-hidden="true" />{{ mcp.isSaving ? '保存中' : '保存连接' }}</button>
          </div>
        </form>

        <section class="mcp-view__inspection" aria-label="连接检查结果">
          <header><div><p>工具发现</p><h3>可用工具</h3></div><Cable :size="18" aria-hidden="true" /></header>
          <div v-if="mcp.selected?.lastCheck" class="mcp-view__check-summary" :class="{ 'mcp-view__check-summary--failed': !mcp.selected.lastCheck.ok }">
            <CheckCircle2 v-if="mcp.selected.lastCheck.ok" :size="17" aria-hidden="true" /><TriangleAlert v-else :size="17" aria-hidden="true" />
            <span><strong>{{ mcp.selected.lastCheck.ok ? '连接正常' : '连接异常' }}</strong><small>{{ mcp.selected.lastCheck.ok ? `发现 ${mcp.selected.lastCheck.toolCount} 个工具` : mcp.selected.lastCheck.error }}</small></span>
          </div>
          <template v-if="mcp.selected?.lastCheck?.tools.length"><label class="ui-search mcp-view__tool-search"><Search :size="15" aria-hidden="true" /><input v-model="toolSearch" aria-label="搜索工具" placeholder="搜索工具名称或说明" /></label><ul><li v-for="tool in filteredTools" :key="tool.name"><Wrench :size="15" aria-hidden="true" /><details><summary><strong>{{ tool.name }}</strong><ChevronDown :size="14" aria-hidden="true" /><small>{{ tool.description.slice(0, 120) }}{{ tool.description.length > 120 ? '…' : '' }}</small></summary><p>{{ tool.description }}</p></details></li></ul><p v-if="filteredTools.length === 0" class="mcp-view__empty">没有匹配的工具。</p></template>
          <p v-else class="mcp-view__empty">保存连接后执行检查，这里会展示服务返回的工具与说明。</p>
        </section>
      </main>
    </div>
  </section>
</template>

<style scoped>
.mcp-view { display: grid; gap: 1.3rem; grid-template-rows: auto auto minmax(0, 1fr); height: 100%; min-height: 0; overflow: hidden; padding: 1.8rem 1.6rem 1.6rem; }
.mcp-view__stats { display: grid; gap: 1rem; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.mcp-view__stats > div { align-items: center; background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-md); display: flex; gap: 0.85rem; padding: 1rem 1.1rem; }
.mcp-view__stat-icon { align-items: center; background: var(--accent-soft); border-radius: 0.7rem; color: var(--accent); display: flex; height: 2.6rem; justify-content: center; width: 2.6rem; }
.mcp-view__stat-icon--success { background: var(--status-success-bg); color: var(--success); }
.mcp-view__stats small { color: var(--text-secondary); display: block; font-size: 0.78rem; }
.mcp-view__stats strong { display: block; font-size: 1.45rem; font-weight: 600; margin-top: 0.1rem; }
.mcp-view__stats em { color: var(--text-tertiary); font-size: 0.78rem; font-style: normal; font-weight: 400; margin-left: 0.5rem; }
.mcp-view__workspace { display: grid; gap: 1rem; grid-template-columns: 16rem minmax(0, 1fr); min-height: 0; }
.mcp-view__list { align-content: start; background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-md); display: grid; gap: 0.45rem; overflow-y: auto; padding: 1rem 0.7rem; }
.mcp-view__list-heading { align-items: center; display: flex; justify-content: space-between; padding: 0 0.25rem 0.45rem; }
.mcp-view__list-heading h3 { font-size: 0.82rem; margin: 0; }.mcp-view__list-heading > span { color: var(--text-tertiary); font-size: 0.78rem; }
.mcp-view__list .ui-search { margin-bottom: 0.5rem; }
.mcp-view__list > button { align-items: center; border: 1px solid transparent; border-radius: 0.6rem; display: grid; gap: 0.65rem; grid-template-columns: auto minmax(0, 1fr) auto; padding: 0.75rem 0.55rem; text-align: left; }
.mcp-view__list > button:hover { background: var(--surface-hover); }.mcp-view__list > .mcp-view__connection--active { background: var(--accent-soft); border-color: var(--accent-border); }
.mcp-view__connection-icon { align-items: center; background: var(--surface-raised); border: 1px solid var(--line); border-radius: 0.6rem; color: var(--accent); display: inline-flex; height: 2.1rem; justify-content: center; width: 2.1rem; }
.mcp-view__list strong, .mcp-view__list small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcp-view__list strong { font-size: 0.78rem; }.mcp-view__list small { color: var(--text-tertiary); font-size: 0.72rem; margin-top: 0.3rem; }.mcp-view__ok { color: var(--status-success-text); }.mcp-view__bad { color: var(--status-danger-text); }
.mcp-view__editor { display: grid; gap: 1rem; grid-template-columns: minmax(0, 1fr) minmax(15rem, 0.75fr); min-height: 0; overflow: hidden; }
.mcp-view__editor > form, .mcp-view__inspection { background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-md); min-width: 0; overflow-y: auto; padding: 1.35rem; }
.mcp-view__editor header { align-items: center; display: flex; gap: 0.7rem; justify-content: space-between; }
.mcp-view__editor header p { color: var(--text-tertiary); font-size: 0.72rem; margin: 0 0 0.4rem; }.mcp-view__editor h3 { font-size: 1rem; margin: 0; overflow-wrap: anywhere; }
.mcp-view__toggle { align-items: center; background: var(--surface); border-radius: 99px; display: flex; font-size: 0.78rem; gap: 0.4rem; padding: 0.45rem 0.65rem; white-space: nowrap; }.mcp-view__toggle input { height: 0.9rem; width: 0.9rem; }
.mcp-view__fields { display: grid; gap: 1.1rem 0.8rem; grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: 1.8rem; }.mcp-view__fields label { color: var(--text-secondary); display: grid; font-size: 0.78rem; font-weight: 600; gap: 0.5rem; }.mcp-view__url { grid-column: 1 / -1; }
.mcp-view__fields input, .mcp-view__fields select, .mcp-view__fields textarea { background: var(--surface); border: 1px solid var(--line-strong); border-radius: 0.5rem; color: var(--text-primary); font: inherit; min-height: 2.7rem; min-width: 0; padding: 0 0.65rem; width: 100%; }
.mcp-view__fields textarea { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.78rem; line-height: 1.6; padding: 0.55rem 0.65rem; resize: vertical; }
.mcp-view__headers { grid-column: 1 / -1; }
.mcp-view__headers small { font-weight: 400; }
.mcp-view__headers-error { color: var(--status-danger-text); }
.mcp-view__commands { border-top: 1px solid var(--line); display: flex; flex-wrap: wrap; gap: 0.55rem; justify-content: flex-end; margin-top: 1.8rem; padding-top: 1.25rem; }.mcp-view__commands button { align-items: center; border: 1px solid var(--line-strong); border-radius: 0.5rem; display: inline-flex; font-size: 0.78rem; gap: 0.4rem; min-height: 2.35rem; padding: 0 0.75rem; }.mcp-view__save { background: var(--accent); border-color: var(--accent) !important; color: white; }.mcp-view__delete { color: var(--status-danger-text); margin-right: auto; }
.mcp-view__check-summary { align-items: center; background: var(--status-success-bg); border: 1px solid var(--status-success-border); border-radius: 0.65rem; color: var(--status-success-text); display: flex; gap: 0.65rem; margin-top: 1.4rem; padding: 0.9rem; }.mcp-view__check-summary--failed { background: var(--status-danger-bg); border-color: var(--status-danger-border); color: var(--status-danger-text); }.mcp-view__check-summary strong, .mcp-view__check-summary small { display: block; }.mcp-view__check-summary strong { font-size: 0.82rem; }.mcp-view__check-summary small { font-size: 0.78rem; margin-top: 0.3rem; overflow-wrap: anywhere; }
.mcp-view__inspection ul { display: grid; gap: 0.6rem; list-style: none; margin: 1rem 0 0; padding: 0; }.mcp-view__inspection li { align-items: start; background: var(--surface); border: 1px solid var(--line); border-radius: 0.65rem; display: grid; gap: 0.65rem; grid-template-columns: auto minmax(0, 1fr); padding: 0.85rem; }.mcp-view__inspection li > svg { color: var(--accent); margin-top: 0.15rem; }.mcp-view__inspection li strong, .mcp-view__inspection li small { display: block; overflow-wrap: anywhere; }.mcp-view__inspection li strong { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.78rem; }.mcp-view__inspection li small, .mcp-view__empty { color: var(--text-secondary); font-size: 0.72rem; line-height: 1.65; margin-top: 0.35rem; }
.mcp-view__tool-search { margin-top: 1rem; }.mcp-view__inspection details { min-width: 0; }.mcp-view__inspection summary { cursor: pointer; display: grid; gap: 0.45rem; grid-template-columns: minmax(0, 1fr) auto; list-style: none; }.mcp-view__inspection summary::-webkit-details-marker { display: none; }.mcp-view__inspection summary svg { color: var(--text-tertiary); }.mcp-view__inspection details[open] summary svg { transform: rotate(180deg); }.mcp-view__inspection summary small { display: -webkit-box; grid-column: 1 / -1; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }.mcp-view__inspection details[open] summary small { display: none; }.mcp-view__inspection details p { border-top: 1px solid var(--line); color: var(--text-secondary); font-size: 0.72rem; line-height: 1.7; margin: 0.6rem 0 0; overflow-wrap: anywhere; padding-top: 0.65rem; white-space: pre-wrap; }
.mcp-view__empty { background: var(--surface); border: 1px dashed var(--line-strong); border-radius: 0.65rem; padding: 1.2rem; }
.spin { animation: spin 0.8s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 1200px) { .mcp-view__workspace { grid-template-columns: 14rem minmax(0, 1fr); }.mcp-view__editor { grid-template-columns: minmax(0, 1fr); overflow-y: auto; }.mcp-view__editor > form, .mcp-view__inspection { overflow: visible; } }
@media (max-width: 760px) { .mcp-view { display: flex; flex-direction: column; gap: 1rem; overflow-y: auto; padding: 1.25rem 1rem; }.mcp-view__header { align-items: flex-start; flex-wrap: wrap; }.mcp-view__stats { gap: 0.5rem; }.mcp-view__stats > div { gap: 0.5rem; padding: 0.8rem 0.6rem; }.mcp-view__stat-icon { display: none; }.mcp-view__stats small { font-size: 0.72rem; }.mcp-view__stats strong { font-size: 1.25rem; }.mcp-view__workspace { display: flex; flex-direction: column; flex: 0 0 auto; }.mcp-view__list { max-height: 15rem; }.mcp-view__editor { display: flex; flex-direction: column; overflow: visible; }.mcp-view__fields { grid-template-columns: minmax(0, 1fr); }.mcp-view__url { grid-column: auto; } }
</style>
