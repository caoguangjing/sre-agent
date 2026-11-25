import { createPinia, setActivePinia } from "pinia";
import { afterEach, describe, expect, it } from "vitest";

import type {
  InspectionFinding,
  InspectionSettings,
  SseEvent
} from "@agent-java/api-contracts";

import { ApiClientError } from "../src/api/apiClient";
import type { AiopsClient } from "../src/aiops/aiopsClient";
import { setInspectionClientFactoryForTests, useInspectionStore } from "../src/stores/inspection";

const catalog = [
  { category: "资源", name: "pod_cpu_limit_usage", displayName: "CPU 利用率" },
  { category: "资源", name: "pod_memory_util", displayName: "内存利用率" },
  { category: "流量", name: "pod_nginx_qps", displayName: "Nginx QPS" }
];

const settings = (enabled: boolean, metrics: readonly string[] | null = null): InspectionSettings => ({
  enabled,
  updatedAt: "2026-11-25T08:00:00Z",
  metrics,
  availableCategories: ["资源", "流量"],
  catalog,
  config: {
    configured: true,
    apps: ["uniagent-control-center"],
    intervalMinutes: 5,
    windowMinutes: 5,
    thresholdPercent: 30,
    autoDiagnose: true
  }
});

const finding = (overrides: Partial<InspectionFinding> = {}): InspectionFinding => ({
  id: "finding_1",
  ownerUserId: "user_1",
  service: "uniagent-control-center",
  metricKey: "cpu",
  metricName: "CPU 利用率",
  unit: "%",
  promql: 'avg(pod_cpu_limit_usage{app_name="uniagent-control-center"})',
  direction: "up",
  deltaPercent: 164,
  baselineValue: 10,
  currentValue: 26.4,
  windowMinutes: 5,
  status: "open",
  detectedAt: "2026-11-25T08:00:00Z",
  lastSeenAt: "2026-11-25T08:05:00Z",
  recoveredAt: null,
  diagnosticId: "diagnostic_1",
  alertName: "",
  ...overrides
});

afterEach(() => setInspectionClientFactoryForTests(null));

describe("Inspection store", () => {
  it("loads settings and findings on initialize", async () => {
    const client = fakeClient();
    setInspectionClientFactoryForTests(() => client);
    setActivePinia(createPinia());
    const store = useInspectionStore();

    await store.initialize();

    expect(store.settings).toMatchObject({ enabled: false, config: { configured: true } });
    expect(store.findings.map((item) => item.id)).toEqual(["finding_1"]);
    expect(store.isLoading).toBe(false);
    expect(store.errorMessage).toBeNull();
  });

  it("toggles the switch and refreshes findings", async () => {
    const client = fakeClient();
    const updates: boolean[] = [];
    let enabled = false;
    client.getInspectionSettings = async () => settings(enabled);
    client.updateInspectionSettings = async (request) => {
      updates.push(request.enabled);
      enabled = request.enabled;
      return settings(enabled);
    };
    setInspectionClientFactoryForTests(() => client);
    setActivePinia(createPinia());
    const store = useInspectionStore();
    await store.initialize();

    await store.setEnabled(true);

    expect(store.settings?.enabled).toBe(true);
    expect(updates).toEqual([true]);

    await store.setEnabled(false);
    expect(store.settings?.enabled).toBe(false);
    expect(updates).toEqual([true, false]);
  });

  it("surfaces load errors without keeping stale state", async () => {
    const client = fakeClient();
    client.listInspectionFindings = async () => {
      throw new ApiClientError({
        code: "SYSTEM_UNAVAILABLE",
        category: "system",
        httpStatus: 503,
        message: "backend unavailable"
      });
    };
    setInspectionClientFactoryForTests(() => client);
    setActivePinia(createPinia());
    const store = useInspectionStore();

    await expect(store.initialize()).rejects.toBeInstanceOf(ApiClientError);

    expect(store.errorMessage).toBe("服务暂时不可用，请稍后重试。");
    expect(store.isLoading).toBe(false);
  });

  it("updates metrics alongside the switch state", async () => {
    const client = fakeClient();
    let current: InspectionSettings = settings(false);
    client.updateInspectionSettings = async (request) => {
      current = settings(request.enabled, request.metrics === undefined ? null : request.metrics);
      return current;
    };
    setInspectionClientFactoryForTests(() => client);
    setActivePinia(createPinia());
    const store = useInspectionStore();
    await store.initialize();

    await store.setMetrics(["pod_cpu_limit_usage"]);

    expect(store.settings?.enabled).toBe(false);
    expect(store.settings?.metrics).toEqual(["pod_cpu_limit_usage"]);

    await store.setMetrics(null);

    expect(store.settings?.metrics).toBeNull();
  });
});

function fakeClient(): AiopsClient {
  return {
    createDiagnostic: async () => {
      throw new Error("not used in inspection store tests");
    },
    getEvidenceChain: async () => {
      throw new Error("not used in inspection store tests");
    },
    getInspectionSettings: async () => settings(false),
    listActiveAlerts: async () => ({ items: [] }),
    listDiagnosticCases: async () => ({ items: [] }),
    listDiagnostics: async () => ({ items: [] }),
    listInspectionFindings: async () => ({ items: [finding()] }),
    saveDiagnosticCase: async () => {
      throw new Error("not used in inspection store tests");
    },
    streamDiagnostic: async function* (): AsyncIterable<SseEvent> {},
    updateInspectionSettings: async (request) => settings(request.enabled),
  };
}
