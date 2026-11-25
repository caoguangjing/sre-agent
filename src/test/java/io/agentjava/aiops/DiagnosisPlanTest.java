package io.agentjava.aiops;

import static io.agentjava.store.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DiagnosisPlanTest {
  private static Map<String, Object> step(String tool) {
    return obj("tool", tool, "arguments", obj(), "purpose", "取证");
  }

  @Test
  void logToolRequiresLogQueryUnlessEvidenceOrPrerequisite() {
    var steps = List.of(step("sls_list_logstores"), step("custom_metric_tool"));
    var bounded =
        DiagnosisService.boundedSteps(steps, List.of(), true);
    assertEquals(2, bounded.size());

    assertThrows(
        IllegalStateException.class,
        () -> DiagnosisService.boundedSteps(List.of(step("custom_metric_tool")), List.of(), true));

    var logEvidence = List.of(obj("kind", "log", "source", "sls_query_logs"));
    assertEquals(
        1, DiagnosisService.boundedSteps(List.of(step("custom_metric_tool")), logEvidence, true).size());

    var prerequisite = List.of(step("sls_get_logstore_index"));
    assertEquals(
        1, DiagnosisService.boundedSteps(prerequisite, List.of(), true).size());
  }

  @Test
  void logToolStepsDedupeRepeatedSlsQueryAndCapAt8() {
    var steps = new ArrayList<Map<String, Object>>();
    steps.add(step("sls_query_logs"));
    steps.add(step("sls_query_logs"));
    for (int i = 0; i < 8; i++) steps.add(step("tool_" + i));
    var bounded = DiagnosisService.boundedSteps(steps, List.of(), true);
    assertEquals(8, bounded.size());
    assertEquals("sls_query_logs", s(bounded.get(0), "tool"));
    assertEquals("tool_6", s(bounded.get(7), "tool"));
  }

  @Test
  void withoutLogToolRequiresRealQueryOrExistingEvidence() {
    assertThrows(
        IllegalStateException.class,
        () -> DiagnosisService.boundedSteps(List.of(), List.of(), false));
    var steps = List.of(step("queryAppErrorLog"));
    assertEquals(
        1, DiagnosisService.boundedSteps(steps, List.of(), false).size());
    var evidence = List.of(obj("kind", "metric", "source", "queryMetricsByPromQL"));
    assertEquals(
        0, DiagnosisService.boundedSteps(List.of(), evidence, false).size());
  }

  @Test
  void evidenceKindFollowsConfiguredMappingWithDefaults() {
    var kinds = obj("queryAppErrorLog", "log", "queryAppLogByTrace", "log", "queryAlarmList", "bogus");
    assertEquals("log", DiagnosisService.evidenceKindOf("queryAppErrorLog", kinds));
    assertEquals("log", DiagnosisService.evidenceKindOf("sls_query_logs", obj()));
    assertEquals("metric", DiagnosisService.evidenceKindOf("queryTraceList", obj()));
    assertEquals("metric", DiagnosisService.evidenceKindOf("queryAlarmList", kinds));
    assertEquals("log", DiagnosisService.evidenceKindOf("queryAppLogByTrace", kinds));
  }
}
