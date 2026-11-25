// @vitest-environment jsdom

import { flushPromises, mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ChatAssemblyConfigurationResponse } from "@agent-java/api-contracts";

import ChatPromptSidebar from "../src/components/ChatPromptSidebar.vue";
import ChatSkillSidebar from "../src/components/ChatSkillSidebar.vue";
import PromptView from "../src/views/PromptView.vue";
import SkillView from "../src/views/SkillView.vue";
import { useChatStore } from "../src/stores/chat";

beforeEach(() => setActivePinia(createPinia()));

describe("chat configuration sidebars", () => {
  it("selects a system prompt in the single-select sidebar and saves the choice", async () => {
    const wrapper = mount(ChatPromptSidebar, {
      global: { stubs: { RouterLink: { template: "<a><slot /></a>" } } },
      props: { configuration: configuration(), isSaving: false }
    });

    expect(wrapper.text()).toContain("对话系统提示词设置");
    expect((wrapper.get('input[type="radio"]').element as HTMLInputElement).checked).toBe(true);
    expect(wrapper.find(".chat-prompt-sidebar__editor").exists()).toBe(false);

    const radios = wrapper.findAll('input[type="radio"]');
    await radios[1]?.setValue(true);
    await wrapper.findAll("button").find((item) => item.text().includes("保存选择"))?.trigger("click");
    expect(wrapper.emitted("save")?.[0]).toEqual(["prompt_2", []]);
  });

  it("reflects a refreshed selection without local prompt editing controls", async () => {
    const wrapper = mount(ChatPromptSidebar, {
      global: { stubs: { RouterLink: { template: "<a><slot /></a>" } } },
      props: { configuration: configuration(), isSaving: false }
    });
    const refreshed = configuration();
    await wrapper.setProps({
      configuration: {
        ...refreshed,
        selection: { ...refreshed.selection, systemPromptId: "prompt_2", updatedAt: "2026-11-24T00:01:00Z" }
      }
    });
    const radios = wrapper.findAll('input[type="radio"]');
    expect((radios[0]?.element as HTMLInputElement).checked).toBe(false);
    expect((radios[1]?.element as HTMLInputElement).checked).toBe(true);
  });

  it("selects and saves skills as a multi-select sidebar without management controls", async () => {
    const wrapper = mount(ChatSkillSidebar, {
      global: { stubs: { RouterLink: { template: "<a><slot /></a>" } } },
      props: { configuration: configuration(), isSaving: false }
    });

    expect(wrapper.text()).toContain("Skill 设置");
    expect(wrapper.text()).toContain("ops-analysis");
    expect(wrapper.text()).toContain("分析日志、告警和运行状态");
    expect(wrapper.find('input[type="file"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain("删除");

    await wrapper.get('input[type="checkbox"]').setValue(true);
    await wrapper.findAll("button").find((item) => item.text().includes("保存使用的 Skill"))?.trigger("click");
    expect(wrapper.emitted("save")?.[0]).toEqual(["prompt_1", ["skill_1"]]);
  });
});

describe("skill management page", () => {
  it("explains invalid skill uploads before sending them to the backend", async () => {
    const store = useChatStore();
    vi.spyOn(store, "reloadConfiguration").mockResolvedValue();
    const uploadSkill = vi.spyOn(store, "uploadSkill").mockResolvedValue();
    store.configuration = configuration();
    const wrapper = mount(SkillView);
    await flushPromises();

    const input = wrapper.get('input[type="file"]');
    const file = new File(["# Skill"], "Ops.md", { type: "text/markdown" });
    Object.defineProperty(input.element, "files", { value: [file], configurable: true });
    await input.trigger("change");

    expect(wrapper.text()).toContain("必须严格为 SKILL.md");
    expect(uploadSkill).not.toHaveBeenCalled();
  });

  it("uploads valid skills and lists existing ones with a content viewer", async () => {
    const store = useChatStore();
    vi.spyOn(store, "reloadConfiguration").mockResolvedValue();
    const uploadSkill = vi.spyOn(store, "uploadSkill").mockResolvedValue();
    const loadSkillContent = vi.spyOn(store, "loadSkillContent").mockResolvedValue();
    store.configuration = configuration();
    store.loadedSkillContent = {
      ...configuration().skills[0]!,
      content: "---\nname: ops-analysis\ndescription: 分析日志、告警和运行状态\n---\n# Skill\nSKILL_MARKER"
    };
    const wrapper = mount(SkillView);
    await flushPromises();

    const input = wrapper.get('input[type="file"]');
    const file = new File(
      ["---\nname: ops\ndescription: 运维分析\n---\n# Skill\nSKILL_MARKER"],
      "SKILL.md",
      { type: "text/markdown" }
    );
    Object.defineProperty(input.element, "files", { value: [file], configurable: true });
    await input.trigger("change");
    expect(uploadSkill).toHaveBeenCalledWith(file);

    await wrapper.get('button[title="查看正文"]').trigger("click");
    expect(loadSkillContent).toHaveBeenCalledWith("skill_1");
    await flushPromises();
    expect(wrapper.text()).toContain("SKILL_MARKER");
  });
});

describe("prompt management page", () => {
  it("creates a prompt through the page editor", async () => {
    const store = useChatStore();
    vi.spyOn(store, "reloadConfiguration").mockResolvedValue();
    const createPrompt = vi.spyOn(store, "createPrompt").mockResolvedValue();
    store.configuration = configuration();
    const wrapper = mount(PromptView);
    await flushPromises();

    await wrapper.get('input[placeholder="例如：SLS 日志分析专家"]').setValue("新提示词");
    await wrapper.get('textarea[placeholder*="注入对话系统提示词"]').setValue("新的系统提示词");
    await wrapper.get("form").trigger("submit");

    expect(createPrompt).toHaveBeenCalledWith("新提示词", "新的系统提示词");
  });
});

function configuration(): ChatAssemblyConfigurationResponse {
  return {
    prompts: [
      {
        id: "prompt_1",
        label: "默认提示词",
        content: "原始提示词",
        isDefault: true,
        createdAt: "2026-07-11T00:00:00Z",
        updatedAt: "2026-07-11T00:00:00Z"
      },
      {
        id: "prompt_2",
        label: "自定义提示词",
        content: "备用提示词",
        isDefault: false,
        createdAt: "2026-07-11T00:00:00Z",
        updatedAt: "2026-07-11T00:00:00Z"
      }
    ],
    skills: [
      {
        id: "skill_1",
        filename: "SKILL.md",
        name: "ops-analysis",
        description: "分析日志、告警和运行状态",
        label: "ops-analysis",
        contentPreview: "分析日志、告警和运行状态",
        sizeBytes: 22,
        createdAt: "2026-07-11T00:00:00Z",
        updatedAt: "2026-07-11T00:00:00Z"
      }
    ],
    selection: {
      systemPromptId: "prompt_1",
      skillIds: [],
      updatedAt: "2026-07-11T00:00:00Z"
    }
  };
}
