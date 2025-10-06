# ICan AI 教育平台 API 文档

## 概述

ICan AI 教育平台是一个基于 Spring Boot + Spring AI 的智能教育系统，提供用户认证、文档管理、RAG问答、虚拟教师等功能。

**基础信息：**
- 基础URL: `http://localhost:8080`
- 认证方式: Sa-Token (Bearer Token)
- 内容类型: `application/json`
- 字符编码: `UTF-8`

## 认证说明

除了登录、注册、获取验证码等公开接口外，所有接口都需要在请求头中携带认证Token：

```http
Authorization: Bearer <your-token>
```

## 1. 认证管理 API

### 1.1 获取验证码

**接口描述：** 获取图形验证码

**请求信息：**
- **URL:** `GET /auth/captcha`
- **认证：** 无需认证
- **参数：** 无

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "captchaKey": "captcha:1234567890",
    "captchaImage": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...",
    "expireTime": 300
  }
}
```

**响应字段说明：**
| 字段 | 类型 | 说明 |
|------|------|------|
| captchaKey | String | 验证码唯一标识，用于后续验证 |
| captchaImage | String | Base64编码的验证码图片 |
| expireTime | Long | 验证码过期时间（秒） |

### 1.2 用户登录

**接口描述：** 用户通过用户名和密码进行登录

**请求信息：**
- **URL:** `POST /auth/login`
- **认证：** 无需认证
- **Content-Type:** `application/json`

**请求参数：**
```json
{
  "username": "admin",
  "password": "123456",
  "captcha": "abcd",
  "captchaKey": "captcha:1234567890"
}
```

**请求字段说明：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | String | 是 | 用户名 |
| password | String | 是 | 密码 |
| captcha | String | 否 | 验证码 |
| captchaKey | String | 否 | 验证码标识 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenName": "Authorization",
    "tokenPrefix": "Bearer ",
    "tokenTimeout": 2592000,
    "userInfo": {
      "userId": 1,
      "username": "admin",
      "nickname": "管理员",
      "email": "admin@example.com",
      "avatar": "https://example.com/avatar.jpg",
      "gender": 1,
      "phone": "13800138000",
      "lastLoginTime": "2024-10-07T10:30:00",
      "lastLoginIp": "127.0.0.1"
    }
  }
}
```

### 1.3 用户注册

**接口描述：** 新用户注册账号

**请求信息：**
- **URL:** `POST /auth/register`
- **认证：** 无需认证
- **Content-Type:** `application/json`

**请求参数：**
```json
{
  "username": "testuser",
  "password": "123456",
  "confirmPassword": "123456",
  "email": "user@example.com",
  "captcha": "abcd",
  "captchaKey": "captcha:1234567890"
}
```

**请求字段说明：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | String | 是 | 用户名，4-20个字符，只能包含字母、数字和下划线 |
| password | String | 是 | 密码，6-20个字符 |
| confirmPassword | String | 是 | 确认密码 |
| email | String | 否 | 邮箱地址 |
| captcha | String | 否 | 验证码 |
| captchaKey | String | 否 | 验证码标识 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "注册成功"
}
```

### 1.4 退出登录

**接口描述：** 用户退出当前登录状态

**请求信息：**
- **URL:** `POST /auth/logout`
- **认证：** 需要认证
- **参数：** 无

**响应示例：**
```json
{
  "code": 0,
  "msg": "退出成功"
}
```

### 1.5 检查登录状态

**接口描述：** 检查用户当前是否处于登录状态

**请求信息：**
- **URL:** `GET /auth/check`
- **认证：** 无需认证
- **参数：** 无

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": true
}
```

### 1.6 获取当前用户ID

**接口描述：** 获取当前登录用户的ID

**请求信息：**
- **URL:** `GET /auth/current-user-id`
- **认证：** 需要认证
- **参数：** 无

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": 1
}
```

## 2. DeepSeek AI 聊天 API

### 2.1 发送聊天消息

**接口描述：** 支持上下文对话，如果不传conversationId则创建新会话

**请求信息：**
- **URL:** `POST /ai/chat`
- **认证：** 需要认证
- **Content-Type:** `application/json`

**请求参数：**
```json
{
  "conversationId": "conv_1234567890",
  "message": "你好，请介绍一下Spring Boot",
  "enableRag": false,
  "ragTopK": 5
}
```

**请求字段说明：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| conversationId | String | 否 | 会话ID，不传则创建新会话 |
| message | String | 是 | 用户消息内容 |
| enableRag | Boolean | 否 | 是否启用RAG检索增强生成，默认false |
| ragTopK | Integer | 否 | RAG检索文档数量，默认5 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "conversationId": "conv_1234567890",
    "userMessage": "你好，请介绍一下Spring Boot",
    "aiResponse": "Spring Boot是一个基于Spring框架的快速开发框架...",
    "timestamp": "2024-10-07T10:30:00"
  }
}
```

### 2.2 流式发送聊天消息

**接口描述：** 使用SSE流式传输AI回复，逐字显示

**请求信息：**
- **URL:** `POST /ai/chat/stream`
- **认证：** 需要认证
- **Content-Type:** `application/json`
- **Accept:** `text/event-stream`

**请求参数：** 同2.1

**响应格式：** Server-Sent Events (SSE)
```
data: {"conversationId":"conv_1234567890","userMessage":"你好","aiResponse":"你好！我是AI助手，很高兴为您服务。"}

data: {"conversationId":"conv_1234567890","userMessage":"你好","aiResponse":"你好！我是AI助手，很高兴为您服务。有什么可以帮助您的吗？"}

data: [DONE]
```

### 2.3 获取会话列表

**接口描述：** 获取用户的所有会话列表

**请求信息：**
- **URL:** `GET /ai/sessions`
- **认证：** 需要认证
- **参数：** 无

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": [
    {
      "id": 1,
      "conversationId": "conv_1234567890",
      "title": "Spring Boot 学习讨论",
      "createTime": "2024-10-07T10:00:00",
      "updateTime": "2024-10-07T10:30:00"
    },
    {
      "id": 2,
      "conversationId": "conv_0987654321",
      "title": "Java 基础问题",
      "createTime": "2024-10-07T09:00:00",
      "updateTime": "2024-10-07T09:15:00"
    }
  ]
}
```

### 2.4 获取会话历史

**接口描述：** 获取指定会话的所有历史消息

**请求信息：**
- **URL:** `GET /ai/sessions/{conversationId}/history`
- **认证：** 需要认证

**路径参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| conversationId | String | 是 | 会话ID |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": [
    {
      "id": 1,
      "role": "user",
      "content": "你好，请介绍一下Spring Boot",
      "createTime": "2024-10-07T10:00:00"
    },
    {
      "id": 2,
      "role": "assistant",
      "content": "Spring Boot是一个基于Spring框架的快速开发框架...",
      "createTime": "2024-10-07T10:00:05"
    }
  ]
}
```

### 2.5 创建新会话

**接口描述：** 创建一个新的聊天会话

**请求信息：**
- **URL:** `POST /ai/sessions`
- **认证：** 需要认证
- **Content-Type:** `application/x-www-form-urlencoded`

**请求参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | Long | 否 | 用户ID |
| title | String | 否 | 会话标题 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": "conv_1234567890"
}
```

### 2.6 删除会话

**接口描述：** 删除指定会话及其所有历史消息

**请求信息：**
- **URL:** `DELETE /ai/sessions/{conversationId}`
- **认证：** 需要认证

**路径参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| conversationId | String | 是 | 会话ID |

**响应示例：**
```json
{
  "code": 0,
  "msg": "删除成功"
}
```

### 2.7 清空会话历史

**接口描述：** 清空指定会话的所有历史消息

**请求信息：**
- **URL:** `DELETE /ai/sessions/{conversationId}/history`
- **认证：** 需要认证

**路径参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| conversationId | String | 是 | 会话ID |

**响应示例：**
```json
{
  "code": 0,
  "msg": "清空成功"
}
```

## 3. 文档管理 API

### 3.1 上传文档

**接口描述：** 支持 PDF、Word、Markdown、TXT 格式的文档上传

**请求信息：**
- **URL:** `POST /api/documents/upload`
- **认证：** 需要认证
- **Content-Type:** `multipart/form-data`

**请求参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | File | 是 | 文档文件 |
| type | String | 否 | 文档类型，可选值：research_paper, teaching_material, other（默认：other） |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "documentId": 123,
    "message": "文档上传成功,正在处理中..."
  }
}
```

### 3.2 获取文档列表

**接口描述：** 获取当前用户上传的所有文档

**请求信息：**
- **URL:** `GET /api/documents/list`
- **认证：** 需要认证

**查询参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | 否 | 文档类型过滤 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": [
    {
      "id": 123,
      "title": "Spring Boot 开发指南.pdf",
      "type": "research_paper",
      "fileSize": 2048576,
      "status": "completed",
      "createTime": "2024-10-07T10:00:00",
      "updateTime": "2024-10-07T10:05:00"
    }
  ]
}
```

### 3.3 获取文档详情

**接口描述：** 获取指定文档的详细信息

**请求信息：**
- **URL:** `GET /api/documents/{documentId}`
- **认证：** 需要认证

**路径参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| documentId | Long | 是 | 文档ID |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "id": 123,
    "title": "Spring Boot 开发指南.pdf",
    "type": "research_paper",
    "fileSize": 2048576,
    "status": "completed",
    "createTime": "2024-10-07T10:00:00",
    "updateTime": "2024-10-07T10:05:00"
  }
}
```

### 3.4 检索相关文档

**接口描述：** 基于向量相似度检索最相关的文档

**请求信息：**
- **URL:** `GET /api/documents/search`
- **认证：** 需要认证

**查询参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| query | String | 是 | 查询文本 |
| topK | Integer | 否 | 返回数量，默认5 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "query": "Spring Boot 配置",
    "results": [
      {
        "content": "Spring Boot 提供了多种配置方式...",
        "metadata": {
          "documentId": "123",
          "chunkIndex": 0,
          "source": "Spring Boot 开发指南.pdf"
        }
      }
    ]
  }
}
```

### 3.5 删除文档

**接口描述：** 删除文档及其向量数据

**请求信息：**
- **URL:** `DELETE /api/documents/{documentId}`
- **认证：** 需要认证

**路径参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| documentId | Long | 是 | 文档ID |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "message": "文档删除成功"
  }
}
```

## 4. RAG 功能 API

### 4.1 RAG 问答

**接口描述：** 基于知识库的智能问答

**请求信息：**
- **URL:** `POST /api/rag/chat`
- **认证：** 需要认证
- **Content-Type:** `application/x-www-form-urlencoded`

**请求参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| conversationId | String | 是 | 会话ID |
| query | String | 是 | 用户问题 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": "基于您的知识库，Spring Boot是一个基于Spring框架的快速开发框架..."
}
```

### 4.2 文档问答

**接口描述：** 针对特定文档的问答

**请求信息：**
- **URL:** `POST /api/rag/document-chat`
- **认证：** 需要认证
- **Content-Type:** `application/x-www-form-urlencoded`

**请求参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| documentId | Long | 是 | 文档ID |
| query | String | 是 | 用户问题 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": "根据文档内容，Spring Boot的自动配置机制..."
}
```

### 4.3 论文总结

**接口描述：** 自动生成论文结构化总结

**请求信息：**
- **URL:** `POST /api/rag/summarize-paper`
- **认证：** 需要认证
- **Content-Type:** `application/x-www-form-urlencoded`

**请求参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| documentId | Long | 是 | 论文文档ID |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "title": "基于深度学习的图像识别算法研究",
    "authors": ["张三", "李四", "王五"],
    "publicationYear": 2024,
    "summary": {
      "background": "随着计算机视觉技术的快速发展...",
      "methodology": "本文提出了一种基于卷积神经网络的...",
      "results": "实验结果表明，所提出的算法在准确率上...",
      "innovations": [
        "提出了新的网络架构",
        "改进了损失函数设计",
        "优化了训练策略"
      ],
      "limitations": "当前方法在处理小样本数据时仍存在挑战..."
    },
    "keywords": ["深度学习", "图像识别", "卷积神经网络", "计算机视觉"]
  }
}
```

### 4.4 生成教学设计

**接口描述：** 基于参考文档自动生成完整的教学设计方案

**请求信息：**
- **URL:** `POST /api/rag/generate-teaching-plan`
- **认证：** 需要认证
- **Content-Type:** `application/x-www-form-urlencoded`

**请求参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| topic | String | 是 | 课题 |
| grade | String | 是 | 学段 |
| subject | String | 是 | 学科 |
| documentIds | List<Long> | 否 | 参考文档ID列表 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": {
    "title": "Spring Boot 基础入门",
    "grade": "大学本科",
    "subject": "计算机科学",
    "duration": "2课时",
    "objectives": {
      "knowledge": [
        "理解Spring Boot的基本概念",
        "掌握Spring Boot的核心特性"
      ],
      "skills": [
        "能够创建Spring Boot项目",
        "能够配置Spring Boot应用"
      ],
      "values": [
        "培养编程思维",
        "提高解决问题的能力"
      ]
    },
    "keyPoints": [
      "Spring Boot自动配置原理",
      "Spring Boot Starter机制"
    ],
    "difficulties": [
      "理解依赖注入机制",
      "掌握配置文件的优先级"
    ],
    "teachingSteps": [
      {
        "step": 1,
        "name": "导入新课",
        "duration": "10分钟",
        "content": "通过实际案例引入Spring Boot概念",
        "activities": ["案例分析", "问题讨论"]
      },
      {
        "step": 2,
        "name": "讲解核心概念",
        "duration": "30分钟",
        "content": "详细讲解Spring Boot的核心特性",
        "activities": ["理论讲解", "代码演示"]
      }
    ],
    "evaluation": {
      "methods": ["课堂提问", "实践操作", "作业检查"],
      "criteria": ["理解程度", "操作熟练度", "问题解决能力"]
    },
    "assignments": [
      "创建一个简单的Spring Boot项目",
      "实现一个RESTful API接口"
    ],
    "resources": [
      "Spring Boot官方文档",
      "示例代码",
      "教学PPT"
    ]
  }
}
```

## 5. 虚拟教师 API

### 5.1 智能对话

**接口描述：** 与虚拟教师进行智能对话，支持多种角色

**请求信息：**
- **URL:** `POST /api/virtual-teacher/chat`
- **认证：** 需要认证
- **Content-Type:** `application/x-www-form-urlencoded`

**请求参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| conversationId | String | 是 | 会话ID |
| query | String | 是 | 用户问题 |
| role | String | 否 | 角色类型，可选值：teacher, student, researcher（默认：student） |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": "作为您的学习伙伴，我来帮您解答这个问题..."
}
```

### 5.2 流式对话

**接口描述：** 流式返回虚拟教师的对话内容

**请求信息：**
- **URL:** `POST /api/virtual-teacher/chat/stream`
- **认证：** 需要认证
- **Content-Type:** `application/x-www-form-urlencoded`
- **Accept:** `text/event-stream`

**请求参数：** 同5.1

**响应格式：** Server-Sent Events (SSE)
```
data: 作为您的学习伙伴

data: 作为您的学习伙伴，我来帮您解答这个问题

data: 作为您的学习伙伴，我来帮您解答这个问题。首先让我们从基础概念开始...

data: [DONE]
```

### 5.3 推荐学习内容

**接口描述：** 基于用户画像推荐学习内容

**请求信息：**
- **URL:** `GET /api/virtual-teacher/recommend`
- **认证：** 需要认证

**查询参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| subject | String | 是 | 学科 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": [
    "Spring Boot 基础教程",
    "微服务架构设计模式",
    "RESTful API 最佳实践",
    "Spring Security 安全配置"
  ]
}
```

### 5.4 批改作业

**接口描述：** AI自动批改作业并给出反馈

**请求信息：**
- **URL:** `POST /api/virtual-teacher/grade-assignment`
- **认证：** 需要认证
- **Content-Type:** `application/x-www-form-urlencoded`

**请求参数：**
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| assignment | String | 是 | 作业内容 |
| subject | String | 是 | 学科 |

**响应示例：**
```json
{
  "code": 0,
  "msg": "操作成功",
  "data": "作业批改结果：\n\n优点：\n1. 代码结构清晰，注释完整\n2. 使用了合适的设计模式\n\n需要改进：\n1. 异常处理可以更加完善\n2. 建议添加单元测试\n\n总体评分：85分"
}
```

## 6. 错误码说明

### 6.1 通用错误码

| 错误码 | 说明 | 解决方案 |
|--------|------|----------|
| 0 | 操作成功 | - |
| 1 | 操作失败 | 查看具体错误信息 |
| 401 | 未登录 | 请先登录 |
| 403 | 权限不足 | 检查用户权限 |
| 404 | 资源不存在 | 检查请求路径和参数 |
| 500 | 服务器内部错误 | 联系技术支持 |

### 6.2 业务错误码

| 错误码 | 说明 | 解决方案 |
|--------|------|----------|
| 1001 | 用户名或密码错误 | 检查登录凭据 |
| 1002 | 验证码错误 | 重新获取验证码 |
| 1003 | 用户已存在 | 使用其他用户名 |
| 2001 | 文档格式不支持 | 使用支持的文档格式 |
| 2002 | 文档处理失败 | 检查文档内容是否完整 |
| 3001 | 会话不存在 | 检查会话ID是否正确 |
| 3002 | 消息内容为空 | 提供有效的消息内容 |

## 7. 数据模型说明

### 7.1 用户信息 (UserInfoVO)

```json
{
  "userId": 1,
  "username": "admin",
  "nickname": "管理员",
  "email": "admin@example.com",
  "avatar": "https://example.com/avatar.jpg",
  "gender": 1,
  "phone": "13800138000",
  "lastLoginTime": "2024-10-07T10:30:00",
  "lastLoginIp": "127.0.0.1"
}
```

### 7.2 聊天会话 (ChatSessionVO)

```json
{
  "id": 1,
  "conversationId": "conv_1234567890",
  "title": "Spring Boot 学习讨论",
  "createTime": "2024-10-07T10:00:00",
  "updateTime": "2024-10-07T10:30:00"
}
```

### 7.3 聊天消息 (ChatMessageVO)

```json
{
  "id": 1,
  "role": "user",
  "content": "你好，请介绍一下Spring Boot",
  "createTime": "2024-10-07T10:00:00"
}
```

### 7.4 文档信息 (DocumentVO)

```json
{
  "id": 123,
  "title": "Spring Boot 开发指南.pdf",
  "type": "research_paper",
  "fileSize": 2048576,
  "status": "completed",
  "createTime": "2024-10-07T10:00:00",
  "updateTime": "2024-10-07T10:05:00"
}
```

## 8. 接口调用示例

### 8.1 JavaScript/Axios 示例

```javascript
// 设置基础配置
const api = axios.create({
  baseURL: 'http://localhost:8080',
  timeout: 30000
});

// 请求拦截器 - 添加认证Token
api.interceptors.request.use(config => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 响应拦截器 - 处理错误
api.interceptors.response.use(
  response => response.data,
  error => {
    if (error.response?.status === 401) {
      // 未登录，跳转到登录页
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

// 登录示例
async function login(username, password, captcha, captchaKey) {
  try {
    const response = await api.post('/auth/login', {
      username,
      password,
      captcha,
      captchaKey
    });
    
    if (response.code === 0) {
      // 保存Token
      localStorage.setItem('token', response.data.token);
      return response.data;
    } else {
      throw new Error(response.msg);
    }
  } catch (error) {
    console.error('登录失败:', error);
    throw error;
  }
}

// 发送聊天消息示例
async function sendChatMessage(message, conversationId) {
  try {
    const response = await api.post('/ai/chat', {
      conversationId,
      message,
      enableRag: false,
      ragTopK: 5
    });
    
    return response.data;
  } catch (error) {
    console.error('发送消息失败:', error);
    throw error;
  }
}

// 流式聊天示例
function streamChat(message, conversationId, onMessage, onComplete, onError) {
  const eventSource = new EventSource(`/ai/chat/stream?conversationId=${conversationId}&message=${encodeURIComponent(message)}`);
  
  eventSource.onmessage = function(event) {
    if (event.data === '[DONE]') {
      eventSource.close();
      onComplete && onComplete();
    } else {
      const data = JSON.parse(event.data);
      onMessage && onMessage(data);
    }
  };
  
  eventSource.onerror = function(error) {
    eventSource.close();
    onError && onError(error);
  };
}
```

### 8.2 Python/Requests 示例

```python
import requests
import json

class ICanAPI:
    def __init__(self, base_url='http://localhost:8080'):
        self.base_url = base_url
        self.session = requests.Session()
        self.token = None
    
    def set_token(self, token):
        """设置认证Token"""
        self.token = token
        self.session.headers.update({
            'Authorization': f'Bearer {token}'
        })
    
    def login(self, username, password, captcha=None, captcha_key=None):
        """用户登录"""
        url = f'{self.base_url}/auth/login'
        data = {
            'username': username,
            'password': password,
            'captcha': captcha,
            'captchaKey': captcha_key
        }
        
        response = self.session.post(url, json=data)
        result = response.json()
        
        if result['code'] == 0:
            self.set_token(result['data']['token'])
            return result['data']
        else:
            raise Exception(result['msg'])
    
    def send_chat_message(self, message, conversation_id=None, enable_rag=False, rag_top_k=5):
        """发送聊天消息"""
        url = f'{self.base_url}/ai/chat'
        data = {
            'message': message,
            'conversationId': conversation_id,
            'enableRag': enable_rag,
            'ragTopK': rag_top_k
        }
        
        response = self.session.post(url, json=data)
        result = response.json()
        
        if result['code'] == 0:
            return result['data']
        else:
            raise Exception(result['msg'])
    
    def upload_document(self, file_path, doc_type='other'):
        """上传文档"""
        url = f'{self.base_url}/api/documents/upload'
        
        with open(file_path, 'rb') as f:
            files = {'file': f}
            data = {'type': doc_type}
            
            response = self.session.post(url, files=files, data=data)
            result = response.json()
            
            if result['code'] == 0:
                return result['data']
            else:
                raise Exception(result['msg'])

# 使用示例
api = ICanAPI()

# 登录
try:
    login_result = api.login('admin', '123456')
    print(f"登录成功，用户ID: {login_result['userInfo']['userId']}")
except Exception as e:
    print(f"登录失败: {e}")

# 发送聊天消息
try:
    chat_result = api.send_chat_message("你好，请介绍一下Spring Boot")
    print(f"AI回复: {chat_result['aiResponse']}")
except Exception as e:
    print(f"发送消息失败: {e}")

# 上传文档
try:
    upload_result = api.upload_document('/path/to/document.pdf', 'research_paper')
    print(f"文档上传成功，文档ID: {upload_result['documentId']}")
except Exception as e:
    print(f"文档上传失败: {e}")
```

## 9. 注意事项

### 9.1 认证相关
- 除了公开接口外，所有接口都需要在请求头中携带 `Authorization: Bearer <token>`
- Token有效期为30天，过期后需要重新登录
- 建议在客户端实现Token自动刷新机制

### 9.2 文件上传
- 支持的文件格式：PDF、DOCX、Markdown、TXT
- 单个文件最大大小：10MB
- 请求最大大小：100MB
- 建议在上传前检查文件格式和大小

### 9.3 流式响应
- 流式接口使用Server-Sent Events (SSE)协议
- 客户端需要正确处理SSE事件流
- 建议设置合适的超时时间

### 9.4 错误处理
- 所有接口都返回统一的响应格式
- 建议根据错误码进行相应的错误处理
- 网络错误和业务错误需要分别处理

### 9.5 性能优化
- 建议对频繁调用的接口进行缓存
- 大文件上传建议使用分片上传
- 流式接口建议实现断线重连机制

---

**文档版本：** v1.0  
**最后更新：** 2024-10-07  
**联系方式：** example@example.com
