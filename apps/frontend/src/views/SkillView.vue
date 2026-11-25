<script setup lang="ts">
import { Eye, EyeOff, Puzzle, Trash2, Upload } from "lucide-vue-next";
import { computed, onMounted, ref } from "vue";

import AppLoadingState from "../components/AppLoadingState.vue";
import { useChatStore } from "../stores/chat";

const chat = useChatStore();
const notice = ref<string | null>(null);
const viewingId = ref<string | null>(null);

const skills = computed(() => chat.configuration?.skills ?? []);
const visibleContent = computed(() =>
  viewingId.value !== null && chat.loadedSkillContent?.id === viewingId.value ? chat.loadedSkillContent : null
);

onMounted(() => {
  void chat.reloadConfiguration().catch(() => undefined);
});

function formatSize(bytes: number): string {
  return bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${bytes} B`;
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString("zh-CN", { dateStyle: "short", timeStyle: "short" });
}

function uploadSkill(event: Event): void {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (file === undefined) return;
  if (file.name !== "SKILL.md") {
    notice.value = "Skill 文件名必须严格为 SKILL.md。";
    return;
  }
  if (file.size === 0) {
    notice.value = "Skill 文件不能为空，请上传 UTF-8 Markdown 文本。";
    return;
  }
  if (file.size > 64 * 1024) {
    notice.value = "Skill 文件不能超过 64 KB。";
    return;
  }
  notice.value = null;
  void chat.uploadSkill(file).catch(() => undefined);
}

async function toggleContent(skillId: string): Promise<void> {
  if (viewingId.value === skillId) {
    viewingId.value = null;
    return;
  }
  viewingId.value = skillId;
  try {
    await chat.loadSkillContent(skillId);
  } catch {
    viewingId.value = null;
  }
}

function removeSkill(skillId: string): void {
  if (viewingId.value === skillId) viewingId.value = null;
  void chat.deleteSkill(skillId).catch(() => undefined);
}
</script>

<template>
  <section class="skill-view" aria-label="Skill 管理">
    <header class="skill-view__header ui-page-heading">
      <div><h2>把排查经验沉淀成 Skill。</h2><p>上传 SKILL.md 供对话按需加载，也可在智能诊断中作为排查指引。</p></div>
      <label class="ui-button ui-button--primary skill-view__upload" title="上传 Skill">
        <Upload :size="16" aria-hidden="true" />上传 Skill
        <input aria-label="上传 Skill 文件" :disabled="chat.isSavingConfiguration" type="file" accept=".md" @change="uploadSkill">
      </label>
    </header>
    <section class="skill-view__rules" aria-label="Skill 上传规范">
      <strong>上传规范</strong>
      <ul>
        <li>文件名必须严格为 <code>SKILL.md</code>，YAML frontmatter 需包含合法的 <code>name</code> 和 <code>description</code>。</li>
        <li>内容必须是 UTF-8 Markdown，不能为空，单个文件不超过 64 KB；name 不能与已有 Skill 重复。</li>
        <li>对话中模型按需读取正文；智能诊断中正文会注入规划与报告提示词作为排查指引。</li>
      </ul>
    </section>
    <p v-if="notice" class="skill-view__notice" role="alert">{{ notice }}</p>
    <AppLoadingState v-if="chat.isSavingConfiguration && skills.length === 0" label="正在加载 Skill" />
    <p v-else-if="skills.length === 0" class="skill-view__empty">还没有上传 Skill，点击「上传 Skill」把团队的排查经验沉淀进来。</p>
    <div v-else class="skill-view__list" role="list">
      <article v-for="skill in skills" :key="skill.id" class="skill-view__item" role="listitem">
        <div class="skill-view__row">
          <span class="skill-view__icon"><Puzzle :size="17" aria-hidden="true" /></span>
          <span class="skill-view__meta">
            <strong>{{ skill.name }}</strong>
            <small>{{ skill.description }}</small>
            <small class="skill-view__hint">{{ formatSize(skill.sizeBytes) }} · 更新于 {{ formatDate(skill.updatedAt) }}</small>
          </span>
          <span class="skill-view__actions">
            <button type="button" :disabled="chat.isLoadingSkillContent" :title="viewingId === skill.id ? '收起正文' : '查看正文'" @click="toggleContent(skill.id)">
              <EyeOff v-if="viewingId === skill.id" :size="15" aria-hidden="true" /><Eye v-else :size="15" aria-hidden="true" />{{ viewingId === skill.id ? "收起" : "查看正文" }}
            </button>
            <button type="button" class="skill-view__delete" :disabled="chat.isSavingConfiguration" title="删除 Skill" @click="removeSkill(skill.id)">
              <Trash2 :size="15" aria-hidden="true" />删除
            </button>
          </span>
        </div>
        <pre v-if="visibleContent?.id === skill.id" class="skill-view__content">{{ visibleContent.content }}</pre>
      </article>
    </div>
  </section>
</template>

<style scoped>
.skill-view { display: grid; gap: 1.3rem; align-content: start; height: 100%; min-height: 0; overflow-y: auto; padding: 1.8rem 1.6rem 1.6rem; }
.skill-view__upload { position: relative; cursor: pointer; }
.skill-view__upload input { height: 1px; opacity: 0; overflow: hidden; position: absolute; width: 1px; }
.skill-view__upload:focus-within { outline: 2px solid var(--focus-ring); outline-offset: 3px; }
.skill-view__rules { background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-md); padding: 0.9rem 1rem; }
.skill-view__rules strong { color: var(--text-primary); font-size: 0.82rem; }
.skill-view__rules ul { color: var(--text-secondary); display: grid; font-size: 0.78rem; gap: 0.4rem; line-height: 1.6; margin: 0.5rem 0 0; padding-left: 1.1rem; }
.skill-view__rules code { background: var(--surface-inset); border-radius: 0.28rem; color: var(--accent-strong); padding: 0.08rem 0.28rem; }
.skill-view__notice { background: var(--danger-soft); border: 1px solid var(--status-danger-border); border-radius: 0.5rem; color: var(--status-danger-text); font-size: 0.78rem; line-height: 1.5; margin: 0; padding: 0.55rem 0.7rem; }
.skill-view__empty { background: var(--surface-raised); border: 1px dashed var(--line-strong); border-radius: var(--radius-md); color: var(--text-secondary); font-size: 0.82rem; line-height: 1.6; margin: 0; padding: 1.4rem; }
.skill-view__list { display: grid; gap: 0.7rem; }
.skill-view__item { background: var(--surface-raised); border: 1px solid var(--line); border-radius: var(--radius-md); padding: 0.85rem 1rem; }
.skill-view__row { align-items: center; display: grid; gap: 0.8rem; grid-template-columns: auto minmax(0, 1fr) auto; }
.skill-view__icon { align-items: center; background: var(--accent-soft); border-radius: 0.6rem; color: var(--accent); display: inline-flex; height: 2.3rem; justify-content: center; width: 2.3rem; }
.skill-view__meta { display: grid; gap: 0.2rem; min-width: 0; }
.skill-view__meta strong { color: var(--text-primary); font-size: 0.86rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.skill-view__meta small { color: var(--text-secondary); font-size: 0.78rem; line-height: 1.5; overflow-wrap: anywhere; }
.skill-view__hint { color: var(--text-tertiary) !important; font-size: 0.72rem !important; }
.skill-view__actions { display: grid; gap: 0.4rem; }
.skill-view__actions button { align-items: center; border: 1px solid var(--line-strong); border-radius: 0.5rem; color: var(--text-secondary); display: inline-flex; font-size: 0.78rem; gap: 0.35rem; justify-content: center; min-height: 2.2rem; padding: 0 0.7rem; }
.skill-view__actions button:hover:not(:disabled) { background: var(--surface-hover); color: var(--text-primary); }
.skill-view__delete { color: var(--status-danger-text) !important; }
.skill-view__content { background: var(--surface-inset); border: 1px solid var(--line); border-radius: 0.6rem; color: var(--text-secondary); font: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.78rem; line-height: 1.65; margin: 0.85rem 0 0; max-height: 26rem; overflow: auto; padding: 0.9rem; white-space: pre-wrap; }
@media (max-width: 760px) { .skill-view { padding: 1.25rem 1rem; }.skill-view__row { grid-template-columns: minmax(0, 1fr); }.skill-view__icon { display: none; }.skill-view__actions { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
