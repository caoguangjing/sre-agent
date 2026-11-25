<script setup lang="ts">
import { Activity, BookOpen, Cable, MessageSquare, Puzzle, Radar, ScrollText } from "lucide-vue-next";
defineProps<{ readonly activePath: string }>();
const entries = [
  { icon: MessageSquare, label: "对话", to: "/chat" },
  { icon: Radar, label: "智能巡检", to: "/inspection" },
  { icon: Activity, label: "智能诊断", to: "/aiops" },
  { icon: BookOpen, label: "知识库", to: "/knowledge" },
  { icon: Cable, label: "MCP", to: "/mcp" },
  { icon: Puzzle, label: "Skill", to: "/skills" },
  { icon: ScrollText, label: "Prompt", to: "/prompts" }
] as const;
</script>
<template>
  <nav class="workspace-navigation" aria-label="工作区导航">
    <p class="workspace-navigation__label">工作空间</p>
    <template v-for="entry in entries" :key="entry.to">
      <RouterLink :to="entry.to" class="workspace-navigation__link" :class="{ 'workspace-navigation__link--active': activePath === entry.to }" :aria-current="activePath === entry.to ? 'page' : undefined">
        <component :is="entry.icon" :size="18" stroke-width="1.65" aria-hidden="true" />
        <span>{{ entry.label }}</span>
      </RouterLink>
      <slot v-if="entry.to === '/chat' && activePath === '/chat'" name="chat-history" />
    </template>
  </nav>
</template>
<style scoped>
.workspace-navigation { display: flex; flex-direction: column; gap: 0.4rem; }
.workspace-navigation__label { color: var(--rail-muted); font-size: 0.72rem; letter-spacing: 0.09em; margin: 0.5rem 0.8rem 0.6rem; }
.workspace-navigation__link { align-items: center; border: 1px solid transparent; border-radius: 0.65rem; color: var(--rail-muted); display: flex; font-size: 0.82rem; font-weight: 500; gap: 0.65rem; min-height: 2.85rem; padding: 0 0.8rem; text-decoration: none; }
.workspace-navigation__link:hover { background: var(--rail-hover); color: var(--rail-text); }
.workspace-navigation__link--active { background: var(--surface-selected); border-color: var(--accent-border); color: var(--rail-text); }
.workspace-navigation__link--active svg { color: var(--accent-strong); }
</style>
