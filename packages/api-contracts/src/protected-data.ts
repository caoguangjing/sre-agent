import type { ToolCallAudit } from "./chat";
import type { BackgroundJob } from "./background-jobs";

export interface KnowledgeBaseSummary {
  readonly id: string;
  readonly name: string;
  readonly ownerUserId: string;
}

export interface KnowledgeBaseListResponse {
  readonly items: readonly KnowledgeBaseSummary[];
}

export interface AiopsDiagnosticSummary {
  readonly id: string;
  readonly ownerUserId: string;
  readonly status: AiopsDiagnosticStatus;
  readonly query: string;
  readonly inputPayload: Record<string, unknown>;
  readonly resultPayload: Record<string, unknown>;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly completedAt: string | null;
  readonly reports: readonly AiopsDiagnosticReport[];
  readonly backgroundJob?: BackgroundJob;
}

export type AiopsDiagnosticStatus = "accepted" | "running" | "succeeded" | "failed" | "cancelled";

export interface CreateAiopsDiagnosticRequest {
  readonly query: string;
  readonly alert?: Record<string, unknown>;
  readonly skillIds?: readonly string[];
}

export interface ActiveAlert {
  readonly id: string;
  readonly source: string;
  readonly alertName: string;
  readonly service: string;
  readonly severity: string;
  readonly status: string;
  readonly startsAt: string;
  readonly summary: string;
  readonly labels: Record<string, string>;
  readonly annotations: Record<string, string>;
  readonly context: Record<string, unknown>;
}

export interface ActiveAlertListResponse {
  readonly items: readonly ActiveAlert[];
}

export interface AiopsDiagnosticReport {
  readonly id: string;
  readonly title: string;
  readonly content: string;
  readonly payload: Record<string, unknown>;
  readonly evidenceIds: readonly string[];
  readonly createdAt: string;
}

export interface AiopsDiagnosticStep {
  readonly id: string;
  readonly taskId: string;
  readonly sequence: number;
  readonly phase: string;
  readonly status: string;
  readonly payload: Record<string, unknown>;
  readonly createdAt: string;
}

export type AiopsEvidenceKind = "log" | "metric" | "alert" | "ticket" | "knowledge_reference";

export interface AiopsDiagnosticEvidence {
  readonly id: string;
  readonly taskId: string;
  readonly stepId: string | null;
  readonly toolCallId: string | null;
  readonly kind: AiopsEvidenceKind;
  readonly source: string;
  readonly summary: string;
  readonly payload: Record<string, unknown>;
  readonly createdAt: string;
}

export interface AiopsReportEvidenceLink {
  readonly id: string;
  readonly taskId: string;
  readonly reportId: string;
  readonly evidenceId: string;
  readonly createdAt: string;
}

export interface AiopsGraphCheckpoint {
  readonly id: string;
  readonly taskId: string;
  readonly threadId: string;
  readonly checkpointNamespace: string;
  readonly checkpointId: string;
  readonly payload: Record<string, unknown>;
  readonly metadata: Record<string, unknown>;
  readonly createdAt: string;
}

export interface AiopsDiagnosticHistoryResponse {
  readonly items: readonly AiopsDiagnosticSummary[];
}

export interface AiopsDiagnosticCase {
  readonly id: string;
  readonly ownerUserId: string;
  readonly taskId: string;
  readonly reportId: string;
  readonly documentId: string;
  readonly indexTaskId: string;
  readonly alertName: string;
  readonly service: string;
  readonly keywords: readonly string[];
  readonly rootCause: string;
  readonly remediation: string;
  readonly summary: string;
  readonly evidenceIds: readonly string[];
  readonly createdAt: string;
}

export interface AiopsDiagnosticCaseListResponse {
  readonly items: readonly AiopsDiagnosticCase[];
}

export interface AiopsDiagnosticEvidenceChain {
  readonly task: AiopsDiagnosticSummary;
  readonly steps: readonly AiopsDiagnosticStep[];
  readonly toolCalls: readonly ToolCallAudit[];
  readonly evidence: readonly AiopsDiagnosticEvidence[];
  readonly reports: readonly AiopsDiagnosticReport[];
  readonly reportEvidenceLinks: readonly AiopsReportEvidenceLink[];
  readonly checkpoints: readonly AiopsGraphCheckpoint[];
}

export interface SaveAiopsDiagnosticCaseResponse {
  readonly document: import("./documents").KnowledgeDocument;
  readonly task: import("./indexing").DocumentIndexTask;
  readonly scheduled: true;
}

export type InspectionFindingDirection = "up" | "down";

export type InspectionFindingStatus = "open" | "recovered";

export interface InspectionSettingsConfig {
  readonly configured: boolean;
  readonly apps: readonly string[];
  readonly intervalMinutes: number;
  readonly windowMinutes: number;
  readonly thresholdPercent: number;
  readonly autoDiagnose: boolean;
}

export interface InspectionMetricCatalogEntry {
  readonly category: string;
  readonly name: string;
  readonly displayName: string;
}

export interface InspectionSettings {
  readonly enabled: boolean;
  readonly updatedAt: string;
  readonly metrics: readonly string[] | null;
  readonly availableCategories: readonly string[];
  readonly catalog: readonly InspectionMetricCatalogEntry[];
  readonly config: InspectionSettingsConfig;
}

export interface UpdateInspectionSettingsRequest {
  readonly enabled: boolean;
  readonly metrics?: readonly string[] | null;
}

export interface InspectionFinding {
  readonly id: string;
  readonly ownerUserId: string;
  readonly service: string;
  readonly metricKey: string;
  readonly metricName: string;
  readonly unit: string;
  readonly promql: string;
  readonly direction: InspectionFindingDirection;
  readonly deltaPercent: number;
  readonly baselineValue: number;
  readonly currentValue: number;
  readonly windowMinutes: number;
  readonly status: InspectionFindingStatus;
  readonly detectedAt: string;
  readonly lastSeenAt: string;
  readonly recoveredAt: string | null;
  readonly diagnosticId: string | null;
  readonly alertName: string;
}

export interface InspectionFindingListResponse {
  readonly items: readonly InspectionFinding[];
}
