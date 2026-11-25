<script setup lang="ts">
import { AlertTriangle, CheckCheck, Database, FileText, FileWarning, Search, Upload } from "lucide-vue-next";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from "vue-router";

import type { KnowledgeDocument } from "@agent-java/api-contracts";

import AppEmptyState from "../components/AppEmptyState.vue";
import AppErrorState from "../components/AppErrorState.vue";
import AppLoadingState from "../components/AppLoadingState.vue";
import KnowledgeDocumentList from "../components/KnowledgeDocumentList.vue";
import KnowledgeUpload from "../components/KnowledgeUpload.vue";
import { useKnowledgeStore } from "../stores/knowledge";

const knowledge = useKnowledgeStore();
const route = useRoute();
const search = ref("");
const statusFilter = ref("all");
const uploadOpen = ref(false);
function documentStatus(document: KnowledgeDocument): string { return knowledge.indexTasks.find(task => task.documentId === document.id)?.status ?? document.indexStatus; }
const indexedCount = computed(() => knowledge.documents.filter(document => document.indexStatus === "indexed").length);
const processingCount = computed(() => knowledge.documents.filter(document => ["pending", "running", "accepted", "indexing"].includes(documentStatus(document))).length);
const filteredDocuments = computed(() => knowledge.documents.filter(document => {
  const matchesSearch = document.filename.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase());
  const state = documentStatus(document);
  const matchesStatus = statusFilter.value === "all" || (statusFilter.value === "indexed" ? document.indexStatus === "indexed" : statusFilter.value === "processing" ? ["pending", "running", "accepted", "indexing"].includes(state) : ["failed", "cancelled"].includes(state));
  return matchesSearch && matchesStatus;
}));
const pendingDeletion = ref<KnowledgeDocument | null>(null);

onMounted(() => { void initializeFromCitation().catch(() => undefined); });
onBeforeUnmount(() => { knowledge.stopPolling(); });

function run(operation: () => Promise<unknown>): void { void operation().catch(() => undefined); }
function openDocument(document: KnowledgeDocument): void { run(() => knowledge.openDocument(document)); }

async function initializeFromCitation(): Promise<void> {
  await knowledge.initialize();
  const knowledgeBaseId = route.query.knowledgeBaseId;
  if (typeof knowledgeBaseId === "string" && knowledgeBaseId !== knowledge.selectedKnowledgeBaseId) {
    await knowledge.selectKnowledgeBase(knowledgeBaseId);
  }
  const documentId = route.query.documentId;
  if (typeof documentId !== "string") return;
  const document = knowledge.documents.find((item) => item.id === documentId);
  if (document !== undefined) await knowledge.openDocument(document);
}

function confirmDelete(): void {
  const document = pendingDeletion.value;
  if (document === null) return;
  run(async () => {
    await knowledge.deleteDocument(document);
    pendingDeletion.value = null;
  });
}

function uploadDocument(file: File, chunking: import("@agent-java/api-contracts").DocumentChunkingConfiguration): void {
  run(() => knowledge.upload(file, chunking));
}
</script>

<template>
  <section class="knowledge-view" aria-label="知识库工作区">
    <header class="knowledge-view__header ui-page-heading">
      <div><h2>让每一份知识，成为可靠依据。</h2><span>管理运行手册、SOP 与复盘记录，构建你的知识空间。</span></div><button type="button" class="ui-button ui-button--primary" aria-label="上传文档" :aria-expanded="uploadOpen || knowledge.documents.length === 0" aria-controls="knowledge-upload-panel" @click="uploadOpen = !uploadOpen"><Upload :size="15" aria-hidden="true" />上传文档</button>
      <label v-if="knowledge.knowledgeBases.length > 1" class="knowledge-view__base"><span>当前知识库</span><select :value="knowledge.selectedKnowledgeBaseId ?? ''" :disabled="knowledge.isLoading" aria-label="当前知识库" @change="run(() => knowledge.selectKnowledgeBase(($event.target as HTMLSelectElement).value))"><option v-for="base in knowledge.knowledgeBases" :key="base.id" :value="base.id">{{ base.name }}</option></select></label>
    </header>

    <AppLoadingState v-if="knowledge.isLoading && knowledge.documents.length === 0" label="正在加载知识库" />
    <AppErrorState v-else-if="knowledge.errorMessage && knowledge.documents.length === 0" :can-retry="true" :message="knowledge.errorMessage" @retry="run(knowledge.initialize)" />
    <template v-else>
      <div class="knowledge-view__stats"><article><span class="knowledge-view__stat-icon"><FileText :size="20" aria-hidden="true" /></span><div><span>知识文档</span><strong>{{ knowledge.documents.length }}<small>份</small></strong></div><p>你的知识资产</p></article><article><span class="knowledge-view__stat-icon knowledge-view__stat-icon--green"><CheckCheck :size="20" aria-hidden="true" /></span><div><span>已完成索引</span><strong>{{ indexedCount }}<small>份</small></strong></div><p>可用于知识检索</p></article><article><span class="knowledge-view__stat-icon knowledge-view__stat-icon--amber"><Database :size="20" aria-hidden="true" /></span><div><span>正在处理</span><strong>{{ processingCount }}<small>份</small></strong></div><p>排队与索引任务</p></article></div>
      <div v-show="uploadOpen || knowledge.documents.length === 0" id="knowledge-upload-panel" class="knowledge-view__upload"><KnowledgeUpload :disabled="knowledge.isUploading || knowledge.selectedKnowledgeBaseId === null" :is-uploading="knowledge.isUploading" @upload="uploadDocument" /></div>
      <section v-if="knowledge.pendingOverwriteFile" class="knowledge-view__confirmation" role="alert"><FileWarning :size="18" aria-hidden="true" /><div><strong>发现同名文档</strong><p>{{ knowledge.pendingOverwriteFile.name }} 会替换现有文档及其向量数据。</p></div><div><button type="button" @click="knowledge.clearPendingOverwrite">取消</button><button type="button" class="knowledge-view__danger-command" @click="run(knowledge.overwritePendingUpload)">确认替换</button></div></section>
      <div class="knowledge-view__body">
        <section class="knowledge-view__documents" aria-labelledby="knowledge-documents-heading">
          <header><div><h3 id="knowledge-documents-heading">全部文档<span>{{ knowledge.documents.length }}</span></h3><p>上传、索引与来源管理</p></div><div class="knowledge-view__filters"><label class="ui-search"><Search :size="15" aria-hidden="true" /><input v-model="search" type="search" aria-label="搜索文档" placeholder="搜索文档名称…" /></label><select v-model="statusFilter" aria-label="筛选索引状态"><option value="all">全部状态</option><option value="indexed">已索引</option><option value="processing">处理中</option><option value="failed">失败或取消</option></select></div></header>
          <AppEmptyState v-if="knowledge.documents.length === 0" title="还没有文档" detail="上传一份 SOP、故障复盘或运行手册，让它在对话和诊断中成为可信来源。" />
          <AppEmptyState v-else-if="filteredDocuments.length === 0" title="没有匹配的文档" detail="试试其他文档名称，或切换索引状态筛选。" />
          <KnowledgeDocumentList v-else :documents="filteredDocuments" :index-tasks="knowledge.indexTasks" :preview="knowledge.chunkPreview" :selected-document="knowledge.selectedDocument" @close="knowledge.closeDocument" @delete="pendingDeletion = $event" @detail="openDocument" @rebuild="run(() => knowledge.rebuildDocumentIndex($event))" @retry="run(() => knowledge.retryIndexTask($event))" />
        </section>
      </div>
    </template>

    <section v-if="pendingDeletion" class="knowledge-view__confirmation knowledge-view__confirmation--delete" role="alertdialog" aria-label="确认删除文档"><AlertTriangle :size="18" aria-hidden="true" /><div><strong>删除 {{ pendingDeletion.filename }}？</strong><p>此操作会删除文档记录和已建立的向量索引。</p></div><div><button type="button" @click="pendingDeletion = null">取消</button><button type="button" class="knowledge-view__danger-command" @click="confirmDelete">确认删除</button></div></section>
  </section>
</template>

<style scoped>
.knowledge-view { background: var(--canvas); box-sizing: border-box; display: flex; flex-direction: column; gap: 1.25rem; height: 100%; min-height: 0; overflow: hidden; padding: 1.8rem 2rem; }
.knowledge-view__header { flex-shrink: 0; position: relative; }.knowledge-view__header > div > span { color: var(--text-secondary); display: block; font-size: 0.82rem; line-height: 1.6; margin-top: 0.6rem; }.knowledge-view__base { display: flex; font-size: 0.78rem; gap: 0.5rem; }
.knowledge-view__stats { display: grid; gap: 1rem; grid-template-columns: repeat(3, minmax(0, 1fr)); }.knowledge-view__stats article { align-items: center; background: var(--surface); border: 1px solid var(--line); border-radius: 0.8rem; box-shadow: var(--shadow-card); display: grid; gap: 0 0.9rem; grid-template-columns: auto minmax(0, 1fr); padding: 1rem 1.2rem; }.knowledge-view__stat-icon { align-items: center; background: var(--accent-soft); border-radius: 0.65rem; color: var(--accent); display: flex; grid-row: span 2; height: 2.8rem; justify-content: center; width: 2.8rem; }.knowledge-view__stat-icon--green { background: var(--status-success-bg); color: var(--success); }.knowledge-view__stat-icon--amber { background: var(--status-waiting-bg); color: var(--warning); }.knowledge-view__stats article > div { align-items: center; display: flex; gap: 1rem; justify-content: space-between; }.knowledge-view__stats article > div > span { color: var(--text-secondary); font-size: 0.78rem; }.knowledge-view__stats strong { font-size: 1.55rem; font-weight: 600; font-variant-numeric: tabular-nums; letter-spacing: -0.05em; }.knowledge-view__stats small { color: var(--text-tertiary); font-size: 0.72rem; font-weight: 400; margin-left: 0.3rem; }.knowledge-view__stats p { color: var(--text-tertiary); font-size: 0.72rem; margin: 0.3rem 0 0; }
.knowledge-view__upload { flex-shrink: 0; }.knowledge-view__body { background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-lg); display: grid; flex: 1 1 auto; min-height: 0; min-width: 0; overflow: hidden; }.knowledge-view__documents { display: grid; grid-template-rows: auto minmax(0, 1fr); min-height: 0; min-width: 0; overflow: hidden; }.knowledge-view__documents > header { align-items: center; border-bottom: 1px solid var(--line); display: flex; gap: 1rem; justify-content: space-between; padding: 1rem 1.25rem; }.knowledge-view__documents h3 { align-items: center; display: flex; font-size: 0.86rem; font-weight: 600; gap: 0.55rem; margin: 0; }.knowledge-view__documents h3 span { background: var(--surface-inset); border-radius: 0.35rem; color: var(--text-secondary); font-size: 0.72rem; padding: 0.15rem 0.35rem; }.knowledge-view__documents > header p { color: var(--text-tertiary); font-size: 0.72rem; margin: 0.35rem 0 0; }.knowledge-view__filters { display: flex; gap: 0.7rem; }.knowledge-view__filters select, .knowledge-view__base select { background: var(--surface-inset); border: 1px solid var(--line); border-radius: 0.5rem; color: var(--text-secondary); font-size: 0.72rem; padding: 0.5rem; }
.knowledge-view__confirmation { align-items: center; background: var(--status-waiting-bg); border: 1px solid var(--status-waiting-border); border-radius: var(--radius-md); display: grid; flex-shrink: 0; gap: 0.8rem; grid-template-columns: auto minmax(0, 1fr) auto; padding: 0.75rem 1rem; }.knowledge-view__confirmation strong { font-size: 0.82rem; }.knowledge-view__confirmation p { color: var(--text-secondary); font-size: 0.78rem; margin: 0.3rem 0 0; }.knowledge-view__confirmation > div:last-child { display: flex; gap: 0.5rem; }.knowledge-view__confirmation button { border: 1px solid var(--line-strong); border-radius: 0.4rem; font-size: 0.78rem; min-height: 2rem; padding: 0 0.65rem; white-space: nowrap; }.knowledge-view__danger-command { color: var(--danger); }.knowledge-view__confirmation--delete { background: var(--danger-soft); border-color: var(--status-danger-border); }
@media (max-width: 1100px) { .knowledge-view { padding: 1.4rem; }.knowledge-view__stats article { padding: 0.9rem; }.knowledge-view__stats article > div { display: grid; gap: 0.2rem; }.knowledge-view__stats p { display: none; } }
@media (max-width: 760px) { .knowledge-view { height: 100%; overflow-y: auto; padding: 1rem; }.knowledge-view__header { align-items: flex-start; }.knowledge-view__header h2 { font-size: 1.15rem; }.knowledge-view__header > div > span { font-size: 0.78rem; }.knowledge-view__header > button { font-size: 0; min-width: 2.4rem; padding: 0; }.knowledge-view__stats { flex-shrink: 0; gap: 0.5rem; }.knowledge-view__stat-icon { display: none; }.knowledge-view__stats article { display: block; padding: 0.8rem; }.knowledge-view__stats article > div > span { font-size: 0.72rem; }.knowledge-view__stats strong { font-size: 1.3rem; }.knowledge-view__documents > header { align-items: stretch; flex-direction: column; padding: 1rem; }.knowledge-view__filters .ui-search { flex: 1; min-width: 0; }.knowledge-view__body { flex: 1 0 22rem; overflow: visible; }.knowledge-view__documents { display: block; overflow: visible; }.knowledge-view__confirmation { grid-template-columns: auto minmax(0, 1fr); }.knowledge-view__confirmation > div:last-child { grid-column: 2; } }
</style>
