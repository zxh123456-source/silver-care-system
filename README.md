# 银龄智慧康护——养老院管理系统

面向养老院日常运营的管理系统，包含 Vue 3 前端、Spring Boot 后端、FastAPI RAG 服务、MySQL、Redis 与 Milvus。项目已加入护理记录辅助、制度知识库、健康趋势、用药核对、每日护理助手、老人级数据权限、AI 审计和中文 OCR。

## 项目结构

- `源码/beadhouse-frontend`：Vue 3 + TypeScript 前端
- `源码/beadhouse-backend`：Spring Boot + MyBatis Plus 后端
- `源码/beadhouse-ai`：FastAPI + BGE + Milvus 混合检索服务
- `数据库/ai_care_upgrade.sql`：AI 功能增量迁移
- `AI护理工作台使用说明.md`：AI 功能、接口和验收说明
- `启动AI养老院.ps1`、`停止AI养老院.ps1`：Windows 本地启动和停止脚本

## 本地准备

1. 准备 JDK 8、Node.js、Python 3.12、MySQL、Redis 和 Docker Desktop。
2. 创建 `db_beadhouse` 数据库。含业务演示数据的原始数据库备份涉及个人信息，不进入 Git 仓库，请由项目负责人通过安全渠道提供脱敏种子数据。
3. 执行 `数据库/ai_care_upgrade.sql`。
4. 安装前端、后端和 `源码/beadhouse-ai/README.md` 中列出的依赖。
5. 运行 `启动AI养老院.ps1`，访问 `http://127.0.0.1:8080`。

## 安全约定

- 不提交账号、API Key、数据库密码、日志、上传文件和本地运行数据。
- MySQL、Redis、JWT、邮件、RAG 与 Milvus 凭据通过环境变量或本地配置提供。
- 提交前检查 `git status`，确认没有 `.runtime`、`.env`、数据库数据文件和账号文件。

详细功能和验收方法见 [AI护理工作台使用说明.md](./AI护理工作台使用说明.md)。
