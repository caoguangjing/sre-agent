<script setup lang="ts">
import { ChevronRight, CircleDot, Layers3, LogOut, Menu, ShieldCheck, TriangleAlert, X } from "lucide-vue-next";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import WorkspaceNavigation from "../components/WorkspaceNavigation.vue";
import ChatSessionList from "../components/ChatSessionList.vue";
import { createRuntimeHealthClient } from "../runtimeHealth";
import { useAuthStore } from "../stores/auth";
import { useChatStore } from "../stores/chat";
import { APP_TITLE } from "../config";
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const chat = useChatStore();
const routeTitle = computed(() => typeof route.meta.title === "string" ? route.meta.title : "工作台");
const initials = computed(() => auth.user?.displayName.slice(0, 1).toUpperCase() ?? "用");
const isConnected = ref<boolean | null>(null);
const navigationOpen = ref(false);
watch(() => route.path, () => { navigationOpen.value = false; });
function onKey(event: KeyboardEvent): void { if (event.key === "Escape") navigationOpen.value = false; }
onMounted(() => {
  window.addEventListener("keydown", onKey);
  void createRuntimeHealthClient().health().then(() => { isConnected.value = true; }).catch(() => { isConnected.value = false; });
});
onBeforeUnmount(() => { window.removeEventListener("keydown", onKey); });
async function logout(): Promise<void> { try { await auth.logout(); } finally { await router.replace("/login"); } }
function runChat(operation: () => Promise<unknown>): void { void operation().catch(() => undefined); }
</script>
<template>
  <div class="workspace-layout">
    <button v-if="navigationOpen" class="workspace-layout__backdrop" aria-label="关闭导航" @click="navigationOpen = false" />
    <aside class="workspace-layout__rail" :class="{ 'workspace-layout__rail--open': navigationOpen }">
      <RouterLink class="workspace-layout__brand" to="/chat" :aria-label="APP_TITLE"><span class="workspace-layout__mark"><Layers3 :size="21" stroke-width="1.65" aria-hidden="true" /></span><span><strong>{{ APP_TITLE }}</strong></span></RouterLink>
      <div class="workspace-layout__space"><span class="workspace-layout__space-icon">{{ initials }}</span><span><strong>个人工作区</strong><small>你的知识与运行洞察</small></span><ShieldCheck :size="14" aria-hidden="true" /></div>
      <WorkspaceNavigation :active-path="route.path">
        <template #chat-history><ChatSessionList :active-session-id="chat.activeSessionId" :sessions="chat.sessions" variant="rail" @create="runChat(chat.newSession)" @delete="runChat(() => chat.deleteSession($event))" @select="runChat(() => chat.selectSession($event)); navigationOpen = false" /></template>
      </WorkspaceNavigation>
      <div class="workspace-layout__bottom"><span class="workspace-layout__edition"><span />Agent Java 工作台</span><div class="workspace-layout__account"><span class="workspace-layout__avatar" aria-hidden="true">{{ initials }}</span><span class="workspace-layout__identity"><strong>{{ auth.user?.displayName }}</strong><small>{{ auth.user?.email }}</small></span><button type="button" title="退出登录" aria-label="退出登录" @click="logout"><LogOut :size="16" aria-hidden="true" /></button></div></div>
    </aside>
    <section class="workspace-layout__main">
      <header class="workspace-layout__header"><div class="workspace-layout__breadcrumb"><button class="workspace-layout__menu ui-icon-button" :aria-expanded="navigationOpen" aria-label="打开工作区导航" @click="navigationOpen = !navigationOpen"><X v-if="navigationOpen" :size="18" /><Menu v-else :size="18" /></button><span>工作空间</span><ChevronRight :size="13" aria-hidden="true" /><h1>{{ routeTitle }}</h1></div><div class="workspace-layout__header-right"><span class="workspace-layout__status" :class="{ 'workspace-layout__status--degraded': isConnected === false, 'workspace-layout__status--pending': isConnected === null }" role="status" aria-live="polite" title="HTTP 服务存活状态"><TriangleAlert v-if="isConnected === false" :size="13" aria-hidden="true" /><CircleDot v-else :size="13" aria-hidden="true" />{{ isConnected === null ? '检测服务中' : isConnected ? '服务已连接' : '服务连接异常' }}</span><span class="workspace-layout__header-avatar" aria-hidden="true">{{ initials }}</span></div></header>
      <main class="workspace-layout__content"><RouterView /></main>
    </section>
    <div class="workspace-layout__mobile-nav"><WorkspaceNavigation :active-path="route.path" /></div>
  </div>
</template>
<style scoped>
.workspace-layout { background: var(--canvas); color: var(--text-primary); display: grid; grid-template-columns: 14.5rem minmax(0, 1fr); min-height: 100vh; }
.workspace-layout__rail { background: var(--rail); display: flex; flex-direction: column; height: 100dvh; overflow-y: auto; padding: 1.7rem 0.8rem 1rem; }
.workspace-layout__brand { align-items: center; display: flex; gap: 0.7rem; margin: 0 0.65rem 1.8rem; text-decoration: none; }
.workspace-layout__mark { align-items: center; background: var(--accent); border: 1px solid var(--accent-border); border-radius: 0.65rem; color: #fff; display: flex; height: 2.4rem; justify-content: center; width: 2.4rem; }
.workspace-layout__brand strong { color: var(--rail-text); display: block; font-size: 1.05rem; font-weight: 600; letter-spacing: -0.02em; }
.workspace-layout__space { align-items: center; background: var(--rail-hover); border: 1px solid var(--line); border-radius: 0.65rem; color: var(--rail-muted); display: grid; gap: 0.6rem; grid-template-columns: auto minmax(0, 1fr) auto; margin: 0 0.35rem 1.5rem; padding: 0.75rem; }
.workspace-layout__space-icon { align-items: center; background: var(--surface-selected); border-radius: 0.35rem; color: var(--rail-muted); display: inline-flex; font-size: 0.72rem; height: 1.65rem; justify-content: center; width: 1.65rem; }
.workspace-layout__space strong { color: var(--rail-text); display: block; font-size: 0.78rem; font-weight: 500; }.workspace-layout__space small { display: block; font-size: 0.72rem; margin-top: 0.2rem; }
.workspace-layout__bottom { margin-top: auto; padding-top: 1.5rem; }.workspace-layout__edition { align-items: center; color: var(--rail-muted); display: flex; font-size: 0.72rem; gap: 0.45rem; letter-spacing: 0.035em; margin: 0 0.8rem 1.15rem; }.workspace-layout__edition > span { background: var(--text-tertiary); border-radius: 50%; height: 4px; width: 4px; }
.workspace-layout__account { align-items: center; border-top: 1px solid var(--line); display: grid; gap: 0.55rem; grid-template-columns: auto minmax(0, 1fr) auto; padding: 1rem 0.45rem 0; }
.workspace-layout__avatar, .workspace-layout__header-avatar { align-items: center; background: var(--rail-hover); border-radius: 50%; color: var(--rail-text); display: inline-flex; font-size: 0.72rem; height: 2rem; justify-content: center; width: 2rem; }
.workspace-layout__identity { min-width: 0; }.workspace-layout__identity strong, .workspace-layout__identity small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.workspace-layout__identity strong { color: var(--rail-text); font-size: 0.72rem; font-weight: 500; }.workspace-layout__identity small { color: var(--rail-muted); font-size: 0.72rem; margin-top: 0.25rem; }
.workspace-layout__account button { color: var(--rail-muted); padding: 0.3rem; }.workspace-layout__account button:hover { color: #fff; }
.workspace-layout__main { display: grid; grid-template-rows: auto minmax(0, 1fr); height: 100dvh; min-width: 0; overflow: hidden; }
.workspace-layout__header { align-items: center; background: var(--surface-raised); border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; min-height: 4rem; padding: 0 1.9rem; }
.workspace-layout__breadcrumb { align-items: center; color: var(--text-tertiary); display: flex; font-size: 0.78rem; gap: 0.9rem; }.workspace-layout__breadcrumb h1 { color: var(--text-primary); font-size: 0.78rem; font-weight: 600; margin: 0; }.workspace-layout__header-right { align-items: center; display: flex; gap: 1.2rem; }
.workspace-layout__status { align-items: center; background: var(--status-success-bg); border: 1px solid var(--status-success-border); border-radius: 99px; color: var(--success); display: inline-flex; font-size: 0.72rem; gap: 0.35rem; padding: 0.32rem 0.65rem; }.workspace-layout__status--pending { background: var(--status-neutral-bg); border-color: var(--status-neutral-border); color: var(--status-neutral-text); }.workspace-layout__status--degraded { background: var(--danger-soft); border-color: var(--status-danger-border); color: var(--danger); }.workspace-layout__header-avatar { background: var(--accent-soft); color: var(--accent); font-size: 0.72rem; height: 1.85rem; width: 1.85rem; }
.workspace-layout__content { height: 100%; min-height: 0; overflow: hidden; width: 100%; }
.workspace-layout__mobile-nav, .workspace-layout__menu, .workspace-layout__backdrop { display: none; }
@media (max-width: 760px) { .workspace-layout { display: block; }.workspace-layout__main { height: calc(100dvh - 4rem); }.workspace-layout__rail { display: none; }.workspace-layout__rail--open { bottom: 0; display: flex; left: 0; position: fixed; top: 0; width: 16rem; z-index: 30; }.workspace-layout__backdrop { background: rgb(2 6 16 / 60%); display: block; inset: 0; position: fixed; z-index: 29; }.workspace-layout__menu { display: inline-flex; }.workspace-layout__header { min-height: 3.75rem; padding: 0 1rem; }.workspace-layout__breadcrumb { gap: 0.5rem; }.workspace-layout__breadcrumb > span, .workspace-layout__breadcrumb > svg, .workspace-layout__header-avatar { display: none; }.workspace-layout__mobile-nav { background: var(--surface-raised); border-top: 1px solid var(--line); bottom: 0; display: block; left: 0; padding-bottom: env(safe-area-inset-bottom); position: fixed; right: 0; z-index: 20; }.workspace-layout__mobile-nav :deep(.workspace-navigation) { display: grid; gap: 0; grid-template-columns: repeat(4, 1fr); }.workspace-layout__mobile-nav :deep(.workspace-navigation__label), .workspace-layout__mobile-nav :deep(small) { display: none; }.workspace-layout__mobile-nav :deep(.workspace-navigation__link) { border: 0; border-radius: 0; color: var(--text-tertiary); flex-direction: column; font-size: 0.72rem; gap: 0.25rem; justify-content: center; min-height: 4rem; padding: 0.45rem; }.workspace-layout__mobile-nav :deep(.workspace-navigation__link--active) { background: var(--accent-soft); color: var(--accent); }.workspace-layout__mobile-nav :deep(.workspace-navigation__link--active svg) { color: var(--accent); } }
</style>
