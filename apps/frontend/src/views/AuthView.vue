<script setup lang="ts">
import { ArrowRight, Eye, EyeOff, BookOpen, GitBranch, Layers3, LoaderCircle, MessageSquare, Sparkles } from "lucide-vue-next";
import { computed, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";

import { APP_TITLE } from "../config";
import { useAuthStore } from "../stores/auth";

const props = defineProps<{ readonly mode: "login" | "register" }>();
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const form = reactive({ displayName: "", email: "", password: "" });
const passwordVisible = ref(false);
const isLogin = computed(() => props.mode === "login");
const heading = computed(() => (isLogin.value ? "欢迎回来" : "创建你的工作台"));
const submitLabel = computed(() => (isLogin.value ? "登录" : "注册并进入"));
const alternatePath = computed(() => (isLogin.value ? "/register" : "/login"));
const alternateLabel = computed(() => (isLogin.value ? "还没有账号？创建工作台" : "已有账号？直接登录"));

function intendedPath(): string {
  const redirect = route.query.redirect;
  return typeof redirect === "string" && redirect.startsWith("/") ? redirect : "/chat";
}

async function submit(): Promise<void> {
  try {
    if (isLogin.value) {
      await auth.login({ email: form.email, password: form.password });
    } else {
      await auth.register({
        displayName: form.displayName,
        email: form.email,
        password: form.password
      });
    }
    await router.replace(intendedPath());
  } catch {
    // The auth store publishes the normalized API error to global feedback.
  }
}
</script>

<template>
  <main class="auth-view">
    <aside class="auth-view__story" aria-label="工作台介绍">
      <div class="auth-view__story-brand"><Layers3 :size="24" aria-hidden="true" /><span>{{ APP_TITLE }}</span></div>
      <div class="auth-view__story-content">
        <h2>从问题到洞察，<br />让工作更进一步。</h2>
        <span>对话、知识与运维诊断，在一个工作台连起来。</span>
        <div class="auth-view__visual" aria-hidden="true">
          <div class="auth-view__visual-core"><Sparkles :size="30" /><strong>{{ APP_TITLE }}</strong><small>理解 · 检索 · 执行</small></div>
          <div class="auth-view__visual-node"><MessageSquare :size="20" /><span>智能对话</span></div>
          <div class="auth-view__visual-node"><BookOpen :size="20" /><span>知识检索</span></div>
          <div class="auth-view__visual-node"><GitBranch :size="20" /><span>运维诊断</span></div>
        </div>
      </div>
      <small class="auth-view__story-footer">让回答有依据，让诊断可追溯。</small>
    </aside>
    <section class="auth-view__panel" :aria-labelledby="`${mode}-title`">
      <RouterLink class="auth-view__brand" to="/login">
        <Sparkles :size="20" aria-hidden="true" />
        <span>{{ APP_TITLE }}</span>
      </RouterLink>
      <div class="auth-view__intro">
        <h1 :id="`${mode}-title`">{{ heading }}</h1>
        <span>{{ isLogin ? "继续你的对话、知识与诊断工作。" : "用一个账号管理你的对话、知识与诊断记录。" }}</span>
      </div>
      <form class="auth-view__form" @submit.prevent="submit">
        <label v-if="!isLogin">
          <span>昵称</span>
          <input v-model.trim="form.displayName" autocomplete="name" required />
        </label>
        <label>
          <span>邮箱</span>
          <input v-model.trim="form.email" autocomplete="email" inputmode="email" type="email" required />
        </label>
        <label>
          <span>密码</span>
          <span class="auth-view__password"><input v-model="form.password" :autocomplete="isLogin ? 'current-password' : 'new-password'" minlength="8" :type="passwordVisible ? 'text' : 'password'" required /><button type="button" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" :title="passwordVisible ? '隐藏密码' : '显示密码'" @click="passwordVisible = !passwordVisible"><EyeOff v-if="passwordVisible" :size="17" aria-hidden="true" /><Eye v-else :size="17" aria-hidden="true" /></button></span>
        </label>
        <button class="auth-view__submit" type="submit" :disabled="auth.isLoading">
          <span>{{ auth.isLoading ? (isLogin ? "正在登录" : "正在创建") : submitLabel }}</span>
          <LoaderCircle v-if="auth.isLoading" class="auth-view__spin" :size="17" aria-hidden="true" /><ArrowRight v-else :size="17" aria-hidden="true" />
        </button>
      </form>
      <RouterLink class="auth-view__alternate" :to="alternatePath">{{ alternateLabel }}</RouterLink>
    </section>
  </main>
</template>

<style scoped>
.auth-view { align-items: center; background: var(--surface-raised); display: grid; grid-template-columns: minmax(0, 1.08fr) minmax(0, 1fr); min-height: 100dvh; }
.auth-view__panel { margin: 0 auto; max-width: 31rem; padding: 3rem; width: 100%; }
.auth-view__brand { align-items: center; color: var(--text-primary); display: inline-flex; font-size: 0.9rem; font-weight: 700; gap: 0.58rem; text-decoration: none; }
.auth-view__brand svg { color: var(--accent); }
.auth-view__intro { margin: 2.75rem 0 1.85rem; }
.auth-view__intro h1 { font-size: clamp(1.65rem, 5vw, 2.1rem); font-weight: 700; letter-spacing: 0; margin: 0; }
.auth-view__intro > span { color: var(--text-secondary); display: block; font-size: 0.9rem; line-height: 1.65; margin-top: 0.75rem; }
.auth-view__form { display: grid; gap: 1rem; }
label { color: var(--text-secondary); display: grid; font-size: 0.82rem; font-weight: 600; gap: 0.46rem; }
input { background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--radius-sm); color: var(--text-primary); font: inherit; min-height: 2.9rem; padding: 0 0.82rem; width: 100%; }
input:focus { border-color: var(--accent); outline: 3px solid var(--accent-focus); outline-offset: 1px; }
.auth-view__password { display: block; position: relative; }
.auth-view__password input { padding-right: 2.85rem; }
.auth-view__password button { align-items: center; border-radius: 0.4rem; color: var(--text-tertiary); display: inline-flex; height: 2.35rem; justify-content: center; position: absolute; right: 0.25rem; top: 0.25rem; width: 2.35rem; }
.auth-view__password button:hover { background: var(--surface-hover); color: var(--text-primary); }
.auth-view__submit { align-items: center; background: var(--accent); border-radius: var(--radius-sm); color: white; display: flex; font-weight: 600; justify-content: space-between; margin-top: 0.35rem; min-height: 3rem; padding: 0 0.95rem; transition: background var(--transition-fast), transform var(--transition-fast); }
.auth-view__submit:hover:not(:disabled) { background: var(--accent-strong); transform: translateY(-1px); }
.auth-view__submit:disabled { cursor: wait; opacity: 0.72; }
.auth-view__spin { animation: auth-spin 0.8s linear infinite; }
.auth-view__alternate { color: var(--accent-strong); display: inline-block; font-size: 0.86rem; font-weight: 600; margin-top: 1.5rem; text-decoration: none; }
.auth-view__story { align-self: stretch; background: radial-gradient(ellipse at 80% 20%, #2b3564 0%, #161f3c 45%, #0a1020 85%); color: #fff; display: flex; flex-direction: column; justify-content: space-between; overflow: hidden; padding: clamp(2rem, 4vw, 4rem); position: relative; }
.auth-view__story-brand { align-items: center; display: flex; font-size: 1.1rem; font-weight: 600; gap: 0.65rem; }.auth-view__story-brand svg { color: #a6b1ff; }
.auth-view__story-content { margin: 4rem 0; max-width: 35rem; }.auth-view__story-content h2 { font-size: clamp(2rem, 3.3vw, 3.2rem); font-weight: 600; letter-spacing: 0; line-height: 1.5; margin: 0 0 1rem; }.auth-view__story-content > span { color: #aeb9d2; font-size: 0.86rem; line-height: 1.8; }
.auth-view__visual { border: 1px solid rgb(177 190 245 / 16%); border-radius: 1.4rem; display: grid; gap: 1rem; grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: 3.5rem; padding: 1.5rem; position: relative; }.auth-view__visual::before { background: linear-gradient(90deg, transparent, #6573ad, transparent); content: ""; height: 1px; left: 15%; position: absolute; right: 15%; top: 62%; }
.auth-view__visual-core { align-items: center; background: rgb(112 128 206 / 12%); border: 1px solid rgb(159 178 255 / 18%); border-radius: 0.9rem; display: flex; flex-direction: column; gap: 0.5rem; grid-column: 1 / -1; justify-self: center; padding: 1.3rem 3rem; z-index: 1; }.auth-view__visual-core svg { color: #b3baff; }.auth-view__visual-core strong { font-size: 0.95rem; font-weight: 500; }.auth-view__visual-core small { color: #9daacd; font-size: 0.72rem; }
.auth-view__visual-node { align-items: center; background: #1d2748; border: 1px solid #35427a; border-radius: 0.65rem; color: #bdc7e4; display: flex; gap: 0.5rem; justify-content: center; padding: 1rem 0.4rem; z-index: 1; }.auth-view__visual-node span { font-size: 0.78rem; }.auth-view__story-footer { color: #7f8dab; font-size: 0.78rem; }
@media (max-width: 900px) { .auth-view__panel { padding: 2rem; }.auth-view__story { padding: 2rem; }.auth-view__visual { gap: 0.5rem; padding: 1rem; }.auth-view__visual-node { flex-direction: column; } }
@media (max-width: 650px) { .auth-view { grid-template-columns: minmax(0, 1fr); }.auth-view__story { display: none; }.auth-view__panel { padding: 2rem 1.5rem; } }
@keyframes auth-spin { to { transform: rotate(360deg); } }
</style>
