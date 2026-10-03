# 银龄智慧康护——养老院管理系统

面向养老院日常运营的管理系统，包含 Vue 3 前端、Spring Boot 后端和 FastAPI RAG 服务。MySQL、Redis、Milvus、etcd 与 MinIO 统一由 Docker Compose 运行，业务代码仍在本机运行。

## 项目结构

- `源码/beadhouse-frontend`：Vue 3 + TypeScript 前端
- `源码/beadhouse-backend`：Spring Boot + MyBatis Plus 后端
- `源码/beadhouse-ai`：FastAPI + BGE + Milvus 混合检索服务
- `数据库/ai_care_upgrade.sql`：AI 功能增量迁移
- `AI护理工作台使用说明.md`：AI 功能、接口和验收说明
- `启动AI养老院.ps1`、`停止AI养老院.ps1`：Windows 本地启动和停止脚本

## 本地准备

1. 准备 JDK 8、Node.js、Python 3.12 和 Docker Desktop，不需要单独安装 MySQL 或 Redis。
2. 复制 `.env.example` 为 `.env`，按需修改本地密码和内部令牌。
3. 安装前端、后端和 `源码/beadhouse-ai/README.md` 中列出的依赖。
4. 运行 `启动AI养老院.ps1`。脚本会启动 Docker 基础设施，再启动本机 Spring Boot、FastAPI 和 Vue。
5. 访问 `http://127.0.0.1:8080`。

MySQL 容器首次创建数据卷时会依次导入 `数据库/db_beadhouse.sql` 和 `数据库/ai_care_upgrade.sql`。演示库里的身份信息与联系方式均为虚构测试数据。已有数据卷不会重复初始化；需要重新初始化时应先自行备份，再显式删除对应 Docker volume。

MySQL 容器内部端口为 `3306`，宿主机映射为 `127.0.0.1:3308`，避免与电脑上已有的 MySQL 服务冲突。Spring Boot 默认连接 3308。

## 安全约定

- 不提交账号、API Key、数据库密码、日志、上传文件和本地运行数据。
- MySQL、Redis、JWT、邮件、RAG 与 Milvus 凭据通过 `.env`、环境变量或本地配置提供。
- 提交前检查 `git status`，确认没有 `.runtime`、`.env`、数据库数据文件和账号文件。

详细功能和验收方法见 [AI护理工作台使用说明.md](./AI护理工作台使用说明.md)。
