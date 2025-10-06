# 教育科研智能助手系统 RAG 实现任务规划

## 项目概述

基于 Spring AI 构建教育科研智能助手系统,实现论文助手、教学设计生成、虚拟教师三大核心功能。

## 技术栈

- **后端框架**: Spring Boot 3.3.5 + Spring AI 1.0.3
- **AI 模型**: DeepSeek Chat (已集成)
- **向量数据库**: Redis (RediSearch + RedisJSON)
- **文档存储**: MinIO
- **全文检索**: Elasticsearch
- **消息队列**: RabbitMQ
- **数据库**: MySQL 8.0
- **缓存**: Redis

## 任务分解

### 阶段一: 基础设施搭建 (Week 1-2)

#### 1.1 依赖管理 ✅ 进行中
- [x] 添加 Spring AI 核心依赖
- [ ] 添加 Redis 向量存储依赖
- [ ] 添加文档解析依赖 (Tika/PDF Box)
- [ ] 添加 Elasticsearch 依赖
- [ ] 添加 RabbitMQ 依赖
- [ ] 添加 MinIO 依赖

**工作量**: 0.5天

#### 1.2 Redis 向量数据库配置
- [ ] 配置 Redis Stack (RediSearch + RedisJSON)
- [ ] 创建向量索引配置类
- [ ] 实现 RedisVectorStore Bean
- [ ] 配置嵌入模型 (使用 OpenAI Embedding)
- [ ] 编写向量存储测试用例

**工作量**: 2天
**优先级**: P0

#### 1.3 Elasticsearch 配置
- [ ] 配置 Elasticsearch 连接
- [ ] 创建文档索引映射
- [ ] 实现全文检索服务
- [ ] 配置中文分词器

**工作量**: 1.5天
**优先级**: P1

#### 1.4 MinIO 文件存储配置
- [ ] 配置 MinIO 连接
- [ ] 创建存储桶 (papers, teaching-plans, documents)
- [ ] 实现文件上传/下载服务
- [ ] 配置文件访问权限

**工作量**: 1天
**优先级**: P1

#### 1.5 RabbitMQ 配置
- [ ] 配置 RabbitMQ 连接
- [ ] 创建消息队列 (document-processing, rag-indexing)
- [ ] 实现消息生产者/消费者
- [ ] 配置死信队列

**工作量**: 1.5天
**优先级**: P2

---

### 阶段二: 文档处理与向量化 (Week 3-4)

#### 2.1 文档解析模块
- [ ] 实现 PDF 文档解析 (Apache Tika)
- [ ] 实现 Word 文档解析
- [ ] 实现 Markdown 文档解析
- [ ] 实现文档分块策略 (TokenTextSplitter)
- [ ] 提取文档元数据 (作者、标题、摘要等)

**工作量**: 3天
**优先级**: P0

**实现要点**:
```java
// 文档分块配置
TokenTextSplitter splitter = new TokenTextSplitter(
    500,  // chunkSize: 每块500个token
    100   // chunkOverlap: 块间重叠100个token
);
```

#### 2.2 ETL Pipeline 实现
- [ ] 创建文档导入流程
  - 文档上传 → 格式解析 → 内容分块 → 元数据提取
- [ ] 实现向量嵌入生成
  - 使用 OpenAI Embedding API
  - 批量处理优化
- [ ] 向量存储写入
  - 存入 Redis Vector Store
  - 建立元数据索引
- [ ] 实现增量更新机制

**工作量**: 4天
**优先级**: P0

**流程图**:
```
文档上传 → MinIO存储 → 消息队列 → 文档解析
    ↓
文本分块 → 嵌入生成 → Redis向量库
    ↓
元数据提取 → Elasticsearch索引
```

#### 2.3 文档管理服务
- [ ] 创建文档实体类 (DocumentDO)
- [ ] 实现文档 CRUD 操作
- [ ] 实现文档分类管理
- [ ] 实现文档权限控制
- [ ] 实现文档版本管理

**工作量**: 2天
**优先级**: P1

---

### 阶段三: RAG 核心功能实现 (Week 5-7)

#### 3.1 向量检索服务
- [ ] 实现相似度搜索
  - 配置 TopK 参数 (默认 5)
  - 配置相似度阈值 (默认 0.7)
- [ ] 实现混合检索 (向量 + 全文)
- [ ] 实现过滤表达式支持
  - 按文档类型过滤
  - 按用户权限过滤
  - 按时间范围过滤
- [ ] 实现重排序 (Re-ranking)

**工作量**: 3天
**优先级**: P0

**核心代码**:
```java
VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
    .vectorStore(vectorStore)
    .similarityThreshold(0.7)
    .topK(5)
    .filterExpression(new FilterExpressionBuilder()
        .eq("type", "research_paper")
        .and()
        .eq("userId", currentUserId)
        .build())
    .build();
```

#### 3.2 QuestionAnswerAdvisor 集成
- [ ] 配置 QuestionAnswerAdvisor
- [ ] 自定义 RAG Prompt 模板
- [ ] 实现动态过滤表达式
- [ ] 集成到 ChatClient
- [ ] 优化上下文长度管理

**工作量**: 2天
**优先级**: P0

#### 3.3 查询增强模块
- [ ] 实现查询改写 (RewriteQueryTransformer)
  - 优化模糊查询
  - 扩展同义词
- [ ] 实现多查询扩展 (MultiQueryExpander)
  - 生成3-5个语义相关查询
  - 合并检索结果
- [ ] 实现查询翻译 (TranslationQueryTransformer)
  - 支持中英文互译

**工作量**: 2.5天
**优先级**: P1

#### 3.4 后处理模块
- [ ] 实现文档去重
- [ ] 实现文档排序优化
- [ ] 实现上下文压缩
  - 移除无关内容
  - 保留关键信息
- [ ] 实现引用标注
  - 标记信息来源
  - 生成参考文献

**工作量**: 2天
**优先级**: P1

---

### 阶段四: 业务功能实现 (Week 8-10)

#### 4.1 论文助手模块

##### 4.1.1 论文上传与解析
- [ ] 实现论文上传接口
- [ ] 实现论文格式验证
- [ ] 实现论文解析服务
- [ ] 实现论文向量化
- [ ] 实现论文元数据提取

**API 设计**:
```
POST /api/papers/upload
POST /api/papers/{id}/parse
GET  /api/papers/{id}
DELETE /api/papers/{id}
```

**工作量**: 2天

##### 4.1.2 论文自动总结
- [ ] 实现摘要生成服务
  - 研究背景
  - 核心方法
  - 实验结果
  - 创新点提取
- [ ] 实现结构化输出
  - JSON 格式输出
  - 分段分级展示
- [ ] 支持多语言总结

**Prompt 模板**:
```
请对以下论文进行总结,按以下结构输出:
1. 研究背景 (200字)
2. 核心方法 (300字)
3. 实验结果 (200字)
4. 创新点 (3-5条)
5. 局限性 (100字)

论文内容:
{context}
```

**工作量**: 3天

##### 4.1.3 论文智能问答
- [ ] 实现基于 RAG 的问答
- [ ] 支持多轮对话
- [ ] 实现引用溯源
- [ ] 支持跨文献对比

**工作量**: 3天

#### 4.2 教学设计生成模块

##### 4.2.1 教案自动生成
- [ ] 实现教学目标生成
- [ ] 实现教学过程设计
- [ ] 实现教学评价方案
- [ ] 实现作业建议生成
- [ ] 支持多学段适配 (小学/初中/高中/大学)

**结构化输出示例**:
```java
@Data
public class TeachingPlan {
    private String title;           // 课题名称
    private String grade;           // 学段
    private String subject;         // 学科
    private List<String> objectives;  // 教学目标
    private List<TeachingStep> steps; // 教学过程
    private EvaluationPlan evaluation; // 评价方案
    private List<String> assignments;  // 作业建议
}
```

**工作量**: 4天

##### 4.2.2 教学资源推荐
- [ ] 基于课题检索相关论文
- [ ] 推荐教学案例
- [ ] 推荐多媒体资源
- [ ] 生成知识图谱

**工作量**: 2天

#### 4.3 虚拟教师模块

##### 4.3.1 智能对话系统
- [ ] 实现多角色对话 (教师/学生/教研员)
- [ ] 实现上下文记忆管理
- [ ] 实现个性化回复
- [ ] 支持语音输入/输出 (可选)

**工作量**: 3天

##### 4.3.2 智能推送系统
- [ ] 实现用户画像分析
- [ ] 实现内容推荐算法
- [ ] 实现定时推送
  - 学科热点
  - 前沿文献
  - 教学建议
- [ ] 实现推送效果追踪

**工作量**: 3天

##### 4.3.3 作业批改功能
- [ ] 实现作业上传
- [ ] 实现自动批改
- [ ] 实现批改结果结构化输出
- [ ] 实现改进建议生成

**工作量**: 2天

---

### 阶段五: 性能优化与安全加固 (Week 11-12)

#### 5.1 性能优化
- [ ] 实现向量检索缓存
- [ ] 实现批量处理优化
- [ ] 实现异步任务处理
- [ ] 实现分页加载
- [ ] 数据库查询优化
- [ ] 实现 CDN 加速

**工作量**: 3天

#### 5.2 安全加固
- [ ] 实现文档访问权限控制
- [ ] 实现敏感信息过滤
- [ ] 实现内容审核
- [ ] 实现防止 Prompt 注入
- [ ] 实现 API 限流
- [ ] 数据加密存储

**工作量**: 3天

#### 5.3 监控与日志
- [ ] 实现 RAG 调用链路追踪
- [ ] 实现性能监控
- [ ] 实现异常告警
- [ ] 实现日志聚合分析

**工作量**: 2天

---

### 阶段六: 测试与部署 (Week 13-14)

#### 6.1 测试
- [ ] 单元测试 (覆盖率 > 80%)
- [ ] 集成测试
- [ ] RAG 效果测试
  - 准确率评估
  - 响应时间评估
- [ ] 压力测试
- [ ] 安全测试

**工作量**: 4天

#### 6.2 部署
- [ ] Docker 镜像构建
- [ ] K8s 部署配置
- [ ] CI/CD 流程配置
- [ ] 生产环境配置
- [ ] 备份与恢复方案

**工作量**: 2天

#### 6.3 文档编写
- [ ] API 文档
- [ ] 部署文档
- [ ] 用户手册
- [ ] 开发文档

**工作量**: 2天

---

## 数据库设计

### 核心表结构

#### 1. 文档表 (documents)
```sql
CREATE TABLE documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '上传用户ID',
    title VARCHAR(255) NOT NULL COMMENT '文档标题',
    type VARCHAR(50) NOT NULL COMMENT '文档类型: research_paper, teaching_material, other',
    file_url VARCHAR(500) NOT NULL COMMENT 'MinIO文件路径',
    file_size BIGINT COMMENT '文件大小(字节)',
    status VARCHAR(20) COMMENT '处理状态: pending, processing, completed, failed',
    metadata JSON COMMENT '文档元数据',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    is_deleted TINYINT(1) DEFAULT 0,
    INDEX idx_user_id (user_id),
    INDEX idx_type (type),
    INDEX idx_status (status)
);
```

#### 2. 文档块表 (document_chunks)
```sql
CREATE TABLE document_chunks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL COMMENT '文档ID',
    chunk_index INT NOT NULL COMMENT '块索引',
    content TEXT NOT NULL COMMENT '文本内容',
    vector_id VARCHAR(100) COMMENT 'Redis向量ID',
    tokens INT COMMENT 'Token数量',
    metadata JSON COMMENT '块元数据',
    create_time DATETIME NOT NULL,
    INDEX idx_document_id (document_id),
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);
```

#### 3. 教学设计表 (teaching_plans)
```sql
CREATE TABLE teaching_plans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    grade VARCHAR(50) COMMENT '学段',
    subject VARCHAR(50) COMMENT '学科',
    content JSON COMMENT '教案内容(结构化)',
    source_papers JSON COMMENT '参考论文ID列表',
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    is_deleted TINYINT(1) DEFAULT 0,
    INDEX idx_user_id (user_id),
    INDEX idx_grade (grade),
    INDEX idx_subject (subject)
);
```

#### 4. RAG 查询日志表 (rag_query_logs)
```sql
CREATE TABLE rag_query_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    conversation_id VARCHAR(64),
    query TEXT NOT NULL COMMENT '用户查询',
    retrieved_docs JSON COMMENT '检索到的文档ID和分数',
    response TEXT COMMENT 'AI响应',
    retrieval_time INT COMMENT '检索耗时(ms)',
    generation_time INT COMMENT '生成耗时(ms)',
    total_tokens INT COMMENT '消耗Token数',
    create_time DATETIME NOT NULL,
    INDEX idx_user_id (user_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_create_time (create_time)
);
```

---

## 配置文件设计

### application-rag.yaml
```yaml
# RAG 配置
rag:
  # 向量检索配置
  retrieval:
    # 默认检索文档数量
    top-k: 5
    # 相似度阈值
    similarity-threshold: 0.7
    # 是否启用重排序
    enable-reranking: true
    # 是否启用混合检索
    enable-hybrid-search: true
  
  # 文档处理配置
  document:
    # 文档分块大小(token)
    chunk-size: 500
    # 块间重叠(token)
    chunk-overlap: 100
    # 支持的文件类型
    allowed-types:
      - pdf
      - docx
      - md
      - txt
    # 单个文件最大大小(MB)
    max-file-size: 50
  
  # 嵌入模型配置
  embedding:
    # 模型名称
    model: text-embedding-ada-002
    # 批量处理大小
    batch-size: 100
    # 维度
    dimension: 1536
  
  # Prompt 模板配置
  prompts:
    # 论文总结模板路径
    paper-summary: classpath:prompts/paper-summary.st
    # 教案生成模板路径
    teaching-plan: classpath:prompts/teaching-plan.st
    # 问答模板路径
    qa: classpath:prompts/qa.st

# Redis Vector Store 配置
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:
      # Redis Stack 配置
      redisearch:
        # 索引前缀
        index-prefix: ican:vector:
        # 向量算法
        vector-algorithm: HNSW
        # 距离度量
        distance-metric: COSINE

# Elasticsearch 配置
elasticsearch:
  uris: http://localhost:9200
  username: elastic
  password:
  # 索引配置
  indexes:
    documents: ican-documents
    teaching-plans: ican-teaching-plans

# MinIO 配置
minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  buckets:
    papers: ican-papers
    teaching-plans: ican-teaching-plans
    documents: ican-documents

# RabbitMQ 配置
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    # 队列配置
    queues:
      document-processing: ican.document.processing
      rag-indexing: ican.rag.indexing
```

---

## Prompt 模板设计

### 1. 论文总结模板 (paper-summary.st)
```
你是一位资深的学术研究专家。请仔细阅读以下论文内容,并按照指定格式进行总结。

## 论文内容
{context}

## 总结要求
请按以下JSON格式输出:

```json
{
  "title": "论文标题",
  "authors": ["作者1", "作者2"],
  "publicationYear": 2024,
  "summary": {
    "background": "研究背景(200字以内)",
    "methodology": "核心方法(300字以内)",
    "results": "实验结果(200字以内)",
    "innovations": [
      "创新点1",
      "创新点2",
      "创新点3"
    ],
    "limitations": "局限性(100字以内)"
  },
  "keywords": ["关键词1", "关键词2", "关键词3"]
}
```

注意:
1. 必须严格按照JSON格式输出
2. 内容要准确、简洁
3. 创新点要具体、可量化
4. 如果信息不足,标注为"未提及"
```

### 2. 教案生成模板 (teaching-plan.st)
```
你是一位经验丰富的教学设计专家。请根据提供的参考资料,生成一份完整的教学设计方案。

## 教学信息
- 课题: {topic}
- 学段: {grade}
- 学科: {subject}

## 参考资料
{context}

## 输出格式
请按以下JSON格式输出:

```json
{
  "title": "课题名称",
  "grade": "学段",
  "subject": "学科",
  "duration": "课时",
  "objectives": {
    "knowledge": ["知识目标1", "知识目标2"],
    "skills": ["能力目标1", "能力目标2"],
    "values": ["情感态度价值观目标1"]
  },
  "keyPoints": ["重点1", "重点2"],
  "difficulties": ["难点1", "难点2"],
  "teachingSteps": [
    {
      "step": 1,
      "name": "导入",
      "duration": "5分钟",
      "content": "具体内容",
      "activities": ["活动1", "活动2"]
    }
  ],
  "evaluation": {
    "methods": ["评价方法1", "评价方法2"],
    "criteria": ["评价标准1", "评价标准2"]
  },
  "assignments": ["作业1", "作业2"],
  "resources": ["资源1", "资源2"]
}
```
```

---

## 关键技术实现要点

### 1. 向量检索优化
```java
/**
 * 混合检索: 向量检索 + 全文检索
 */
public List<Document> hybridSearch(String query, Map<String, Object> filters) {
    // 1. 向量检索
    List<Document> vectorResults = vectorStore.similaritySearch(
        SearchRequest.query(query)
            .withTopK(10)
            .withSimilarityThreshold(0.6)
            .withFilterExpression(buildFilterExpression(filters))
    );
    
    // 2. 全文检索
    List<Document> textResults = elasticsearchService.search(query, filters);
    
    // 3. 结果合并与重排序
    return rerank(merge(vectorResults, textResults));
}
```

### 2. 文档分块策略
```java
/**
 * 递归字符分块器 - 保持语义完整性
 */
public List<String> splitDocument(String content) {
    RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(
        500,  // chunkSize
        100,  // chunkOverlap
        Arrays.asList("\n\n", "\n", "。", "！", "?", ";", ":", "，", " ")
    );
    return splitter.split(content);
}
```

### 3. 上下文压缩
```java
/**
 * 压缩检索结果,只保留最相关内容
 */
public String compressContext(List<Document> documents, String query) {
    return documents.stream()
        .map(doc -> extractRelevantSentences(doc.getContent(), query))
        .collect(Collectors.joining("\n\n"));
}
```

---

## 项目里程碑

| 阶段 | 时间 | 交付物 | 状态 |
|------|------|--------|------|
| 阶段一 | Week 1-2 | 基础设施搭建完成 | 🟡 进行中 |
| 阶段二 | Week 3-4 | 文档处理与向量化 | ⚪ 待开始 |
| 阶段三 | Week 5-7 | RAG 核心功能 | ⚪ 待开始 |
| 阶段四 | Week 8-10 | 业务功能实现 | ⚪ 待开始 |
| 阶段五 | Week 11-12 | 性能优化与安全 | ⚪ 待开始 |
| 阶段六 | Week 13-14 | 测试与部署 | ⚪ 待开始 |

---

## 下一步行动

### 立即开始 (本周)
1. ✅ 添加 Redis Vector Store 依赖
2. ✅ 添加文档解析依赖
3. ✅ 配置 Redis Stack
4. ✅ 实现基础的向量存储服务
5. ✅ 实现简单的文档上传接口

### 本月目标
- 完成阶段一和阶段二的所有任务
- 实现基本的论文上传和向量化功能
- 实现基础的 RAG 问答功能

---

## 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| 向量数据库性能瓶颈 | 高 | 提前进行性能测试,考虑分片策略 |
| 大模型调用成本高 | 中 | 实现缓存机制,优化 Prompt |
| 文档解析准确率低 | 中 | 多种解析器备选,人工校验 |
| 检索召回率不足 | 高 | 优化分块策略,调整检索参数 |

---

**创建时间**: 2024-10-06
**最后更新**: 2024-10-06
**负责人**: 开发团队
**状态**: 🟡 进行中

