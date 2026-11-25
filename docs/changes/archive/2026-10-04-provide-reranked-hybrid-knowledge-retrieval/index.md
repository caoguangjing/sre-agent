---
title: "混合检索"
status: archive
createdDate: 2026-10-04
archivedDate: 2026-10-04
---

# 混合检索

## 功能概述

## Why

混合召回、RRF 与真实 Rerank是智能运维平台的功能模块，提供混合检索。

## What Changes

校验query/topK和受限filters；并行向量与BM25L正IDF召回，中文单字及双字token，RRF k=60确定性融合最多20候选，真实rerank返回最多5条及全部阶段排名分数。

## Capabilities

### New Capabilities

- `p12-provide-reranked-hybrid-knowledge-retrieval`：混合召回、RRF 与真实 Rerank。

### Modified Capabilities

无。

## Impact

校验query/topK和受限filters；并行向量与BM25L正IDF召回，中文单字及双字token，RRF k=60确定性融合最多20候选，真实rerank返回最多5条及全部阶段排名分数。

实现：`knowledge/KnowledgeService.java`、`agent/ChatService.java`。验证：阶段rank/score、过滤、真实rerank。HTTP/SSE使用共享契约，数据库资源按kind/id逐行存储，领域steps/evidence/reports/checkpoints具有单独可查询记录。

## 设计

## Context

P12：混合召回、RRF 与真实 Rerank。功能由本仓库的领域服务、共享契约与工作台提供。

## Goals / Non-Goals

实现提案全部功能；模型与工具运行时只调用真实依赖。

## Decisions

- Java 17、Spring Boot MVC、AgentScope 2.0.3 ReActAgent，遵循 Maven src/main/java 布局。
- SQLite JDBC 参数化查询保存用户资源与任务；所有查询带 owner。
- Vue工作台通过共享HTTP/SSE契约调用Java后端。
- 外部服务使用官方AgentScope MCP/模型扩展、Milvus Java SDK和Qwen Embedding/rerank HTTP。
- 任务持久化lease、heartbeat、retry、cancel；诊断状态按 plan/execute/replan/report checkpoint 恢复。

## Risks / Trade-offs

自动测试使用外部边界桩验证失败与用户隔离；真实服务验收使用本地配置，并单独记录结果。

## 运行配置

默认后端监听8081，SQLite路径为var/agent-java.db，Milvus集合为agent_java_knowledge；运行参数由JSON配置提供。

## 功能实现定位

校验query/topK和受限filters；并行向量与BM25L正IDF召回，中文单字及双字token，RRF k=60确定性融合最多20候选，真实rerank返回最多5条及全部阶段排名分数。

实现：`knowledge/KnowledgeService.java`、`agent/ChatService.java`。验证：阶段rank/score、过滤、真实rerank。HTTP/SSE使用共享契约，数据库资源按kind/id逐行存储，领域steps/evidence/reports/checkpoints具有单独可查询记录。

## 实现检查

## 1. 实施 P12

- [x] 1.1 按提案实现混合召回、RRF 与真实 Rerank，并保持共享协议
- [x] 1.2 完成相关自动测试与用户隔离/失败行为验证
- [x] 1.3 核验实现、功能描述和验证记录

## 规格 p12-provide-reranked-hybrid-knowledge-retrieval

## ADDED Requirements

### Requirement: P12 混合检索

系统 SHALL 校验query/topK和受限filters；并行向量与BM25L正IDF召回，中文单字及双字token，RRF k=60确定性融合最多20候选，真实rerank返回最多5条及全部阶段排名分数。

#### Scenario: 用户使用已实现功能

- **WHEN** 用户按提案的有效输入调用混合检索
- **THEN** 系统返回提案规定的共享协议或界面行为，并保存所需的可恢复状态

### Requirement: P12 验证与失败边界

系统 SHALL 在混合检索的输入校验、用户归属和外部失败边界返回明确结果，不伪造成功、日志、向量或来源；自动测试使用临时配置和外部边界桩，真实服务验收单独记录。

#### Scenario: 校验或依赖失败

- **WHEN** 输入无效、访问其他用户资源或对应必需依赖不可用
- **THEN** 系统通过统一错误或明确检查结果报告问题，保持用户隔离及正确持久状态

### Requirement: P12 可追溯交付

系统 SHALL 在docs/coverage.md提供本功能实现位置与可重复执行的验证入口。

#### Scenario: 查阅功能记录

- **WHEN** 开发者查阅本功能的实现与验证入口
- **THEN** 文档包含可定位的实现路径和验证命令，页面的正文引用有效
