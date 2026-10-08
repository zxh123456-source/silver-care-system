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
2. 首次运行 `初始化本地配置.ps1`，生成已被 Git 忽略的 `.env`，其中数据库、Redis、JWT、MinIO、Milvus、RAG 密钥均独立随机生成。已有 `.env` 时脚本拒绝覆盖，也可以手动参考 `.env.example` 配置。
3. 安装前端、后端和 `源码/beadhouse-ai/README.md` 中列出的依赖。
4. 运行 `启动AI养老院.ps1`。脚本会启动 Docker 基础设施，再启动本机 Spring Boot、FastAPI 和 Vue。
5. 访问 `http://127.0.0.1:8080`。

MySQL 容器首次创建数据卷时会依次导入 `数据库/db_beadhouse.sql` 和 `数据库/ai_care_upgrade.sql`。演示库里的身份信息与联系方式均为虚构测试数据。已有数据卷不会重复初始化；需要重新初始化时应先自行备份，再显式删除对应 Docker volume。

MySQL 容器内部端口为 `3306`，宿主机映射为 `127.0.0.1:3308`，避免与电脑上已有的 MySQL 服务冲突。Spring Boot 默认连接 3308。

## 安全约定

启动脚本会读取根目录 `.env`，然后读取可选 `本地配置.ps1`（后者优先），并检查必填密钥。直接使用 IDE 启动 Java/Python 时，需要把相同环境变量配置到运行配置中；Spring 本身不会读取根目录 `.env`。JWT 至少32字节；Spring 的 `AI_RAG_INTERNAL_TOKEN` 必须与 Python 的 `RAG_INTERNAL_TOKEN` 一致。`RAG_MILVUS_TOKEN` 默认为启动脚本拼接的 `root:<MILVUS_ROOT_PASSWORD>`，代码中不提供默认密码。

Milvus 已启用认证，MinIO 用户和密码同时传给 MinIO 与 Milvus 存储客户端。根 Compose 和旧 `docker-compose.milvus.yml` 入口共用一套配置，要求 Docker Compose 2.23.1 或更新版本（支持内联 configs）。

历史演示数据库的 staff 密码仍是旧 AES 密文，导入后登录需要另外提供原来的 `LEGACY_AES_IV`/`LEGACY_AES_KEY`，通过本地配置安全分享；新账号使用 BCrypt。不要把 JWT 密钥用作 AES 密钥，也不要生成新的 AES 密钥尝试解密旧密码。旧账号成功登录后自动升级 BCrypt。

已有 Docker 数据卷中的 MySQL 和 Milvus 密码不会因修改 `.env` 自动轮换：必须先使用原凭据完成服务内密码变更，再同步客户端配置。不要重新生成 `.env` 或删除数据卷来处理登录失败。JWT 轮换会使旧会话失效，RAG 内部令牌轮换后需同时重启两端服务。

密码找回使用已登记邮箱，通过 `POST /account/sendCode` 申请验证码（请求仅包含 `account`），通过 `PUT /account/forget` 提交 `account`、`code`、`pass`。手机号自助找回暂不可用，请联系管理员。需要配置 `PASSWORD_RESET_EMAIL_ENABLED=true`、`MAIL_HOST`、`MAIL_ADDRESS`、`MAIL_PASSWORD` 才能发信；默认关闭。邮件采用 SMTP 587 + 必须启用 STARTTLS。

验证码有效期5分钟、发送冷却60秒、最多5次错误验证；每15分钟每账号每类请求最多5次、每IP最多30次，Redis原子脚本防止并发绕过和验证码重复消费。成功重置会注销现有登录，新密码须8至64位并使用 BCrypt 保存。数据库写入失败也会消费验证码，需要重新申请。

- 不提交账号、API Key、数据库密码、日志、上传文件和本地运行数据。
- MySQL、Redis、JWT、邮件、RAG 与 Milvus 凭据通过 `.env`、环境变量或本地配置提供。
- 提交前检查 `git status`，确认没有 `.runtime`、`.env`、数据库数据文件和账号文件。

详细功能和验收方法见 [AI护理工作台使用说明.md](./AI护理工作台使用说明.md)。

## 配置回归检查

PowerShell 下运行后端测试时，设置仅用于测试的 `JWT_SECRET`（至少32字节）、`LEGACY_AES_IV`（16字节）和 `LEGACY_AES_KEY`（16字节）再执行 `mvn test`。这些只用于合成测试，不需要生产密钥。Redis 并发测试需要单独的临时 Redis 并设置 `RESET_TEST_REDIS_PORT`。Python 执行 `python -m pytest -q` 覆盖缺失、弱令牌和 Milvus 凭据校验；执行真实 RAG 评测前须提供有效 `RAG_INTERNAL_TOKEN`。
