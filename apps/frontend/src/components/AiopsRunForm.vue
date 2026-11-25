<script setup lang="ts">
import { Play, Square } from "lucide-vue-next";
import { ref } from "vue";

import type { ChatSkillAsset } from "@agent-java/api-contracts";

const emit = defineEmits<{ cancel: []; run: [query: string, alert: Record<string, unknown> | undefined, skillIds: string[]] }>();
defineProps<{ readonly disabled: boolean; readonly isRunning: boolean; readonly skills?: readonly ChatSkillAsset[] }>();

const query = ref("");
const alertText = ref("");
const validationMessage = ref<string | null>(null);
const selectedSkillIds = ref<string[]>([]);

function toggleSkill(skillId: string, checked: boolean): void {
  if (checked) {
    if (selectedSkillIds.value.length >= 3) {
      validationMessage.value = "最多选择 3 个 Skill。";
      return;
    }
    selectedSkillIds.value = [...selectedSkillIds.value, skillId];
  } else {
    selectedSkillIds.value = selectedSkillIds.value.filter((id) => id !== skillId);
  }
  if (validationMessage.value === "最多选择 3 个 Skill。") validationMessage.value = null;
}

function submit(): void {
  const trimmedQuery = query.value.trim();
  if (trimmedQuery.length === 0) {
    validationMessage.value = "请描述需要排查的运行信号。";
    return;
  }
  let alert: Record<string, unknown> | undefined;
  if (alertText.value.trim().length > 0) {
    try {
      const parsed: unknown = JSON.parse(alertText.value);
      if (parsed === null || Array.isArray(parsed) || typeof parsed !== "object") {
        throw new Error();
      }
      alert = parsed as Record<string, unknown>;
    } catch {
      validationMessage.value = "告警上下文必须是有效的 JSON 对象。";
      return;
    }
  }
  validationMessage.value = null;
  emit("run", trimmedQuery, alert, selectedSkillIds.value);
}
</script>

<template>
  <form class="aiops-run-form" @submit.prevent="submit">
    <div class="aiops-run-form__header"><div><p>发起诊断</p><h2>开始一次诊断</h2></div><span v-if="isRunning" role="status">诊断执行中</span></div>
    <label>诊断问题<textarea v-model="query" :disabled="disabled || isRunning" aria-label="诊断问题" placeholder="例如：排查结算服务 API 延迟升高" rows="3" /></label>
    <details class="aiops-run-form__context"><summary>附加告警上下文<span>可选</span></summary><label><span class="sr-only">告警上下文</span><textarea v-model="alertText" :disabled="disabled || isRunning" aria-label="告警上下文" placeholder='{"service":"checkout","severity":"high"}' rows="3" /></label></details>
    <details class="aiops-run-form__skills" :open="selectedSkillIds.length > 0"><summary>排查指引 Skill<span>{{ skills?.length ? `可选 ${skills.length}` : "未上传" }}</span></summary>
      <ul v-if="skills?.length" class="aiops-run-form__skill-list"><li v-for="skill in skills" :key="skill.id"><label><input :checked="selectedSkillIds.includes(skill.id)" :disabled="disabled || isRunning" type="checkbox" @change="toggleSkill(skill.id, ($event.target as HTMLInputElement).checked)" /><span><strong>{{ skill.name }}</strong><small>{{ skill.description }}</small></span></label></li></ul>
      <p v-if="skills?.length" class="aiops-run-form__skill-empty">不勾选时，诊断会根据问题自动从全部 Skill 中挑选相关项。</p>
      <p v-else class="aiops-run-form__skill-empty">还没有可选 Skill，先到 Skill 页上传 SKILL.md。</p>
    </details>
    <p v-if="validationMessage" class="aiops-run-form__error" role="alert">{{ validationMessage }}</p>
    <div class="aiops-run-form__commands"><button :disabled="disabled || isRunning" type="submit"><Play :size="16" aria-hidden="true" />{{ isRunning ? "诊断执行中" : "开始诊断" }}</button><button v-if="isRunning" class="aiops-run-form__cancel" type="button" @click="emit('cancel')"><Square :size="14" aria-hidden="true" />取消任务</button></div>
  </form>
</template>

<style scoped>
.aiops-run-form { background: var(--surface); border-bottom: 1px solid var(--line); display: grid; gap: 0.9rem; padding: 1.1rem; }
.aiops-run-form__header { align-items: center; display: flex; gap: 1rem; justify-content: space-between; }
.aiops-run-form__header p { color: var(--text-tertiary); font-size: 0.78rem; font-weight: 700; margin: 0 0 0.3rem; }
h2 { font-size: 0.9rem; font-weight: 600; margin: 0; }
.aiops-run-form__header span { color: var(--accent-strong); font-size: 0.78rem; font-weight: 600; }
label { color: var(--text-secondary); display: grid; font-size: 0.78rem; font-weight: 600; gap: 0.4rem; }
label > span { color: var(--text-tertiary); font-weight: 500; }
textarea { background: var(--surface-raised); border: 1px solid var(--line-strong); border-radius: 0.45rem; color: var(--text-primary); line-height: 1.5; padding: 0.6rem; resize: vertical; }
textarea:focus { border-color: var(--accent); outline: 3px solid var(--accent-focus); }
button { align-items: center; background: var(--accent); border-radius: 0.45rem; color: white; display: inline-flex; font-size: 0.82rem; font-weight: 600; gap: 0.4rem; justify-self: start; min-height: 2.5rem; padding: 0 0.8rem; }
button:hover:not(:disabled) { background: var(--accent-strong); }
button:disabled { cursor: not-allowed; opacity: 0.6; }
.aiops-run-form__commands { display: flex; gap: 0.5rem; }.aiops-run-form__cancel { background: transparent; border: 1px solid var(--line-strong); color: var(--status-danger-text); }.aiops-run-form__cancel:hover:not(:disabled) { background: var(--status-danger-bg); }
.aiops-run-form__error { color: var(--danger); font-size: 0.82rem; margin: 0; }
.aiops-run-form__context { border: 1px solid var(--line); border-radius: 0.5rem; padding: 0.55rem; }.aiops-run-form__context summary { align-items: center; color: var(--text-secondary); cursor: pointer; display: flex; font-size: 0.72rem; justify-content: space-between; }.aiops-run-form__context summary span { color: var(--text-tertiary); font-size: 0.72rem; }.aiops-run-form__context textarea { margin-top: 0.6rem; width: 100%; }.aiops-run-form__header p { display: none; }.aiops-run-form__commands > button:first-child { justify-content: center; width: 100%; }.aiops-run-form__commands { flex-wrap: wrap; }
.aiops-run-form__skills { border: 1px solid var(--line); border-radius: 0.5rem; padding: 0.55rem; }.aiops-run-form__skills summary { align-items: center; color: var(--text-secondary); cursor: pointer; display: flex; font-size: 0.72rem; justify-content: space-between; }.aiops-run-form__skills summary span { color: var(--text-tertiary); font-size: 0.72rem; }
.aiops-run-form__skill-list { display: grid; gap: 0.45rem; list-style: none; margin: 0.6rem 0 0; padding: 0; }.aiops-run-form__skill-list label { align-items: start; cursor: pointer; display: grid; gap: 0.5rem; grid-template-columns: auto minmax(0, 1fr); font-weight: 400; }.aiops-run-form__skill-list input { margin-top: 0.15rem; }.aiops-run-form__skill-list strong { color: var(--text-primary); display: block; font-size: 0.78rem; }.aiops-run-form__skill-list small { color: var(--text-tertiary); display: block; font-size: 0.72rem; line-height: 1.5; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.aiops-run-form__skill-empty { color: var(--text-tertiary); font-size: 0.72rem; margin: 0.6rem 0 0; }
</style>
