# ICan - ContiNew Starter 项目

基于 ContiNew Starter 2.13.4 构建的企业级应用脚手架

## 已集成模块

### 核心模块
- ✅ **continew-starter-core** - 核心模块,提供基础配置与通用工具类
- ✅ **continew-starter-web** - Web 模块,提供跨域、全局异常处理、响应封装等
- ✅ **continew-starter-json-jackson** - JSON 处理模块(Jackson)

### 接口文档
- ✅ **continew-starter-api-doc** - API 文档模块(Spring Doc + Knife4j)
  - 访问地址: http://localhost:8080/doc.html

### 数据访问
- ✅ **continew-starter-data-mp** - MyBatis Plus 数据访问模块
- ✅ **mysql-connector-j** - MySQL 驱动

### 缓存
- ✅ **continew-starter-cache-redisson** - Redisson 缓存模块

### 认证授权
- ✅ **continew-starter-auth-satoken** - Sa-Token 认证模块

### 校验
- ✅ **continew-starter-validation** - Hibernate Validator 校验模块

### 日志
- ✅ **continew-starter-log-aop** - 基于 AOP 的日志记录模块

### 限流与幂等
- ✅ **continew-starter-ratelimiter** - 限流模块
- ✅ **continew-starter-idempotent** - 幂等模块

### 安全
- ✅ **continew-starter-security-crypto** - 字段加解密模块
- ✅ **continew-starter-security-mask** - JSON 数据脱敏模块

### Excel
- ✅ **continew-starter-excel-fastexcel** - FastExcel 模块

### 验证码
- ✅ **continew-starter-captcha-graphic** - 图形验证码模块

### 存储
- ✅ **continew-starter-storage-local** - 本地存储模块

## 项目结构

```
src/
├── main/
│   ├── java/
│   │   └── com/ican/
│   │       ├── IcanApplication.java          # 主启动类
│   │       ├── config/                       # 配置类
│   │       │   └── MyBatisPlusConfig.java
│   │       ├── controller/                   # 控制器
│   │       │   └── TestController.java
│   │       ├── service/                      # 服务层
│   │       ├── mapper/                       # 数据访问层
│   │       └── model/                        # 实体类
│   └── resources/
│       ├── application.yaml                  # 应用配置
│       ├── mapper/                           # MyBatis XML 文件
│       ├── static/                           # 静态资源
│       └── templates/                        # 模板文件
```

## 快速开始

### 1. 环境要求
- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 6.0+

### 2. 修改配置

编辑 `src/main/resources/application.yaml`,修改数据库和 Redis 连接信息:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ican?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
  
  data:
    redis:
      host: localhost
      port: 6379
      password: your_password
```

### 3. 创建数据库

```sql
CREATE DATABASE IF NOT EXISTS ican CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 4. 启动项目

```bash
# 使用 Maven 启动
mvnw spring-boot:run

# 或者打包后运行
mvnw clean package
java -jar target/ican-0.0.1-SNAPSHOT.jar
```

### 5. 访问项目

- 应用地址: http://localhost:8080
- 接口文档: http://localhost:8080/doc.html
- 测试接口: http://localhost:8080/api/test/hello

## 可选模块

根据项目需求,可以继续添加以下模块:

### 数据访问
```xml
<!-- MyBatis Flex -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-data-mf</artifactId>
</dependency>
```

### 缓存
```xml
<!-- JetCache 多级缓存 -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-cache-jetcache</artifactId>
</dependency>

<!-- Spring Cache -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-cache-springcache</artifactId>
</dependency>
```

### 认证
```xml
<!-- JustAuth 第三方登录 -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-auth-justauth</artifactId>
</dependency>
```

### 安全
```xml
<!-- XSS 过滤 -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-security-xss</artifactId>
</dependency>

<!-- 敏感词过滤 -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-security-sensitivewords</artifactId>
</dependency>
```

### 验证码
```xml
<!-- 行为验证码 -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-captcha-behavior</artifactId>
</dependency>
```

### 消息
```xml
<!-- 邮件 -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-messaging-mail</artifactId>
</dependency>

<!-- WebSocket -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-messaging-websocket</artifactId>
</dependency>
```

### Excel
```xml
<!-- POI -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-excel-poi</artifactId>
</dependency>
```

### 日志
```xml
<!-- 基于拦截器实现 -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-log-interceptor</artifactId>
</dependency>
```

### 扩展模块
```xml
<!-- CRUD 扩展(MyBatis Plus) -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-extension-crud-mp</artifactId>
</dependency>

<!-- 数据权限(MyBatis Plus) -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-extension-datapermission-mp</artifactId>
</dependency>

<!-- 租户模块(MyBatis Plus) -->
<dependency>
    <groupId>top.continew.starter</groupId>
    <artifactId>continew-starter-extension-tenant-mp</artifactId>
</dependency>
```

## 参考文档

- [ContiNew Starter 官方文档](https://continew.top/starter/)
- [核心模块文档](https://continew.top/starter/module/core.html)
- [Web 模块文档](https://continew.top/starter/module/web.html)
- [MyBatis Plus 官方文档](https://baomidou.com/)
- [Sa-Token 官方文档](https://sa-token.cc/)
- [Knife4j 官方文档](https://doc.xiaominfo.com/)

## 许可证

Apache License 2.0
