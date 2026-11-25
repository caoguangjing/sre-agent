<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { PanelRightClose, PanelRightOpen, SlidersHorizontal, Sparkles } from "lucide-vue-next";
import { useRouter } from "vue-router";

import type { ReferenceSourceSseEvent } from "@agent-java/api-contracts";

import ChatComposer from "../components/ChatComposer.vue";
import ChatPromptSidebar from "../components/ChatPromptSidebar.vue";
import ChatSkillSidebar from "../components/ChatSkillSidebar.vue";
import ChatTranscript from "../components/ChatTranscript.vue";
import { useChatStore } from "../stores/chat";

const chat = useChatStore();
const router = useRouter();
const settingsOpen = ref(typeof window === "undefined" || window.innerWidth > 900);
const settingsTab = ref<"prompt" | "skill">("prompt");
const composer = ref<InstanceType<typeof ChatComposer> | null>(null);
const activeTitle = computed(() =>
  (chat.sessions.find((session) => session.id === chat.activeSessionId)?.title ?? "新对话").replace(/^New chat$/, "新对话")
);

onMounted(() => {
  void chat.initialize().catch(() => undefined);
});

function run(operation: () => Promise<unknown>): void {
  void operation().catch(() => undefined);
}

function openCitationDocument(reference: ReferenceSourceSseEvent["reference"]): void {
  if (reference.documentId === undefined || reference.knowledgeBaseId === undefined) return;
  void router.push({
    name: "knowledge",
    query: { documentId: reference.documentId, knowledgeBaseId: reference.knowledgeBaseId }
  });
}

function saveChatConfiguration(systemPromptId: string, skillIds: readonly string[]): void {
  run(() => chat.saveConfiguration(systemPromptId, skillIds));
}

</script>

<template>
  <section class="chat-view" :class="{ 'chat-view--expanded': !settingsOpen }" aria-label="对话工作区">
    <div class="chat-view__conversation">
      <header class="chat-view__header"><div><h2>{{ activeTitle }}</h2></div><div class="chat-view__actions"><span class="ui-pill"><Sparkles :size="12" aria-hidden="true" />智能助手</span><button class="ui-icon-button" type="button" :aria-expanded="settingsOpen" aria-controls="chat-settings" :aria-label="settingsOpen ? '收起对话配置' : '展开对话配置'" :title="settingsOpen ? '收起对话配置' : '展开对话配置'" @click="settingsOpen = !settingsOpen"><PanelRightClose v-if="settingsOpen" :size="16" aria-hidden="true" /><PanelRightOpen v-else :size="16" aria-hidden="true" /></button></div></header>
      <ChatTranscript
        :is-loading="chat.isLoading"
        :live-tool-calls="chat.liveToolCalls"
        :messages="chat.messages"
        :references="chat.references"
        :tool-audits="chat.toolAudits"
        @open-document="openCitationDocument"
        @suggest="composer?.setDraft($event)"
      />
      <ChatComposer
        ref="composer"
        :disabled="chat.isLoading"
        :is-sending="chat.isSending"
        :is-updating-memory="chat.isUpdatingMemory"
        :memory="chat.activeSession?.memory ?? null"
        @apply-memory="run(() => chat.updateMemoryMode($event))"
        @compact-memory="run(() => chat.compactMemory())"
        @send="run(() => chat.send($event))"
      />
    </div>
    <aside v-show="settingsOpen" id="chat-settings" class="chat-view__settings" aria-label="对话配置"><header><span><SlidersHorizontal :size="15" aria-hidden="true" />对话配置</span><small>塑造助手的工作方式</small></header><div class="ui-tabs chat-view__tabs" aria-label="配置类型"><button type="button" :class="{ 'is-active': settingsTab === 'prompt' }" :aria-pressed="settingsTab === 'prompt'" @click="settingsTab = 'prompt'">系统提示词</button><button type="button" :class="{ 'is-active': settingsTab === 'skill' }" :aria-pressed="settingsTab === 'skill'" @click="settingsTab = 'skill'">Skills<span>{{ chat.configuration?.selection.skillIds.length ?? 0 }}</span></button></div><div class="chat-view__settings-body">
      <ChatPromptSidebar
        v-show="settingsTab === 'prompt'"
        :configuration="chat.configuration"
        :is-saving="chat.isSavingConfiguration"
        @save="saveChatConfiguration"
      />
      <ChatSkillSidebar
        v-show="settingsTab === 'skill'"
        :configuration="chat.configuration"
        :is-saving="chat.isSavingConfiguration"
        @save="saveChatConfiguration"
      />
    </div></aside>
  </section>
</template>

<style scoped>
.chat-view { background: var(--surface-raised); display: grid; grid-template-columns: minmax(0, 1fr) 20rem; height: 100%; min-height: 0; overflow: hidden; }
.chat-view--expanded { grid-template-columns: minmax(0, 1fr); }
.chat-view__conversation { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; min-height: 0; min-width: 0; }
.chat-view__header { align-items: center; border-bottom: 1px solid var(--line); display: flex; gap: 1rem; justify-content: space-between; min-height: 5.3rem; padding: 1rem 2rem; }
.chat-view__header > div:first-child { min-width: 0; }.chat-view__header p { margin-bottom: 0.35rem; }.chat-view__header h2 { font-size: 1.07rem; font-weight: 600; margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-view__actions { align-items: center; display: flex; flex-shrink: 0; gap: 0.75rem; }.chat-view__actions .ui-pill { border: 0; color: var(--accent); }
.chat-view__settings { background: var(--surface); border-left: 1px solid var(--line); display: flex; flex-direction: column; min-height: 0; min-width: 0; }
.chat-view__settings > header { display: grid; gap: 0.4rem; padding: 1.35rem 1.2rem 1rem; }.chat-view__settings > header > span { align-items: center; display: flex; font-size: 0.82rem; font-weight: 600; gap: 0.5rem; }.chat-view__settings > header small { color: var(--text-tertiary); font-size: 0.78rem; padding-left: 1.45rem; }
.chat-view__tabs { background: var(--surface-raised); border: 1px solid var(--line); border-radius: 0.6rem; margin: 0 1.1rem 1rem; padding: 0.25rem; }.chat-view__tabs button { flex: 1; justify-content: center; padding: 0 0.4rem; }
.chat-view__settings-body { flex: 1; min-height: 0; overflow-y: auto; }.chat-view__settings :deep(.chat-prompt-sidebar), .chat-view__settings :deep(.chat-skill-sidebar) { background: transparent; border: 0; height: auto; overflow: visible; padding: 0 1.1rem 1.2rem; }.chat-view__settings :deep(.chat-prompt-sidebar__header h3), .chat-view__settings :deep(.chat-skill-sidebar__header h3) { font-size: 0.82rem; }.chat-view__settings :deep(.chat-prompt-sidebar__header p), .chat-view__settings :deep(.chat-skill-sidebar__header p) { display: none; }.chat-view__settings :deep(.chat-prompt-sidebar__item), .chat-view__settings :deep(.chat-skill-sidebar__item) { background: var(--surface-raised); border-radius: 0.65rem; }
@media (max-width: 1120px) { .chat-view { grid-template-columns: minmax(0, 1fr) 18rem; }.chat-view__header { padding: 1rem 1.25rem; } }
@media (max-width: 900px) { .chat-view { position: relative; grid-template-columns: minmax(0, 1fr); }.chat-view__settings { border-left: 1px solid var(--line); bottom: 0; box-shadow: -12px 0 30px rgb(2 6 16 / 50%); position: absolute; right: 0; top: 5.3rem; width: min(20rem, 90%); z-index: 8; } }
@media (max-width: 760px) { .chat-view__header { min-height: 4.5rem; padding: 0.7rem 1rem; }.chat-view__header p { font-size: 0.72rem; }.chat-view__header h2 { font-size: 0.9rem; }.chat-view__settings { top: 4.5rem; }.chat-view__actions .ui-pill { display: none; } }
</style>
