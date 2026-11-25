<script setup lang="ts">
import { Check } from "lucide-vue-next";
import { computed, ref, watch } from "vue";

import type { ChatAssemblyConfigurationResponse } from "@agent-java/api-contracts";

const emit = defineEmits<{ save: [systemPromptId: string, skillIds: readonly string[]] }>();
const props = defineProps<{
  readonly configuration: ChatAssemblyConfigurationResponse | null;
  readonly isSaving: boolean;
}>();

const skillIds = ref<string[]>([]);
const notice = ref<string | null>(null);
const selectedPromptId = computed(() => props.configuration?.selection.systemPromptId ?? "");

watch(
  () => props.configuration,
  (value) => {
    if (value === null) return;
    skillIds.value = [...value.selection.skillIds];
  },
  { immediate: true }
);

function toggleSkill(skillId: string): void {
  skillIds.value = skillIds.value.includes(skillId)
    ? skillIds.value.filter((item) => item !== skillId)
    : [...skillIds.value, skillId];
}

function saveSelection(): void {
  if (selectedPromptId.value.length === 0) {
    notice.value = "请先在「系统提示词」里选择一个提示词。";
    return;
  }
  notice.value = null;
  emit("save", selectedPromptId.value, skillIds.value);
}
</script>

<template>
  <aside class="chat-skill-sidebar" aria-label="Skill 设置">
    <header class="chat-skill-sidebar__header">
      <div>
        <p>Skill 设置</p>
        <h3>模型按需加载所选 Skill</h3>
      </div>
      <RouterLink class="chat-skill-sidebar__manage" to="/skills">管理</RouterLink>
    </header>

    <p v-if="notice" class="chat-skill-sidebar__notice" role="alert">{{ notice }}</p>
    <p v-if="configuration === null" class="chat-skill-sidebar__empty">正在加载 Skill。</p>
    <p v-else-if="configuration.skills.length === 0" class="chat-skill-sidebar__empty">还没有上传 Skill，到 Skill 页上传。</p>

    <div v-else class="chat-skill-sidebar__list" role="list">
      <label v-for="skill in configuration.skills" :key="skill.id" class="chat-skill-sidebar__item" role="listitem">
        <input type="checkbox" :checked="skillIds.includes(skill.id)" :disabled="isSaving" @change="toggleSkill(skill.id)">
        <span><strong>{{ skill.name }}</strong><small>{{ skill.description }}</small></span>
      </label>
    </div>

    <button class="chat-skill-sidebar__save" type="button" :disabled="isSaving || configuration === null" @click="saveSelection">
      <Check :size="15" aria-hidden="true" />{{ isSaving ? "保存中" : "保存使用的 Skill" }}
    </button>
  </aside>
</template>

<style scoped>
.chat-skill-sidebar { background: var(--surface-raised); border-left: 1px solid var(--line); border-top: 1px solid var(--line); display: grid; gap: 0.8rem; grid-auto-rows: max-content; min-width: 0; overflow: auto; padding: 1rem; }
.chat-skill-sidebar__header { align-items: center; display: flex; gap: 0.8rem; justify-content: space-between; }
.chat-skill-sidebar__header p { color: var(--text-primary); font-size: 0.86rem; font-weight: 700; margin: 0; }
.chat-skill-sidebar__header h3 { color: var(--text-tertiary); font-size: 0.72rem; font-weight: 600; margin: 0.22rem 0 0; }
.chat-skill-sidebar__manage { align-items: center; border: 1px solid var(--line-strong); border-radius: 0.5rem; color: var(--text-secondary); display: inline-flex; font-size: 0.78rem; min-height: 2.2rem; padding: 0 0.65rem; }
.chat-skill-sidebar__manage:hover { background: var(--surface-hover); border-color: var(--accent-border); color: var(--text-primary); }
.chat-skill-sidebar__notice { background: var(--danger-soft); border: 1px solid var(--status-danger-border); border-radius: 0.5rem; color: var(--status-danger-text); font-size: 0.78rem; line-height: 1.5; margin: 0; padding: 0.55rem 0.65rem; }
.chat-skill-sidebar__empty { color: var(--text-tertiary); font-size: 0.78rem; margin: 0; }
.chat-skill-sidebar__list { display: grid; gap: 0.5rem; }
.chat-skill-sidebar__item { align-items: start; border: 1px solid var(--line); border-radius: 0.5rem; display: grid; gap: 0.5rem; grid-template-columns: auto minmax(0, 1fr); padding: 0.58rem; }
.chat-skill-sidebar__item:hover { background: var(--surface-hover); }
.chat-skill-sidebar__item input { margin-top: 0.15rem; }
.chat-skill-sidebar__item span { display: grid; gap: 0.18rem; min-width: 0; }
.chat-skill-sidebar__item strong { color: var(--text-primary); font-size: 0.78rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-skill-sidebar__item small { color: var(--text-tertiary); display: -webkit-box; font-size: 0.78rem; font-weight: 500; line-height: 1.45; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }
.chat-skill-sidebar__save { align-items: center; background: var(--accent); border-radius: 0.45rem; color: white; display: inline-flex; font-size: 0.78rem; font-weight: 700; gap: 0.35rem; min-height: 2.35rem; padding: 0 0.72rem; width: fit-content; }
button:disabled, input:disabled { cursor: not-allowed; opacity: 0.55; }
@media (max-width: 1120px) { .chat-skill-sidebar { border-left: 0; } }
</style>
