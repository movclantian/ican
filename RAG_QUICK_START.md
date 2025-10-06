# RAG 功能快速开始指南

## ✅ 已完成的工作

### 1. 依赖添加
- ✅ Spring AI Redis Vector Store
- ✅ Spring AI Advisors (QuestionAnswerAdvisor)
- ✅ Apache Tika (文档解析)
- ✅ Apache PDFBox (PDF处理)
- ✅ Apache POI (Word处理)
- ✅ Elasticsearch
- ✅ RabbitMQ

### 2. 配置文件
- ✅ `RAGConfig.java` - RAG配置类
- ✅ `VectorStoreConfig.java` - Redis向量存储配置
- ✅ `ChatConfig.java` - 聊天配置
- ✅ `application.yaml` - 添加RAG配置节

### 3. 核心服务
- ✅ `DocumentService` - 文档管理服务接口
- ✅ `DocumentServiceImpl` - 文档管理服务实现
- ✅ `RAGService` - RAG服务接口
- ✅ `RAGServiceImpl` - RAG服务实现

### 4. 控制器
- ✅ `DocumentController` - 文档管理API
- ✅ `RAGController` - RAG功能API

### 5. 数据库
- ✅ `003-create-rag-tables.sql` - RAG相关表结构
  - documents (文档表)
  - document_chunks (文档块表)
  - rag_query_logs (查询日志表)
  - teaching_plans (教学设计表)

### 6. 实体类
- ✅ `DocumentDO` - 文档实体
- ✅ `DocumentMapper` - 文档Mapper

---

## 🎯 核心功能

### 1. 文档上传与向量化
```bash
POST /api/documents/upload
Content-Type: multipart/form-data

file: [PDF/Word/Markdown文件]
type: research_paper | teaching_material | other
```

**流程:**
```
上传文件 → 解析内容 → 文本分块 → 向量嵌入 → 存入Redis
```

### 2. 文档检索
```bash
GET /api/documents/search?query=机器学习&topK=5
```

**返回:**
```json
{
  "query": "机器学习",
  "results": [
    {
      "content": "相关内容片段...",
      "metadata": {
        "documentId": 123,
        "title": "论文标题",
        "type": "research_paper"
      }
    }
  ]
}
```

### 3. RAG 问答
```bash
POST /api/rag/chat
{
  "conversationId": "abc123",
  "query": "什么是深度学习?"
}
```

**特点:**
- 自动从向量库检索相关文档
- 基于检索内容生成准确回答
- 避免模型幻觉

### 4. 论文总结
```bash
POST /api/rag/summarize-paper?documentId=123
```

**返回结构化总结:**
```json
{
  "title": "论文标题",
  "summary": {
    "background": "研究背景...",
    "methodology": "核心方法...",
    "results": "实验结果...",
    "innovations": ["创新点1", "创新点2"],
    "limitations": "局限性..."
  },
  "keywords": ["关键词1", "关键词2"]
}
```

### 5. 教学设计生成
```bash
POST /api/rag/generate-teaching-plan
{
  "topic": "深度学习基础",
  "grade": "大学",
  "subject": "计算机科学",
  "documentIds": [123, 456]
}
```

**返回完整教案:**
```json
{
  "title": "深度学习基础",
  "objectives": {
    "knowledge": ["掌握神经网络原理"],
    "skills": ["能够实现简单神经网络"],
    "values": ["培养科学探索精神"]
  },
  "teachingSteps": [...],
  "evaluation": {...},
  "assignments": [...]
}
```

---

## 🔧 技术实现要点

### 1. Redis 向量存储

```java
@Bean
public VectorStore vectorStore(
        EmbeddingModel embeddingModel,
        RedisConnectionFactory connectionFactory) {
    
    RedisVectorStoreConfig config = RedisVectorStoreConfig.builder()
        .withIndexName("ican-documents-idx")
        .withPrefix("ican:doc:")
        .build();
    
    return new RedisVectorStore(config, embeddingModel, connectionFactory, true);
}
```

**特点:**
- 使用 RediSearch 进行向量检索
- 支持元数据过滤
- 高性能低延迟

### 2. 文档分块策略

```java
TokenTextSplitter splitter = new TokenTextSplitter(
    500,  // chunkSize: 每块500个token
    100,  // chunkOverlap: 块间重叠100个token
    5,    // minChunkSizeChars
    1000, // maxChunkSizeChars
    true  // keepSeparator
);
```

**分块原理:**
- 按Token数量分块(而非字符数)
- 块间有重叠,保持语义连贯性
- 确保每个块大小适中,适合嵌入

### 3. QuestionAnswerAdvisor

```java
QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
    .searchRequest(SearchRequest.builder()
        .similarityThreshold(0.7)  // 相似度阈值
        .topK(5)                   // 返回前5个结果
        .filterExpression(...)     // 元数据过滤
        .build())
    .build();

String response = chatClientBuilder.build()
    .prompt()
    .advisors(qaAdvisor)  // 添加RAG Advisor
    .user(userQuery)
    .call()
    .content();
```

**工作流程:**
1. 将用户问题向量化
2. 在 Redis 中搜索最相似的文档块
3. 将检索结果作为上下文
4. 调用 LLM 生成回答

---

## 📝 下一步TODO

### 立即需要做的 (P0)

1. **安装 Redis Stack**
```bash
# Docker 方式
docker run -d --name redis-stack \
  -p 6379:6379 \
  -p 8001:8001 \
  redis/redis-stack:latest
```

2. **运行数据库初始化脚本**
```bash
mysql -u root -p ican < src/main/resources/db/003-create-rag-tables.sql
```

3. **配置 OpenAI Embedding API**
```yaml
spring.ai:
  openai:
    api-key: your-api-key
    embedding:
      options:
        model: text-embedding-ada-002
```

4. **测试文档上传和检索**
```bash
# 上传文档
curl -X POST http://localhost:8080/api/documents/upload \
  -H "Authorization: Bearer {token}" \
  -F "file=@paper.pdf" \
  -F "type=research_paper"

# 检索文档
curl "http://localhost:8080/api/documents/search?query=深度学习&topK=5" \
  -H "Authorization: Bearer {token}"
```

### 短期TODO (Week 1-2)

- [ ] 实现 MinIO 文件存储集成
- [ ] 完善 PDF/Word 文档解析
- [ ] 实现异步文档处理(RabbitMQ)
- [ ] 添加文档处理进度查询
- [ ] 实现 Elasticsearch 全文检索
- [ ] 优化向量检索性能

### 中期TODO (Week 3-4)

- [ ] 实现混合检索(向量+全文)
- [ ] 添加查询改写功能
- [ ] 实现文档去重和重排序
- [ ] 添加 Token 使用统计
- [ ] 实现批量文档导入
- [ ] 添加文档分类和标签

### 长期TODO (Week 5+)

- [ ] 实现虚拟教师功能
- [ ] 添加个性化推荐
- [ ] 实现作业批改功能
- [ ] 添加知识图谱
- [ ] 性能优化和监控
- [ ] 前端界面开发

---

## 🎨 API 接口总览

### 文档管理

| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/documents/upload` | POST | 上传文档 |
| `/api/documents/search` | GET | 检索文档 |
| `/api/documents/{id}` | DELETE | 删除文档 |

### RAG 功能

| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/rag/chat` | POST | RAG问答 |
| `/api/rag/document-chat` | POST | 文档问答 |
| `/api/rag/summarize-paper` | POST | 论文总结 |
| `/api/rag/generate-teaching-plan` | POST | 生成教学设计 |

### 现有聊天功能 (已实现)

| 接口 | 方法 | 描述 |
|------|------|------|
| `/ai/chat` | POST | 普通聊天 |
| `/ai/chat/stream` | POST | 流式聊天 |
| `/ai/sessions` | GET | 获取会话列表 |

---

## 📊 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      前端 (Vue/React)                        │
│  [文档上传] [问答界面] [教案生成] [虚拟教师] [会话管理]      │
└───────────────────────────┬─────────────────────────────────┘
                            │ HTTP/WebSocket
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   Spring Boot 后端                           │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │DocumentCtrl  │  │  RAGCtrl     │  │  OpenAICtrl     │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬────────┘  │
│         │                 │                    │           │
│  ┌──────▼──────────────────▼────────────────────▼───────┐  │
│  │           Service Layer                              │  │
│  │                                                       │  │
│  │  [DocumentService] [RAGService] [ChatService]       │  │
│  └──────┬─────────────────┬──────────────────┬─────────┘  │
│         │                 │                  │             │
└─────────┼─────────────────┼──────────────────┼─────────────┘
          │                 │                  │
          ↓                 ↓                  ↓
┌─────────────┐   ┌──────────────────┐   ┌────────────┐
│   MinIO     │   │  Redis Stack     │   │   MySQL    │
│ (文件存储)   │   │  (向量存储)       │   │  (业务数据) │
└─────────────┘   └──────────────────┘   └────────────┘
                           ↑
                           │
                  ┌────────▼─────────┐
                  │  OpenAI API      │
                  │ (Embedding模型)  │
                  └──────────────────┘
```

---

## ⚙️ 环境准备

### 必需组件

1. **Redis Stack** (含 RediSearch)
```bash
docker run -d --name redis-stack \
  -p 6379:6379 \
  -p 8001:8001 \
  redis/redis-stack:latest
```

2. **MySQL 8.0**
```bash
# 已有,执行新的建表脚本
mysql -u root -p ican < src/main/resources/db/003-create-rag-tables.sql
```

3. **OpenAI Embedding API**
```yaml
spring.ai:
  openai:
    api-key: your-api-key
    base-url: https://api.openai.com  # 或使用兼容的国内API
```

### 可选组件 (后续添加)

4. **MinIO** (文件存储)
```bash
docker run -d --name minio \
  -p 9000:9000 \
  -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio server /data --console-address ":9001"
```

5. **Elasticsearch** (全文检索)
```bash
docker run -d --name elasticsearch \
  -p 9200:9200 \
  -p 9300:9300 \
  -e "discovery.type=single-node" \
  elasticsearch:8.11.0
```

6. **RabbitMQ** (消息队列)
```bash
docker run -d --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3-management
```

---

## 🚀 快速测试

### 1. 启动应用
```bash
mvn spring-boot:run
```

### 2. 上传测试文档
```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -H "Authorization: Bearer {your-token}" \
  -F "file=@test.pdf" \
  -F "type=research_paper"
```

### 3. RAG 问答测试
```bash
curl -X POST http://localhost:8080/api/rag/chat \
  -H "Authorization: Bearer {your-token}" \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "test-001",
    "query": "请介绍一下文档中的主要内容"
  }'
```

### 4. 文档检索测试
```bash
curl "http://localhost:8080/api/documents/search?query=深度学习&topK=3" \
  -H "Authorization: Bearer {your-token}"
```

---

## 📈 性能指标

### 目标性能

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 文档上传 | < 2s | 10MB文件 |
| 文档向量化 | < 10s | 100页PDF |
| 向量检索 | < 100ms | TopK=5 |
| RAG问答 | < 3s | 含检索+生成 |
| 并发支持 | 100+ | 同时在线用户 |

### 优化策略

1. **批量处理**: 文档分块批量向量化
2. **缓存机制**: 缓存常见查询结果
3. **异步处理**: 文档处理使用消息队列
4. **索引优化**: Redis 向量索引优化

---

## 🐛 常见问题

### Q1: Redis 报错 "ERR unknown command 'FT.CREATE'"
**A**: 需要使用 Redis Stack,不是普通 Redis
```bash
# 安装 Redis Stack
docker run -d redis/redis-stack:latest
```

### Q2: 嵌入模型调用失败
**A**: 检查 OpenAI API Key 配置
```yaml
spring.ai:
  openai:
    api-key: sk-xxx  # 确保有效
    embedding:
      options:
        model: text-embedding-ada-002
```

### Q3: 文档解析失败
**A**: 检查文件格式和大小
- 支持格式: PDF, Word, Markdown, TXT
- 最大大小: 50MB (可配置)

### Q4: 向量检索无结果
**A**: 
1. 检查相似度阈值是否过高
2. 确认文档已向量化
3. 验证查询文本与文档语言一致

---

## 📚 参考资料

- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [Spring AI RAG 指南](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Redis Vector Store](https://docs.spring.io/spring-ai/reference/api/vectordbs/redis.html)

---

## 🎉 开始使用

您的 RAG 系统基础框架已搭建完成!

**下一步:**
1. 安装 Redis Stack
2. 运行数据库初始化脚本
3. 配置 OpenAI Embedding API
4. 启动应用并测试

**祝开发顺利!** 🚀

