<script setup lang="ts">
import { ArrowUp, BrainCircuit, LoaderCircle } from "lucide-vue-next";
import { computed, nextTick, ref, watch } from "vue";

import type { ChatMemoryMode, ChatMemoryState } from "@agent-java/api-contracts";

const emit = defineEmits<{
  send: [content: string];
  applyMemory: [mode: ChatMemoryMode];
  compactMemory: [];
}>();
const props = defineProps<{
  readonly disabled: boolean;
  readonly isSending: boolean;
  readonly isUpdatingMemory: boolean;
  readonly memory: ChatMemoryState | null;
}>();
const content = ref("");
const input = ref<HTMLTextAreaElement | null>(null);
defineExpose({ setDraft(value: string): void { content.value = value; void nextTick(() => input.value?.focus()); } });
const selectedMode = ref<ChatMemoryMode>(props.memory?.mode ?? "every_30_turns");
const isHardLimited = computed(() => (props.memory?.contextUsagePercent ?? 0) >= 95);
const inputDisabled = computed(() => props.disabled || props.isSending || isHardLimited.value);

watch(
  () => props.memory?.mode,
  (mode) => {
    if (mode !== undefined) selectedMode.value = mode;
  }
);

function submit(): void {
  const nextContent = content.value.trim();
  if (nextContent.length === 0 || inputDisabled.value) return;
  emit("send", nextContent);
  content.value = "";
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key !== "Enter" || event.shiftKey || event.isComposing) return;
  event.preventDefault();
  submit();
}
</script>

<template>
  <form class="chat-composer" @submit.prevent="submit">
    <label class="chat-composer__label" for="chat-message">输入问题</label>
    <div class="chat-composer__row">
      <div class="chat-composer__field">
        <textarea ref="input" id="chat-message" v-model="content" rows="2" placeholder="描述你的问题，让知识与工具一起参与思考…" :disabled="inputDisabled" @keydown="handleKeydown" />
        <button type="submit" title="发送消息" aria-label="发送消息" :disabled="inputDisabled || !content.trim()"><LoaderCircle v-if="isSending" class="chat-composer__spin" :size="18" aria-hidden="true" /><ArrowUp v-else :size="18" aria-hidden="true" /></button>
      </div>
      <aside class="chat-composer__memory" aria-label="会话记忆设置">
        <div class="chat-composer__memory-head">
          <span><BrainCircuit :size="15" aria-hidden="true" />上下文</span>
          <strong>{{ Number((memory?.contextUsagePercent ?? 0).toFixed(2)) }}%</strong>
        </div>
        <progress :value="memory?.contextUsagePercent ?? 0" max="100" aria-label="上下文窗口占用" />
        <div class="chat-composer__memory-actions">
          <select v-model="selectedMode" aria-label="记忆模式" :disabled="!memory || isUpdatingMemory">
            <option value="every_30_turns">每 30 轮压缩</option>
            <option value="context_70_percent">占用 70% 自动压缩</option>
            <option value="manual">手动压缩</option>
          </select>
          <button
            v-if="memory?.mode === 'manual' && selectedMode === 'manual'"
            class="chat-composer__apply"
            type="button"
            :disabled="isUpdatingMemory"
            @click="emit('compactMemory')"
          >{{ isUpdatingMemory ? "压缩中" : "立即压缩" }}</button>
          <button
            v-else
            class="chat-composer__apply"
            type="button"
            :disabled="!memory || isUpdatingMemory || selectedMode === memory?.mode"
            @click="emit('applyMemory', selectedMode)"
          >{{ isUpdatingMemory ? "应用中" : "应用" }}</button>
        </div>
      </aside>
    </div>
    <span class="chat-composer__hint">Enter 发送，Shift+Enter 换行</span>
    <span v-if="isSending" class="chat-composer__status" role="status" aria-live="polite">正在生成回答，请稍候</span>
    <span v-else-if="isHardLimited" class="chat-composer__limit" role="alert">上下文已达到 95%，请执行手动压缩</span>
  </form>
</template>

<style scoped>
.chat-composer { background: var(--surface-subtle); border-top: 1px solid var(--line); display: grid; gap: 0.55rem; padding: 1rem clamp(1rem, 3vw, 3rem) 1.15rem; }
.chat-composer__label { color: var(--text-tertiary); font-size: 0.72rem; }
.chat-composer__row { align-items: center; display: grid; gap: 1rem; grid-template-columns: minmax(0, 1fr) 12.5rem; }
.chat-composer__field { background: var(--surface); border: 1px solid var(--line-strong); border-radius: 0.9rem; display: grid; grid-template-columns: minmax(0, 1fr) auto; padding: 0.45rem; transition: border-color var(--transition-fast), box-shadow var(--transition-fast); }
.chat-composer__field:focus-within { border-color: var(--accent-border); box-shadow: 0 0 0 3px var(--accent-focus); }
textarea { background: transparent; border: 0; color: var(--text-primary); font-size: 0.86rem; line-height: 1.7; min-height: 3.3rem; outline: 0; overflow-y: auto; padding: 0.35rem 0.6rem; resize: none; width: 100%; }
textarea::placeholder { color: var(--text-tertiary); }.chat-composer__field textarea:focus-visible { outline: 0; }
.chat-composer__field button { align-self: end; align-items: center; background: var(--accent); border-radius: 0.65rem; color: white; display: inline-flex; height: 2.15rem; justify-content: center; margin: 0.15rem; width: 2.15rem; }.chat-composer__field button:hover:not(:disabled) { background: var(--accent-strong); }.chat-composer__field button:disabled { background: var(--surface-hover); color: var(--text-tertiary); opacity: 1; }
.chat-composer__memory { display: grid; gap: 0.42rem; }.chat-composer__memory-head { align-items: center; display: flex; justify-content: space-between; }.chat-composer__memory-head span { align-items: center; color: var(--text-secondary); display: inline-flex; font-size: 0.72rem; gap: 0.35rem; }.chat-composer__memory-head strong { color: var(--accent); font-size: 0.78rem; font-variant-numeric: tabular-nums; }
progress { accent-color: var(--accent); height: 0.28rem; width: 100%; }.chat-composer__memory-actions { display: grid; gap: 0.3rem; grid-template-columns: minmax(0, 1fr) auto; }select { background: var(--surface); border: 1px solid var(--line); border-radius: 0.4rem; color: var(--text-secondary); font-size: 0.72rem; min-width: 0; padding: 0.3rem; }.chat-composer__apply { border: 1px solid var(--line); border-radius: 0.4rem; color: var(--accent); font-size: 0.72rem; padding: 0.3rem 0.45rem; white-space: nowrap; }
.chat-composer__hint { color: var(--text-tertiary); font-size: 0.72rem; }.chat-composer__status, .chat-composer__limit { color: var(--accent); font-size: 0.78rem; }.chat-composer__limit { color: var(--danger); }.chat-composer__spin { animation: spin 0.8s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) { .chat-composer { padding: 0.7rem 1rem; }.chat-composer__row { grid-template-columns: minmax(0, 1fr); gap: 0.65rem; }.chat-composer__memory { grid-template-columns: 6rem 1fr; align-items: center; gap: 0.35rem 0.8rem; }.chat-composer__memory progress { display: none; }.chat-composer__label { clip-path: inset(50%); height: 1px; overflow: hidden; position: absolute; width: 1px; } }
</style>
