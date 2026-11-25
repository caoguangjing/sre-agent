<script setup lang="ts">
import { Pencil, Plus, Save, ScrollText, Trash2, X } from "lucide-vue-next";
import { computed, onMounted, reactive, ref } from "vue";

import type { ChatPromptAsset } from "@agent-java/api-contracts";

import AppLoadingState from "../components/AppLoadingState.vue";
import { useChatStore } from "../stores/chat";

const chat = useChatStore();
const notice = ref<string | null>(null);
const editingId = ref<string | null>(null);
const createDraft = reactive({ label: "", content: "" });
const editDraft = reactive({ label: "", content: "" });

const prompts = computed(() => chat.configuration?.prompts ?? []);
const creating = computed(() => createDraft.label.trim().length > 0 && createDraft.content.trim().length > 0);

onMounted(() => {
  void chat.reloadConfiguration().catch(() => undefined);
});

function fail(message: string): boolean {
  notice.value = message;
  return false;
}

async function createPrompt(): Promise<void> {
  const label = createDraft.label.trim();
  const content = createDraft.content.trim();
  if (label.length === 0 || content.length === 0) {
    fail("名称与内容都不能为空。");
    return;
  }
  if (label.length > 160) {
    fail("名称不能超过 160 字。");
    return;
  }
  if (content.length > 12000) {
    fail("内容不能超过 12000 字。");
    return;
  }
  notice.value = null;
  try {
    await chat.createPrompt(label, content);
    createDraft.label = "";
    createDraft.content = "";
  } catch {
    /* store 已记录错误 */
  }
}

function startEdit(prompt: ChatPromptAsset): void {
  editingId.value = prompt.id;
  editDraft.label = prompt.label;
  editDraft.content = prompt.content;
  notice.value = null;
}

function cancelEdit(): void {
  editingId.value = null;
}

async function saveEdit(): Promise<void> {
  if (editingId.value === null) return;
  const label = editDraft.label.trim();
  const content = editDraft.content.trim();
  if (label.length === 0 || content.length === 0) {
    fail("名称与内容都不能为空。");
    return;
  }
  if (label.length > 160) {
    fail("名称不能超过 160 字。");
    return;
  }
  if (content.length > 12000) {
    fail("内容不能超过 12000 字。");
    return;
  }
  notice.value = null;
  try {
    await chat.updatePrompt(editingId.value, label, content);
    editingId.value = null;
  } catch {
    /* store 已记录错误 */
  }
}

function removePrompt(promptId: string): void {
  if (editingId.value === promptId) editingId.value = null;
  void chat.deletePrompt(promptId).catch(() => undefined);
}
</script>

<template>
  <section class="prompt-view" aria-label="Prompt 管理">
    <header class="prompt-view__header ui-page-heading">
      <div><h2>管理你自己的提示词。</h2><p>创建可复用的 Prompt，在对话侧栏勾选后注入系统提示词。</p></div>
    </header>
    <section class="prompt-view__editor" aria-label="新建 Prompt">
      <header><ScrollText :size="17" aria-hidden="true" /><h3>新建 Prompt</h3></header>
      <form @submit.prevent="createPrompt">
        <label><span>名称</span><input v-model="createDraft.label" maxlength="160" placeholder="例如：SLS 日志分析专家" /></label>
        <label><span>内容</span><textarea v-model="createDraft.content" maxlength="12000" placeholder="这段文字会注入对话系统提示词，约束回答口径与工作方式。" rows="6"></textarea></label>
        <div class="prompt-view__editor-actions">
          <button class="ui-button ui-button--primary" :disabled="!creating || chat.isSavingConfiguration" type="submit"><Plus :size="15" aria-hidden="true" />创建</button>
        </div>
      </form>
    </section>
    <p v-if="notice" class="prompt-view__notice" role="alert">{{ notice }}</p>
    <AppLoadingState v-if="chat.isSavingConfiguration && prompts.length === 0" label="正在加载 Prompt" />
    <p v-else-if="prompts.length === 0" class="prompt-view__empty">还没有 Prompt，上方创建第一个，之后在对话页侧栏勾选启用。</p>
    <div v-else class="prompt-view__list" role="list">
      <article v-for="prompt in prompts" :key="prompt.id" class="prompt-view__item" role="listitem">
        <template v-if="editingId === prompt.id">
          <form @submit.prevent="saveEdit">
            <label><span>名称</span><input v-model="editDraft.label" maxlength="160" /></label>
            <label><span>内容</span><textarea v-model="editDraft.content" maxlength="12000" rows="6"></textarea></label>
            <div class="prompt-view__item-actions">
              <button type="submit" :disabled="chat.isSavingConfiguration"><Save :size="14" aria-hidden="true" />保存</button>
              <button type="button" @click="cancelEdit"><X :size="14" aria-hidden="true" />取消</button>
            </div>
          </form>
        </template>
        <template v-else>
          <div class="prompt-view__row">
            <span class="prompt-view__meta"><strong>{{ prompt.label }}</strong><small>{{ prompt.content }}</small></span>
            <span class="prompt-view__item-actions">
              <button type="button" title="编辑" @click="startEdit(prompt)"><Pencil :size="14" aria-hidden="true" />编辑</button>
              <button type="button" class="prompt-view__delete" :disabled="chat.isSavingConfiguration" title="删除" @click="removePrompt(prompt.id)"><Trash2 :size="14" aria-hidden="true" />删除</button>
            </span>
          </div>
        </template>
      </article>
    </div>
  </section>
</template>

<style scoped>
.prompt-view { display: grid; gap: 1.3rem; align-content: start; height: 100%; min-height: 0; overflow-y: auto; padding: 1.8rem 1.6rem 1.6rem; }
.prompt-view__editor { background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-md); padding: 1.1rem 1.2rem; }
.prompt-view__editor header { align-items: center; color: var(--accent); display: flex; gap: 0.5rem; }
.prompt-view__editor h3 { font-size: 0.86rem; margin: 0; }
.prompt-view__editor form { display: grid; gap: 0.7rem; margin-top: 0.8rem; }
.prompt-view__editor label, .prompt-view__item form label { color: var(--text-secondary); display: grid; font-size: 0.78rem; font-weight: 600; gap: 0.4rem; }
.prompt-view__editor input, .prompt-view__editor textarea, .prompt-view__item form input, .prompt-view__item form textarea { background: var(--surface); border: 1px solid var(--line-strong); border-radius: 0.5rem; color: var(--text-primary); font: inherit; min-height: 2.5rem; padding: 0.45rem 0.65rem; resize: vertical; width: 100%; }
.prompt-view__editor-actions { display: flex; justify-content: flex-end; }
.prompt-view__notice { background: var(--danger-soft); border: 1px solid var(--status-danger-border); border-radius: 0.5rem; color: var(--status-danger-text); font-size: 0.78rem; line-height: 1.5; margin: 0; padding: 0.55rem 0.7rem; }
.prompt-view__empty { background: var(--surface-raised); border: 1px dashed var(--line-strong); border-radius: var(--radius-md); color: var(--text-secondary); font-size: 0.82rem; line-height: 1.6; margin: 0; padding: 1.4rem; }
.prompt-view__list { display: grid; gap: 0.7rem; }
.prompt-view__item { background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-md); padding: 0.85rem 1rem; }
.prompt-view__item form { display: grid; gap: 0.7rem; }
.prompt-view__row { align-items: start; display: grid; gap: 0.8rem; grid-template-columns: minmax(0, 1fr) auto; }
.prompt-view__meta { display: grid; gap: 0.3rem; min-width: 0; }
.prompt-view__meta strong { color: var(--text-primary); font-size: 0.86rem; }
.prompt-view__meta small { color: var(--text-secondary); font-size: 0.78rem; line-height: 1.55; overflow-wrap: anywhere; white-space: pre-wrap; }
.prompt-view__item-actions { display: flex; gap: 0.4rem; }
.prompt-view__item-actions button { align-items: center; border: 1px solid var(--line-strong); border-radius: 0.5rem; color: var(--text-secondary); display: inline-flex; font-size: 0.78rem; gap: 0.35rem; min-height: 2.2rem; padding: 0 0.7rem; }
.prompt-view__item-actions button:hover:not(:disabled) { background: var(--surface-hover); color: var(--text-primary); }
.prompt-view__delete { color: var(--status-danger-text) !important; }
@media (max-width: 760px) { .prompt-view { padding: 1.25rem 1rem; }.prompt-view__row { grid-template-columns: minmax(0, 1fr); } }
</style>
