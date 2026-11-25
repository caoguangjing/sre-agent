<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ArrowUpRight, ChevronDown, ChevronRight, Radar, RefreshCw, TrendingDown, TrendingUp } from "lucide-vue-next";
import { useRouter } from "vue-router";

import type { InspectionMetricCatalogEntry } from "@agent-java/api-contracts";

import AppEmptyState from "../components/AppEmptyState.vue";
import AppErrorState from "../components/AppErrorState.vue";
import AppLoadingState from "../components/AppLoadingState.vue";
import { useAiopsStore } from "../stores/aiops";
import { useInspectionStore } from "../stores/inspection";

const inspection = useInspectionStore();
const aiops = useAiopsStore();
const router = useRouter();

const config = computed(() => inspection.settings?.config ?? null);
const enabled = computed(() => inspection.settings?.enabled ?? false);
const openCount = computed(() => inspection.findings.filter((f) => f.status === "open").length);

interface MetricGroup {
  category: string;
  metrics: InspectionMetricCatalogEntry[];
}

const catalog = computed(() => inspection.settings?.catalog ?? []);
const groups = computed<MetricGroup[]>(() => {
  const map = new Map<string, MetricGroup>();
  for (const entry of catalog.value) {
    let group = map.get(entry.category);
    if (!group) {
      group = { category: entry.category, metrics: [] };
      map.set(entry.category, group);
    }
    group.metrics.push(entry);
  }
  return [...map.values()];
});
const selectedNames = ref<Set<string>>(new Set());
const collapsedCategories = ref<Set<string>>(new Set());

// settings.metrics=null 表示全部白名单指标；空数组=全不检
watch(() => inspection.settings?.metrics, (metrics) => {
  const names = catalog.value.map((entry) => entry.name);
  selectedNames.value = new Set(metrics === null || metrics === undefined ? names : metrics.filter((m) => names.includes(m)));
});

onMounted(async () => {
  try {
    await inspection.initialize();
  } catch {
    return;
  }
});

onBeforeUnmount(() => {
  inspection.reset();
});

function toggle(): void {
  void inspection.setEnabled(!enabled.value).catch(() => undefined);
}

function refresh(): void {
  void inspection.refreshFindings().catch(() => undefined);
}

function selectedGroupCount(group: MetricGroup): number {
  return group.metrics.filter((m) => selectedNames.value.has(m.name)).length;
}

function isGroupFullySelected(group: MetricGroup): boolean {
  return group.metrics.length > 0 && selectedGroupCount(group) === group.metrics.length;
}

function isGroupPartiallySelected(group: MetricGroup): boolean {
  const count = selectedGroupCount(group);
  return count > 0 && count < group.metrics.length;
}

function toggleGroup(group: MetricGroup): void {
  const next = new Set(selectedNames.value);
  if (isGroupFullySelected(group)) group.metrics.forEach((m) => next.delete(m.name));
  else group.metrics.forEach((m) => next.add(m.name));
  persistSelection(next);
}

function toggleMetric(name: string): void {
  const next = new Set(selectedNames.value);
  if (next.has(name)) next.delete(name);
  else next.add(name);
  persistSelection(next);
}

function persistSelection(next: Set<string>): void {
  selectedNames.value = next;
  // 全勾时存 null（=全部，白名单新增指标自动跟上）；子集存显式数组；空数组=全不检
  const all = catalog.value.length > 0 && next.size === catalog.value.length;
  void inspection.setMetrics(all ? null : [...next]).catch(() => undefined);
}

function isCollapsed(category: string): boolean {
  return collapsedCategories.value.has(category);
}

function toggleCollapsed(category: string): void {
  const next = new Set(collapsedCategories.value);
  if (next.has(category)) next.delete(category);
  else next.add(category);
  collapsedCategories.value = next;
}

async function openDiagnostic(diagnosticId: string): Promise<void> {
  try {
    await aiops.selectDiagnostic(diagnosticId);
  } catch {
    // 诊断已不存在时仍进入诊断中心
  }
  router.push({ name: "aiops" });
}

function formatTime(value: string): string {
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? value : time.toLocaleString("zh-CN", { hour12: false });
}

function formatDelta(value: number): string {
  return `${value > 0 ? "+" : ""}${value}%`;
}
</script>

<template>
  <section class="inspection-view" aria-label="智能巡检工作区">
    <header class="inspection-view__hero ui-page-heading">
      <div>
        <h2>智能巡检</h2>
        <p>定时对比指标基线并校验业务水位，主动发现异常趋势，发现后自动创建根因诊断——无需手工配置报警规则。</p>
      </div>
      <div class="inspection-view__hero-status">
        <span class="ui-pill"><Radar :size="13" aria-hidden="true" />{{ enabled ? "巡检进行中" : "巡检已关闭" }}</span>
        <span class="ui-pill" v-if="openCount">{{ openCount }} 项异常中</span>
      </div>
    </header>

    <AppLoadingState v-if="inspection.isLoading" label="正在加载巡检状态" />
    <AppErrorState v-else-if="inspection.errorMessage && inspection.settings === null" :can-retry="true" :message="inspection.errorMessage" @retry="() => inspection.initialize().catch(() => undefined)" />
    <div v-else class="inspection-view__console">
      <aside class="inspection-view__settings" aria-label="巡检设置">
        <h3>巡检开关</h3>
        <button
          type="button"
          class="inspection-switch"
          role="switch"
          :aria-checked="enabled"
          :disabled="inspection.isToggling"
          @click="toggle"
        >
          <span class="inspection-switch__track" aria-hidden="true"><span class="inspection-switch__thumb" /></span>
          <span>{{ enabled ? "已开启" : "已关闭" }}</span>
        </button>
        <div v-if="groups.length" class="inspection-catalog" aria-label="检测指标">
          <span class="inspection-catalog__label">检测指标<span v-if="!enabled" class="inspection-catalog__note">开启后按勾选指标自动注册白名单条目</span></span>
          <div v-for="group in groups" :key="group.category" class="inspection-catalog__group">
            <div class="inspection-catalog__head">
              <label class="inspection-catalog__toggle">
                <input
                  type="checkbox"
                  :checked="isGroupFullySelected(group)"
                  :indeterminate="isGroupPartiallySelected(group)"
                  :disabled="inspection.isToggling"
                  @change="toggleGroup(group)"
                />
                <strong>{{ group.category }}</strong>
              </label>
              <span class="inspection-catalog__count">{{ selectedGroupCount(group) }}/{{ group.metrics.length }}</span>
              <button
                type="button"
                class="inspection-catalog__chevron"
                :aria-expanded="!isCollapsed(group.category)"
                :aria-label="`${group.category} 指标列表${isCollapsed(group.category) ? '展开' : '收起'}`"
                @click="toggleCollapsed(group.category)"
              >
                <ChevronDown v-if="!isCollapsed(group.category)" :size="14" aria-hidden="true" />
                <ChevronRight v-else :size="14" aria-hidden="true" />
              </button>
            </div>
            <ul v-if="!isCollapsed(group.category)" class="inspection-catalog__metrics">
              <li v-for="metric in group.metrics" :key="metric.name">
                <label class="inspection-catalog__metric" :title="metric.name">
                  <input type="checkbox" :checked="selectedNames.has(metric.name)" :disabled="inspection.isToggling" @change="toggleMetric(metric.name)" />
                  <span>{{ metric.displayName }}</span>
                </label>
              </li>
            </ul>
          </div>
        </div>
        <p v-if="inspection.settings?.updatedAt" class="inspection-view__muted">更新于 {{ formatTime(inspection.settings.updatedAt) }}</p>
        <dl v-if="config" class="inspection-config">
          <div><dt>监控应用</dt><dd>{{ config.configured && config.apps.length ? config.apps.join("、") : "未配置" }}</dd></div>
          <div><dt>自动诊断</dt><dd>{{ config.autoDiagnose ? "发现异常自动创建诊断任务" : "仅记录发现" }}</dd></div>
        </dl>
        <p v-if="config && !config.configured" class="inspection-view__hint">巡检范围未配置：在 config/project.json 的 inspection 中设置 ownerId 与 apps。</p>
      </aside>

      <main class="inspection-view__findings" aria-label="巡检发现">
        <header class="inspection-view__findings-head">
          <h3>巡检发现<span v-if="inspection.findings.length" class="inspection-view__count">{{ inspection.findings.length }}</span></h3>
          <button type="button" class="inspection-refresh" :disabled="inspection.isLoading" @click="refresh"><RefreshCw :size="14" />刷新</button>
        </header>
        <ul v-if="inspection.findings.length" class="inspection-finding-list">
          <li v-for="finding in inspection.findings" :key="finding.id">
            <article class="inspection-finding" :class="{ 'inspection-finding--open': finding.status === 'open' }">
              <div class="inspection-finding__main">
                <strong>{{ finding.service }}</strong>
                <span>{{ finding.metricName }}</span>
                <span class="inspection-finding__delta" :class="finding.direction === 'up' ? 'is-up' : 'is-down'">
                  <TrendingUp v-if="finding.direction === 'up'" :size="14" aria-hidden="true" />
                  <TrendingDown v-else :size="14" aria-hidden="true" />
                  {{ formatDelta(finding.deltaPercent) }}
                </span>
              </div>
              <p class="inspection-finding__values">{{ finding.baselineValue }} → {{ finding.currentValue }} {{ finding.unit }}（对比窗口 {{ finding.windowMinutes }} 分钟）</p>
              <div class="inspection-finding__meta">
                <span class="inspection-finding__status" :class="finding.status === 'open' ? 'is-open' : 'is-recovered'">{{ finding.status === "open" ? "异常中" : "已恢复" }}</span>
                <span>检测于 {{ formatTime(finding.detectedAt) }}</span>
                <span v-if="finding.alertName">旁证告警：{{ finding.alertName }}</span>
                <button v-if="finding.diagnosticId" type="button" class="inspection-finding__link" @click="openDiagnostic(finding.diagnosticId)">
                  查看诊断<ArrowUpRight :size="13" aria-hidden="true" />
                </button>
              </div>
            </article>
          </li>
        </ul>
        <AppEmptyState
          v-else
          title="暂无巡检发现"
          :detail="enabled ? '系统将按扫描间隔自动对比指标基线。' : '开启智能巡检后，系统将主动发现异常趋势。'"
        />
      </main>
    </div>
  </section>
</template>

<style scoped>
.inspection-view { background: var(--canvas); display: grid; gap: 1.3rem; grid-template-rows: auto minmax(0, 1fr); height: 100%; min-height: 0; overflow: hidden; padding: 1.8rem 1.6rem; }
.inspection-view__hero { min-width: 0; }
.inspection-view__hero > div:first-child { min-width: 0; }
.inspection-view__hero p { max-width: 48rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.inspection-view__hero-status { align-items: center; display: flex; flex-shrink: 0; gap: 0.6rem; }
.inspection-view__console { display: grid; gap: 1rem; grid-template-columns: 17rem minmax(0, 1fr); min-height: 0; overflow: hidden; }
.inspection-view__settings, .inspection-view__findings { background: var(--surface); border: 1px solid var(--line); border-radius: 0.85rem; box-shadow: var(--shadow-card); min-height: 0; min-width: 0; overflow-y: auto; }
.inspection-view__settings { padding: 1.1rem 1rem; display: flex; flex-direction: column; gap: 0.9rem; }
.inspection-view__settings h3, .inspection-view__findings-head h3 { font-size: 0.92rem; font-weight: 600; margin: 0; }
.inspection-switch { align-items: center; background: var(--surface); border: 1px solid var(--line); border-radius: 0.7rem; cursor: pointer; display: flex; font-size: 0.85rem; font-weight: 500; gap: 0.7rem; padding: 0.7rem 0.8rem; }
.inspection-switch:disabled { cursor: default; opacity: 0.7; }
.inspection-switch__track { background: var(--line); border-radius: 99px; display: inline-flex; flex-shrink: 0; height: 1.15rem; padding: 0.12rem; transition: background 0.15s ease; width: 2.1rem; }
.inspection-switch[aria-checked="true"] .inspection-switch__track { background: var(--accent); }
.inspection-switch__thumb { background: var(--surface); border-radius: 99px; flex: 1; transition: transform 0.15s ease; transform: translateX(0); }
.inspection-switch[aria-checked="true"] .inspection-switch__thumb { transform: translateX(0.85rem); }
.inspection-switch[aria-checked="true"] { border-color: var(--accent-border); }
.inspection-view__muted { color: var(--text-tertiary); font-size: 0.72rem; margin: -0.5rem 0 0; }
.inspection-config { display: grid; gap: 0.55rem; margin: 0; }
.inspection-config div { align-items: baseline; display: flex; gap: 0.6rem; justify-content: space-between; }
.inspection-config dt { color: var(--text-tertiary); font-size: 0.76rem; }
.inspection-config dd { font-size: 0.78rem; font-weight: 500; margin: 0; text-align: right; }
.inspection-view__hint { background: var(--surface-selected); border: 1px solid var(--line); border-radius: 0.6rem; color: var(--text-secondary); font-size: 0.72rem; line-height: 1.6; margin: 0; padding: 0.6rem 0.7rem; }
.inspection-view__findings { display: flex; flex-direction: column; }
.inspection-view__findings-head { align-items: center; border-bottom: 1px solid var(--line); display: flex; gap: 0.8rem; padding: 0.85rem 1rem; }
.inspection-view__findings-head h3 { align-items: center; display: flex; gap: 0.45rem; }
.inspection-view__count { background: var(--accent-soft); border-radius: 99px; color: var(--accent); font-size: 0.72rem; padding: 0.05rem 0.5rem; }
.inspection-refresh { align-items: center; background: none; border: 1px solid var(--line); border-radius: 0.55rem; color: var(--text-secondary); cursor: pointer; display: inline-flex; font-size: 0.76rem; gap: 0.35rem; margin-left: auto; padding: 0.35rem 0.65rem; }
.inspection-refresh:hover { background: var(--rail-hover); }
.inspection-finding-list { display: grid; gap: 0.7rem; list-style: none; margin: 0; padding: 0.9rem 1rem; }
.inspection-finding { background: var(--surface); border: 1px solid var(--line); border-radius: 0.75rem; display: grid; gap: 0.45rem; padding: 0.85rem 0.95rem; }
.inspection-finding--open { border-color: var(--accent-border); }
.inspection-finding__main { align-items: center; display: flex; flex-wrap: wrap; gap: 0.55rem; }
.inspection-finding__main strong { font-size: 0.9rem; font-weight: 600; }
.inspection-finding__main > span:not(.inspection-finding__delta) { color: var(--text-secondary); font-size: 0.8rem; }
.inspection-finding__delta { align-items: center; border-radius: 99px; display: inline-flex; font-size: 0.76rem; font-weight: 600; gap: 0.25rem; padding: 0.15rem 0.55rem; }
.inspection-finding__delta.is-up { background: var(--accent-soft); color: var(--accent); }
.inspection-finding__delta.is-down { background: var(--surface-selected); color: var(--text-secondary); }
.inspection-finding__values { color: var(--text-secondary); font-size: 0.78rem; font-variant-numeric: tabular-nums; margin: 0; }
.inspection-finding__meta { align-items: center; color: var(--text-tertiary); display: flex; flex-wrap: wrap; font-size: 0.72rem; gap: 0.7rem; }
.inspection-finding__status { border-radius: 99px; font-weight: 500; padding: 0.15rem 0.55rem; }
.inspection-finding__status.is-open { background: var(--accent-soft); color: var(--accent); }
.inspection-finding__status.is-recovered { background: var(--rail-hover); color: var(--text-secondary); }
.inspection-finding__link { align-items: center; background: none; border: 1px solid var(--line); border-radius: 0.5rem; color: var(--accent); cursor: pointer; display: inline-flex; font-size: 0.72rem; gap: 0.25rem; margin-left: auto; padding: 0.25rem 0.55rem; }
.inspection-finding__link:hover { background: var(--rail-hover); }
@media (max-width: 1150px) { .inspection-view { overflow-y: auto; } .inspection-view__console { grid-template-columns: 16rem minmax(0, 1fr); } }
@media (max-width: 860px) { .inspection-view { display: block; overflow-y: auto; padding: 1rem; } .inspection-view__console { display: flex; flex-direction: column; gap: 1rem; } .inspection-view__settings { flex: 0 0 auto; } .inspection-view__hero p { white-space: normal; } }
.inspection-catalog { display: grid; gap: 0.5rem; }
.inspection-catalog__label { color: var(--text-tertiary); font-size: 0.74rem; }
.inspection-catalog__note { display: block; font-size: 0.7rem; line-height: 1.5; margin-top: 0.15rem; }
.inspection-catalog__group { border: 1px solid var(--line); border-radius: 0.6rem; overflow: hidden; }
.inspection-catalog__head { align-items: center; background: var(--surface-selected); display: flex; gap: 0.5rem; padding: 0.4rem 0.55rem; }
.inspection-catalog__toggle { align-items: center; cursor: pointer; display: inline-flex; flex: 1; font-size: 0.78rem; gap: 0.4rem; min-width: 0; }
.inspection-catalog__toggle strong { font-weight: 600; }
.inspection-catalog__toggle input, .inspection-catalog__metric input { accent-color: var(--accent); }
.inspection-catalog__count { color: var(--text-tertiary); font-size: 0.7rem; font-variant-numeric: tabular-nums; }
.inspection-catalog__chevron { align-items: center; background: none; border: none; color: var(--text-tertiary); cursor: pointer; display: inline-flex; padding: 0.15rem; }
.inspection-catalog__metrics { display: grid; gap: 0.1rem; list-style: none; margin: 0; padding: 0.35rem 0.55rem 0.45rem; }
.inspection-catalog__metric { align-items: center; border-radius: 0.4rem; cursor: pointer; display: flex; font-size: 0.74rem; gap: 0.4rem; padding: 0.18rem 0.25rem; }
.inspection-catalog__metric:hover { background: var(--rail-hover); }
.inspection-catalog__metric:has(input:disabled) { cursor: default; opacity: 0.7; }
</style>
