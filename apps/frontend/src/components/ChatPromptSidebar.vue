<script setup lang="ts">
import { Check } from "lucide-vue-next";
import { computed, ref, watch } from "vue";

import type { ChatAssemblyConfigurationResponse } from "@agent-java/api-contracts";

const emit = defineEmits<{ save: [systemPromptId: string, skillIds: readonly string[]] }>();
const props = defineProps<{
  readonly configuration: ChatAssemblyConfigurationResponse | null;
  readonly isSaving: boolean;
}>();

const chosenPromptId = ref("");
const notice = ref<string | null>(null);
const selectedSkillIds = computed(() => props.configuration?.selection.skillIds ?? []);

watch(
  () => props.configuration,
  (value) => {
    if (value === null) return;
    chosenPromptId.value = value.selection.systemPromptId;
  },
  { immediate: true }
);

function choose(promptId: string): void {
  chosenPromptId.value = promptId;
}

function saveSelection(): void {
  if (chosenPromptId.value.length === 0) {
    notice.value = "请先选择一个系统提示词。";
    return;
  }
  notice.value = null;
  emit("save", chosenPromptId.value, selectedSkillIds.value);
}
</script>

<template>
  <aside class="chat-prompt-sidebar" aria-label="对话系统提示词设置">
    <header class="chat-prompt-sidebar__header">
      <div>
        <p>对话系统提示词设置</p>
        <h3>单选一个系统提示词</h3>
      </div>
      <RouterLink class="chat-prompt-sidebar__manage" to="/prompts">管理</RouterLink>
    </header>

    <p v-if="notice" class="chat-prompt-sidebar__notice" role="alert">{{ notice }}</p>
    <p v-if="configuration === null" class="chat-prompt-sidebar__empty">正在加载提示词。</p>
    <p v-else-if="configuration.prompts.length === 0" class="chat-prompt-sidebar__empty">还没有提示词，到 Prompt 页创建。</p>

    <div v-else class="chat-prompt-sidebar__list" role="radiogroup" aria-label="选择系统提示词">
      <label v-for="prompt in configuration.prompts" :key="prompt.id" class="chat-prompt-sidebar__item" role="radio" :aria-checked="prompt.id === chosenPromptId">
        <input type="radio" name="chat-system-prompt" :checked="prompt.id === chosenPromptId" :disabled="isSaving" @change="choose(prompt.id)">
        <span><strong>{{ prompt.label }}</strong><small>{{ prompt.isDefault ? "默认提示词" : "自定义提示词" }}</small></span>
      </label>
    </div>

    <button class="chat-prompt-sidebar__save" type="button" :disabled="isSaving || configuration === null" @click="saveSelection">
      <Check :size="15" aria-hidden="true" />{{ isSaving ? "保存中" : "保存选择" }}
    </button>
  </aside>
</template>

<style scoped>
.chat-prompt-sidebar { background: var(--surface-raised); border-left: 1px solid var(--line); display: grid; gap: 0.8rem; grid-auto-rows: max-content; min-width: 0; overflow: auto; padding: 1rem; }
.chat-prompt-sidebar__header { align-items: center; display: flex; gap: 0.8rem; justify-content: space-between; }
.chat-prompt-sidebar__header p { color: var(--text-primary); font-size: 0.86rem; font-weight: 700; margin: 0; }
.chat-prompt-sidebar__header h3 { color: var(--text-tertiary); font-size: 0.72rem; font-weight: 600; margin: 0.22rem 0 0; }
.chat-prompt-sidebar__manage { align-items: center; border: 1px solid var(--line-strong); border-radius: 0.5rem; color: var(--text-secondary); display: inline-flex; font-size: 0.78rem; min-height: 2.2rem; padding: 0 0.65rem; }
.chat-prompt-sidebar__manage:hover { background: var(--surface-hover); border-color: var(--accent-border); color: var(--text-primary); }
.chat-prompt-sidebar__notice { background: var(--danger-soft); border: 1px solid var(--status-danger-border); border-radius: 0.5rem; color: var(--status-danger-text); font-size: 0.78rem; line-height: 1.5; margin: 0; padding: 0.55rem 0.65rem; }
.chat-prompt-sidebar__empty { color: var(--text-tertiary); font-size: 0.78rem; margin: 0; }
.chat-prompt-sidebar__list { display: grid; gap: 0.55rem; }
.chat-prompt-sidebar__item { align-items: start; border: 1px solid var(--line); border-radius: 0.5rem; display: grid; gap: 0.5rem; grid-template-columns: auto minmax(0, 1fr); padding: 0.58rem; }
.chat-prompt-sidebar__item:hover { background: var(--surface-hover); }
.chat-prompt-sidebar__item input { margin-top: 0.15rem; }
.chat-prompt-sidebar__item span { display: grid; gap: 0.18rem; min-width: 0; }
.chat-prompt-sidebar__item strong { color: var(--text-primary); font-size: 0.78rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-prompt-sidebar__item small { color: var(--text-tertiary); font-size: 0.72rem; }
.chat-prompt-sidebar__save { align-items: center; background: var(--accent); border-radius: 0.45rem; color: white; display: inline-flex; font-size: 0.78rem; font-weight: 700; gap: 0.35rem; min-height: 2.35rem; padding: 0 0.72rem; width: fit-content; }
button:disabled, input:disabled { cursor: not-allowed; opacity: 0.55; }
@media (max-width: 1120px) { .chat-prompt-sidebar { border-left: 0; border-top: 1px solid var(--line); max-height: none; } }
</style>
