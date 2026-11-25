import { ref } from "vue";
import { defineStore } from "pinia";

import type { InspectionFinding, InspectionSettings, UpdateInspectionSettingsRequest } from "@agent-java/api-contracts";

import { createAiopsClient, type AiopsClient } from "../aiops/aiopsClient";
import { useFeedbackStore } from "./feedback";
import { toUserFacingError } from "../ui/userFacingError";

let clientFactory: () => AiopsClient = createAiopsClient;

export function setInspectionClientFactoryForTests(factory: (() => AiopsClient) | null): void {
  clientFactory = factory ?? createAiopsClient;
}

export const useInspectionStore = defineStore("inspection", () => {
  const client = clientFactory();
  const settings = ref<InspectionSettings | null>(null);
  const findings = ref<readonly InspectionFinding[]>([]);
  const isLoading = ref(false);
  const isToggling = ref(false);
  const errorMessage = ref<string | null>(null);

  function reportError(error: unknown): void {
    const message = toUserFacingError(error);
    errorMessage.value = message;
    useFeedbackStore().showError(message);
  }

  async function reloadSettings(): Promise<void> {
    settings.value = await client.getInspectionSettings();
  }

  async function reloadFindings(): Promise<void> {
    findings.value = (await client.listInspectionFindings()).items;
  }

  async function initialize(): Promise<void> {
    isLoading.value = true;
    errorMessage.value = null;
    try {
      await Promise.all([reloadSettings(), reloadFindings()]);
    } catch (error) {
      reportError(error);
      throw error;
    } finally {
      isLoading.value = false;
    }
  }

  async function updateSettings(request: UpdateInspectionSettingsRequest): Promise<void> {
    isToggling.value = true;
    try {
      settings.value = await client.updateInspectionSettings(request);
      await reloadFindings();
    } catch (error) {
      reportError(error);
      throw error;
    } finally {
      isToggling.value = false;
    }
  }

  async function setEnabled(enabled: boolean): Promise<void> {
    await updateSettings({ enabled });
  }

  // null=全部白名单指标（默认全开）；数组=仅勾选指标；空数组=全不检
  async function setMetrics(metrics: readonly string[] | null): Promise<void> {
    await updateSettings({ enabled: settings.value?.enabled ?? false, metrics });
  }

  function reset(): void {
    settings.value = null;
    findings.value = [];
    isLoading.value = false;
    isToggling.value = false;
    errorMessage.value = null;
  }

  return {
    errorMessage,
    findings,
    isLoading,
    isToggling,
    settings,
    initialize,
    refreshFindings: reloadFindings,
    setEnabled,
    setMetrics,
    reset
  };
});
