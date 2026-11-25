<script setup lang="ts">
import { ArrowUpRight, BookOpen, Bot, Clock3, Layers3, Stethoscope, Wrench } from "lucide-vue-next";
import { computed, ref, watch } from "vue";

import type { ChatMessage, ToolCallAudit } from "@agent-java/api-contracts";
import type { ChatReference, LiveToolCall } from "../stores/chat";
import AppLoadingState from "./AppLoadingState.vue";
import AsyncStatusBadge from "./AsyncStatusBadge.vue";
import ChatCitationDetail from "./ChatCitationDetail.vue";
import MarkdownContent from "./MarkdownContent.vue";
import RetrievalStageTrace from "./RetrievalStageTrace.vue";
import UserFeedbackControl from "./UserFeedbackControl.vue";

const emit = defineEmits<{ "open-document": [reference: ChatReference]; suggest: [content: string] }>();
const props = defineProps<{
  readonly isLoading: boolean;
  readonly liveToolCalls: readonly LiveToolCall[];
  readonly messages: readonly ChatMessage[];
  readonly references: readonly ChatReference[];
  readonly toolAudits: readonly ToolCallAudit[];
}>();

const viewport = ref<HTMLElement | null>(null);
let followLatest = true;
function trackScroll(): void { const el = viewport.value; if (el) followLatest = el.scrollHeight - el.scrollTop - el.clientHeight < 100; }
watch(() => [props.messages.length, props.messages.at(-1)?.content.length], () => { const el = viewport.value; if (el && followLatest) el.scrollTop = el.scrollHeight; }, { flush: "post" });
const starters = [
  { icon: BookOpen, label: "检索知识", detail: "让答案有迹可循", query: "请检索知识库中数据库连接池的排查方法，并给出来源。" },
  { icon: Stethoscope, label: "分析问题", detail: "从现象走向下一步", query: "帮我整理服务响应变慢时的排查思路，以及需要收集的运行信号。" },
  { icon: Clock3, label: "调用工具", detail: "获取此刻的真实信息", query: "请调用时间工具，告诉我现在的北京时间。" }
];
const selectedCitation = ref<ChatReference | null>(null);
const selectedCitationMessageId = ref<string | null>(null);
const latestAssistantMessageId = computed(() =>
  [...props.messages].reverse().find((message) => message.role === "assistant")?.id ?? null
);

function selectCitation(reference: ChatReference, messageId: string | null): void {
  selectedCitation.value = reference;
  selectedCitationMessageId.value = messageId;
}

function canCollectFeedback(message: ChatMessage): boolean {
  return message.role === "assistant" && !message.id.startsWith("message_draft_");
}

function sourceLabel(reference: ChatReference): string {
  if (reference.knowledgeType === "sop") return "SOP";
  if (reference.knowledgeType === "diagnostic-case") return "故障案例";
  return reference.knowledgeType === "document" || reference.sourceType === "document"
    ? "知识库"
    : reference.sourceType;
}

function messageAudits(message: ChatMessage): readonly ToolCallAudit[] {
  const ids = new Set(message.metadata.toolCallIds ?? []);
  return props.toolAudits.filter((audit) => ids.has(audit.id));
}
</script>

<template>
  <section ref="viewport" class="chat-transcript" aria-label="当前对话" @scroll="trackScroll">
    <AppLoadingState v-if="isLoading" label="正在加载对话" />
    <ol v-else-if="messages.length > 0" class="chat-transcript__messages">
      <li v-for="message in messages" :key="message.id" :class="`chat-transcript__message--${message.role}`">
        <article class="chat-transcript__message">
          <span class="chat-transcript__role"><Bot v-if="message.role === 'assistant'" :size="15" aria-hidden="true" />{{ message.role === "assistant" ? "助手" : "你" }}</span>
          <MarkdownContent v-if="message.role === 'assistant'" :content="message.content" />
          <p v-else>{{ message.content }}</p>
          <ul v-if="message.metadata.citations?.length" class="chat-transcript__message-sources" aria-label="回答引用来源">
            <li v-for="reference in message.metadata.citations" :key="reference.id"><button type="button" :aria-label="`查看 ${reference.title} 的来源详情`" @click="selectCitation(reference, message.id)"><BookOpen :size="13" aria-hidden="true" />{{ reference.title }}</button><RetrievalStageTrace :reference="reference" compact /><UserFeedbackControl v-if="canCollectFeedback(message)" target-type="citation" :target-id="message.id" :subject-id="reference.id" compact /></li>
          </ul>
          <UserFeedbackControl v-if="canCollectFeedback(message)" target-type="chat_message" :target-id="message.id" compact />
          <details v-if="message.role === 'assistant' && messageAudits(message).length" class="chat-transcript__details"><summary><Wrench :size="14" aria-hidden="true" />工具调用（{{ messageAudits(message).length }}）</summary><ul><li v-for="audit in messageAudits(message)" :key="audit.id"><span>{{ audit.toolName }}</span><AsyncStatusBadge :status="audit.status" compact /><small v-if="audit.resultSummary || audit.errorMessage">{{ audit.resultSummary ?? audit.errorMessage }}</small></li></ul></details>
          <details v-if="message.role === 'assistant' && message.metadata.reasoning?.length" class="chat-transcript__details"><summary>深度思考</summary><p>{{ message.metadata.reasoning.join('') }}</p></details>
        </article>
      </li>
    </ol>
    <div v-if="!isLoading && messages.length === 0" class="chat-welcome"><div class="chat-welcome__mark"><Layers3 :size="30" stroke-width="1.5" aria-hidden="true" /></div><h2>从一个问题，<br />开始一次清晰的思考。</h2><p class="chat-welcome__description">连接你的知识与工具，让每一次回答更有依据。</p><div class="chat-welcome__starters"><button v-for="starter in starters" :key="starter.label" type="button" @click="emit('suggest', starter.query)"><component :is="starter.icon" :size="19" stroke-width="1.6" aria-hidden="true" /><strong>{{ starter.label }}</strong><small>{{ starter.detail }}</small><ArrowUpRight :size="14" aria-hidden="true" /></button></div></div>
    <aside v-if="liveToolCalls.length > 0" class="chat-transcript__context" aria-label="正在进行的工具调用">
      <h2><Wrench :size="15" aria-hidden="true" />工具调用</h2>
      <ul>
        <li v-for="tool in liveToolCalls" :key="tool.id"><span>{{ tool.name }}</span><AsyncStatusBadge :status="tool.status" compact /></li>
      </ul>
    </aside>
    <aside v-if="references.length > 0" class="chat-transcript__context" aria-label="本次回答引用的来源">
      <h2><BookOpen :size="15" aria-hidden="true" />本次回答引用</h2>
      <ul><li v-for="reference in references" :key="reference.id"><div class="chat-transcript__reference-heading"><span>{{ sourceLabel(reference) }}</span><button type="button" :aria-label="`查看 ${reference.title} 的来源详情`" @click="selectCitation(reference, latestAssistantMessageId)">{{ reference.title }}</button></div><RetrievalStageTrace :reference="reference" compact /><UserFeedbackControl v-if="latestAssistantMessageId" target-type="citation" :target-id="latestAssistantMessageId" :subject-id="reference.id" compact /></li></ul>
    </aside>
    <ChatCitationDetail v-if="selectedCitation && selectedCitationMessageId" :message-id="selectedCitationMessageId" :reference="selectedCitation" @close="selectedCitation = null; selectedCitationMessageId = null" @open-document="emit('open-document', $event)" />
  </section>
</template>

<style scoped>
.chat-transcript { display: flex; flex-direction: column; gap: 1.3rem; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 1.6rem clamp(1rem, 3vw, 3rem); }
.chat-transcript__messages { display: grid; gap: 1.65rem; list-style: none; margin: 0 auto; max-width: 52rem; padding: 0; width: 100%; }.chat-transcript__message--user { display: flex; justify-content: flex-end; }.chat-transcript__message { max-width: min(48rem, 94%); min-width: 0; }.chat-transcript__message--user .chat-transcript__message { background: var(--surface-inset); border-radius: 0.9rem 0.9rem 0.25rem 0.9rem; max-width: 85%; padding: 0.9rem 1.1rem; }.chat-transcript__message--user .chat-transcript__role { font-size: 0.72rem; }
.chat-transcript__role { align-items: center; color: var(--text-secondary); display: inline-flex; font-size: 0.78rem; font-weight: 600; gap: 0.45rem; margin-bottom: 0.55rem; }.chat-transcript__role svg { color: var(--accent); }.chat-transcript__message p { font-size: 0.9rem; line-height: 1.85; margin: 0; overflow-wrap: anywhere; white-space: pre-wrap; word-break: break-word; }
.chat-transcript__message-sources { display: grid; gap: 0.6rem; list-style: none; margin: 0.85rem 0 0; padding: 0; }.chat-transcript__message-sources li { display: grid; gap: 0.35rem; justify-items: start; }.chat-transcript__message-sources li button { align-items: center; background: var(--accent-soft); border: 1px solid var(--accent-border); border-radius: 0.4rem; color: var(--accent-strong); display: inline-flex; font-size: 0.72rem; gap: 0.35rem; padding: 0.35rem 0.6rem; }
.chat-transcript__details { border-top: 1px solid var(--line); color: var(--text-secondary); font-size: 0.78rem; margin-top: 0.85rem; padding-top: 0.7rem; }.chat-transcript__details summary { align-items: center; cursor: pointer; display: flex; gap: 0.35rem; }.chat-transcript__details ul { display: grid; gap: 0.5rem; list-style: none; padding: 0; }.chat-transcript__details li { align-items: center; display: grid; gap: 0.5rem; grid-template-columns: minmax(0, 1fr) auto; }.chat-transcript__details li small { color: var(--text-tertiary); grid-column: 1 / -1; overflow-wrap: anywhere; }.chat-transcript__details p { color: var(--text-secondary); font-size: 0.78rem; margin-top: 0.5rem; }
.chat-transcript__context { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-md); max-width: 52rem; padding: 0.9rem 1rem; width: 100%; }.chat-transcript__context h2 { align-items: center; color: var(--text-secondary); display: flex; font-size: 0.78rem; gap: 0.4rem; margin: 0 0 0.7rem; }.chat-transcript__context ul { display: grid; gap: 0.5rem; list-style: none; margin: 0; padding: 0; }.chat-transcript__context li { display: grid; gap: 0.4rem; }.chat-transcript__context li > span { font-size: 0.78rem; }.chat-transcript__reference-heading { align-items: center; display: flex; gap: 0.5rem; }.chat-transcript__reference-heading > span { color: var(--text-tertiary); font-size: 0.72rem; }.chat-transcript__reference-heading > button { font-size: 0.78rem; text-align: left; }.chat-transcript__reference-heading > button:hover { color: var(--accent); }
.chat-welcome { margin: auto; max-width: 40rem; padding: 1rem 0 2rem; text-align: center; width: 100%; }.chat-welcome__mark { align-items: center; background: var(--accent-soft); border: 1px solid var(--accent-border); border-radius: 1rem; color: var(--accent); display: flex; height: 4rem; justify-content: center; margin: 0 auto 1.7rem; width: 4rem; }.chat-welcome h2 { font-size: clamp(1.7rem, 2.6vw, 2.35rem); font-weight: 600; letter-spacing: -0.045em; line-height: 1.45; margin: 0.8rem 0; }.chat-welcome__description { color: var(--text-secondary); font-size: 0.82rem; line-height: 1.7; }.chat-welcome__starters { display: grid; gap: 0.7rem; grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: 2.3rem; }.chat-welcome__starters button { border: 1px solid var(--line); border-radius: 0.8rem; display: grid; gap: 0.65rem; padding: 1rem; position: relative; text-align: left; }.chat-welcome__starters button:hover { background: var(--surface); border-color: var(--accent-border); }.chat-welcome__starters svg { color: var(--accent); }.chat-welcome__starters svg:last-child { color: var(--text-tertiary); position: absolute; right: 0.75rem; top: 0.9rem; }.chat-welcome__starters strong { font-size: 0.78rem; font-weight: 500; }.chat-welcome__starters small { color: var(--text-tertiary); font-size: 0.72rem; }
@media (max-width: 760px) { .chat-transcript { padding: 1rem; }.chat-welcome { padding: 0.7rem 0; }.chat-welcome__mark { height: 3rem; margin-bottom: 1rem; width: 3rem; }.chat-welcome h2 { font-size: 1.7rem; }.chat-welcome__starters { gap: 0.4rem; margin-top: 1.5rem; }.chat-welcome__starters button { padding: 0.75rem 0.6rem; }.chat-welcome__starters small { display: none; } }
</style>
