<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { Activity, FileText, GitBranch, History, Radio, ShieldCheck } from "lucide-vue-next";
import { useRouter } from "vue-router";

import ActiveAlertList from "../components/ActiveAlertList.vue";
import AiopsCaseLibrary from "../components/AiopsCaseLibrary.vue";
import AiopsEvidenceChain from "../components/AiopsEvidenceChain.vue";
import AiopsHistory from "../components/AiopsHistory.vue";
import AiopsRunForm from "../components/AiopsRunForm.vue";
import AiopsReportPanel, { type AiopsReportDisplay } from "../components/AiopsReportPanel.vue";
import AiopsTimeline from "../components/AiopsTimeline.vue";
import AppErrorState from "../components/AppErrorState.vue";
import AppLoadingState from "../components/AppLoadingState.vue";
import AsyncStatusBadge from "../components/AsyncStatusBadge.vue";
import { useAiopsStore } from "../stores/aiops";
import { useChatStore } from "../stores/chat";

const aiops = useAiopsStore();
const chat = useChatStore();
const router = useRouter();
const diagnosticSkills = computed(() => chat.configuration?.skills ?? []);
const sourceTab = ref<"alerts" | "history">("history");
const contentTab = ref<"report" | "events">("report");
const inspectorTab = ref<"chain" | "cases">("chain");
const activeTitle = computed(() => aiops.activeTask?.query ?? "智能诊断工作区");
const activeStatus = computed(() => (aiops.isRunning ? "running" : aiops.activeTask?.status ?? "ready"));
const evidenceCount = computed(() => aiops.evidenceChain?.evidence.length ?? 0);
const toolCallCount = computed(() => aiops.evidenceChain?.toolCalls.length ?? 0);
const isEffectivelyRunning = computed(
  () => aiops.isRunning || aiops.activeTask?.status === "running"
);
const activeReport = computed<AiopsReportDisplay | null>(() => {
  const reports = aiops.evidenceChain?.reports ?? [];
  if (reports.length > 0) {
    const report = reports[reports.length - 1];
    if (report) return report;
  }
  for (let index = aiops.liveEvents.length - 1; index >= 0; index -= 1) {
    const event = aiops.liveEvents[index];
    if (event?.type === "report") {
      return { ...event.report, createdAt: event.timestamp };
    }
  }
  return null;
});
const lifecycleText = computed(() => {
  if (isEffectivelyRunning.value) return "执行中";
  if (aiops.activeTask?.status === "accepted") return "等待中";
  if (aiops.activeTask?.status === "cancelled") return "已取消";
  if (aiops.activeTask?.status === "failed") return "失败";
  if (aiops.activeTask?.status === "succeeded") return "已完成";
  return "已就绪";
});

onMounted(() => {
  void aiops.initialize().catch(() => undefined);
  void chat.reloadConfiguration().catch(() => undefined);
});

onBeforeUnmount(() => {
  aiops.reset();
});

function run(operation: () => Promise<unknown>): void {
  void operation().catch(() => undefined);
}

function selectDiagnostic(id: string): void {
  contentTab.value = "report";
  run(() => aiops.selectDiagnostic(id));
}

function startDiagnostic(query: string, alert: Record<string, unknown> | undefined, skillIds: string[]): void {
  contentTab.value = "events";
  run(() => aiops.runDiagnostic(query, alert, skillIds));
}

function diagnoseAlert(alert: Parameters<typeof aiops.diagnoseAlert>[0]): void {
  contentTab.value = "events";
  run(() => aiops.diagnoseAlert(alert));
}

function openCaseDocument(documentId: string): void {
  void router.push({ name: "knowledge", query: { documentId } });
}

</script>

<template>
  <section class="aiops-view" aria-label="智能诊断工作区">
    <header class="aiops-view__hero ui-page-heading"><div><h2>诊断中心</h2><p :title="activeTitle">{{ aiops.activeTask ? activeTitle : '从运行信号出发，连接证据、分析与行动。' }}</p></div><div class="aiops-view__hero-status"><span class="ui-pill"><ShieldCheck :size="13" aria-hidden="true" />基于证据的分析</span><AsyncStatusBadge :status="activeStatus"  /></div></header>

    <AppLoadingState v-if="aiops.isLoading && aiops.history.length === 0" label="正在加载诊断历史" />
    <AppErrorState v-else-if="aiops.errorMessage && aiops.history.length === 0" :can-retry="true" :message="aiops.errorMessage" @retry="run(aiops.initialize)" />
    <div v-else class="aiops-view__console">
      <aside class="aiops-view__left" aria-label="诊断输入与历史">
        <AiopsRunForm :disabled="aiops.isLoading" :is-running="aiops.isRunning" :skills="diagnosticSkills" @cancel="run(aiops.cancelActive)" @run="startDiagnostic" />
        <div class="ui-tabs aiops-view__source-tabs" aria-label="诊断来源"><button type="button" :class="{ 'is-active': sourceTab === 'alerts' }" :aria-pressed="sourceTab === 'alerts'" @click="sourceTab = 'alerts'"><Radio :size="14" />告警<span>{{ aiops.activeAlerts.length }}</span></button><button type="button" :class="{ 'is-active': sourceTab === 'history' }" :aria-pressed="sourceTab === 'history'" @click="sourceTab = 'history'"><History :size="14" />历史<span>{{ aiops.history.length }}</span></button></div>
        <ActiveAlertList
          v-show="sourceTab === 'alerts'"
          :alerts="aiops.activeAlerts"
          :error-message="aiops.alertsErrorMessage"
          :is-loading="aiops.alertsLoading"
          @diagnose="diagnoseAlert"
          @refresh="run(aiops.refreshActiveAlerts)"
        />
        <AiopsHistory v-show="sourceTab === 'history'" :active-diagnostic-id="aiops.activeDiagnosticId" :tasks="aiops.history" @select="selectDiagnostic" />
      </aside>

      <main class="aiops-view__center" aria-label="实时诊断">
        <section class="aiops-view__status-strip" aria-label="诊断状态概览">
          <div><span>当前状态</span><strong>{{ lifecycleText }}</strong></div>
          <div><span>执行步骤</span><strong>{{ aiops.evidenceChain?.steps.length ?? 0 }}</strong></div>
          <div><span>证据</span><strong>{{ evidenceCount }}</strong></div>
          <div><span>工具调用</span><strong>{{ toolCallCount }}</strong></div>
        </section>
        <div class="ui-tabs aiops-view__content-tabs" aria-label="诊断内容"><button type="button" :class="{ 'is-active': contentTab === 'report' }" :aria-pressed="contentTab === 'report'" @click="contentTab = 'report'"><FileText :size="15" />诊断报告</button><button type="button" :class="{ 'is-active': contentTab === 'events' }" :aria-pressed="contentTab === 'events'" @click="contentTab = 'events'"><Activity :size="15" />实时过程<span v-if="aiops.liveEvents.length">{{ aiops.liveEvents.length }}</span></button></div>
        <AiopsReportPanel
          v-show="contentTab === 'report'"
          :has-task="aiops.activeTask !== null"
          :is-running="isEffectivelyRunning"
          :report="activeReport"
          :task-failed="aiops.activeTask?.status === 'failed'"
        />
        <AiopsTimeline v-show="contentTab === 'events'" :events="aiops.liveEvents" :is-running="isEffectivelyRunning" />
      </main>

      <aside class="aiops-view__right" aria-label="执行链与工具调用">
        <div class="ui-tabs aiops-view__inspector-tabs" aria-label="诊断资料"><button type="button" :class="{ 'is-active': inspectorTab === 'chain' }" :aria-pressed="inspectorTab === 'chain'" @click="inspectorTab = 'chain'"><GitBranch :size="14" />执行与证据</button><button type="button" :class="{ 'is-active': inspectorTab === 'cases' }" :aria-pressed="inspectorTab === 'cases'" @click="inspectorTab = 'cases'">案例<span>{{ aiops.diagnosticCases.length }}</span></button></div><div class="aiops-view__inspector-body"><AiopsEvidenceChain v-show="inspectorTab === 'chain'" :chain="aiops.evidenceChain" />
        <AiopsCaseLibrary v-show="inspectorTab === 'cases'" :cases="aiops.diagnosticCases" @open-document="openCaseDocument" @select="selectDiagnostic" /></div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.aiops-view { background: var(--canvas); display: grid; gap: 1.3rem; grid-template-rows: auto minmax(0, 1fr); height: 100%; min-height: 0; overflow: hidden; padding: 1.8rem 1.6rem; }
.aiops-view__hero { min-width: 0; }.aiops-view__hero > div:first-child { min-width: 0; }.aiops-view__hero p { max-width: 48rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.aiops-view__hero-status { align-items: center; display: flex; flex-shrink: 0; gap: 0.8rem; }
.aiops-view__console { display: grid; gap: 1rem; grid-template-columns: 16.5rem minmax(0, 1fr) 18.5rem; min-height: 0; overflow: hidden; }
.aiops-view__left, .aiops-view__center, .aiops-view__right { background: var(--surface); border: 1px solid var(--line); border-radius: 0.85rem; box-shadow: var(--shadow-card); min-height: 0; min-width: 0; }
.aiops-view__left { overflow-y: auto; }.aiops-view__source-tabs { border-bottom: 1px solid var(--line); padding: 0.6rem; }.aiops-view__source-tabs button { flex: 1; justify-content: center; }
.aiops-view__center { display: grid; grid-template-rows: auto auto minmax(0, 1fr); overflow: hidden; }
.aiops-view__status-strip { border-bottom: 1px solid var(--line); display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); padding: 1rem 0.3rem; }.aiops-view__status-strip div { border-right: 1px solid var(--line); padding: 0 0.8rem; }.aiops-view__status-strip div:last-child { border: 0; }.aiops-view__status-strip span { color: var(--text-tertiary); display: block; font-size: 0.72rem; }.aiops-view__status-strip strong { display: block; font-size: 1.2rem; font-variant-numeric: tabular-nums; font-weight: 600; line-height: 1.5; margin-top: 0.2rem; }.aiops-view__status-strip div:first-child strong { color: var(--accent); font-size: 0.86rem; line-height: 1.95; }
.aiops-view__content-tabs { border-bottom: 1px solid var(--line); padding: 0.6rem 1rem; }.aiops-view__right { display: flex; flex-direction: column; overflow: hidden; }.aiops-view__inspector-tabs { border-bottom: 1px solid var(--line); flex-shrink: 0; padding: 0.75rem 0.7rem; }.aiops-view__inspector-tabs button { flex: 1; font-size: 0.72rem; justify-content: center; padding: 0 0.3rem; }.aiops-view__inspector-body { flex: 1; min-height: 0; overflow-y: auto; }
.aiops-view__left :deep(.active-alert-list ul) { max-height: none; overflow: visible; }.aiops-view__left :deep(.aiops-history) { border-top: 0; }.aiops-view__left :deep(.aiops-history li button) { border-radius: 0.55rem; }.aiops-view__left :deep(.aiops-history li button > .async-status-badge) { font-size: 0.72rem; }.aiops-view__left :deep(.active-alert-list li > button) { border-radius: 0.45rem; }.aiops-view__right :deep(.aiops-case-library) { border-top: 0; }
@media (max-width: 1400px) { .aiops-view__console { grid-template-columns: 15rem minmax(0, 1fr) 16rem; gap: 0.75rem; }.aiops-view { padding: 1.4rem 1.15rem; }.aiops-view__hero-status > .ui-pill { display: none; } }
@media (max-width: 1150px) { .aiops-view { overflow-y: auto; }.aiops-view__console { grid-template-columns: 16rem minmax(0, 1fr); grid-template-rows: 36rem 28rem; align-content: start; overflow: visible; }.aiops-view__left { max-height: 44rem; }.aiops-view__center { min-height: 36rem; }.aiops-view__right { grid-column: 1 / -1; max-height: 32rem; }.aiops-view__hero p { max-width: 35rem; } }
@media (max-width: 760px) { .aiops-view { display: block; overflow-y: auto; padding: 1rem; }.aiops-view__hero { margin-bottom: 1rem; }.aiops-view__hero h2 { font-size: 1.25rem; }.aiops-view__hero p { font-size: 0.72rem; max-width: 15rem; }.aiops-view__console { display: flex; flex-direction: column; gap: 1rem; }.aiops-view__left { max-height: 30rem; flex: 0 0 auto; }.aiops-view__center { height: 38rem; flex: 0 0 auto; }.aiops-view__right { height: 30rem; flex: 0 0 auto; }.aiops-view__status-strip div { padding: 0 0.65rem; }.aiops-view__hero-status .async-status-badge { font-size: 0.72rem; } }
</style>
