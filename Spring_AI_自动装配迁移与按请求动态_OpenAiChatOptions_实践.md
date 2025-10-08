# Spring AI 自动装配迁移与按请求动态 OpenAiChatOptions 实践

## Core Features

- YAML 定义默认对话/嵌入/记忆/向量库配置

- 移除手写 Bean，避免自动装配冲突

- 服务层按请求覆盖 OpenAiChatOptions

- RAG 使用自动装配 ChatClient 与 advisors

- DashScope 兼容模式配置与模型接入

- 重试与退避策略生效验证

## Tech Stack

{
  "Backend": "Spring Boot + Spring AI OpenAI Starter（自动装配 ChatClient/Memory/VectorStore）"
}

## Design

不涉及 UI 设计

## Plan

Note: 

- [ ] is holding
- [/] is doing
- [X] is done

---

[/] 按示例校正 application.yaml 层级与 DashScope base-url、模型名

[ ] 替换依赖为官方 Spring AI OpenAI Starter，并清理无用坐标

[X] 删除或注释 ChatClientConfig 内的 ChatClient/Memory/RAG 手写 Bean

[ ] 在 ChatServiceImpl 注入 ChatClient/ChatMemory，支持按请求覆盖 OpenAiChatOptions

[ ] 在 RAGServiceImpl 使用 QuestionAnswerAdvisor+VectorStore，并接入会话记忆

[ ] 验证普通对话、带记忆对话、RAG 三类路径的默认配置与动态覆盖生效

[ ] 针对异常与超时回退，确认 retry/backoff 行为符合预期
