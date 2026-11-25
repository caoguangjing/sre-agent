# Chat Skill 上传样例

本目录包含 5 个符合 Agent Skills 规范的示例目录，用于“Skill”页面上传、多选和渐进式加载验证：

- `log-analysis/SKILL.md`：日志线索提炼
- `knowledge-search/SKILL.md`：知识库检索与引用
- `api-troubleshooting/SKILL.md`：API 故障排查
- `incident-report/SKILL.md`：事件报告整理
- `change-risk-review/SKILL.md`：变更风险检查

这些文件不会在项目启动时自动上传或启用。请在“Skill”页面点击“上传 Skill”，进入某个示例子目录并选择其中的 `SKILL.md`；上传后在对话或智能诊断的 Skill 选择中勾选需要使用的 Skill。

每个文件都以 YAML frontmatter 开头，`name` 与父目录名一致，`description` 描述能力及适用场景。所有示例均为 UTF-8 Markdown、文件名严格为 `SKILL.md` 且小于 64KB。

聊天 Agent 的初始 system prompt 只包含已选 Skill 的 `name` 和 `description`。模型判断 Skill 与问题相关时会调用 `load_skill(name)`，完整正文才会作为工具结果进入当前对话上下文。
