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

## 每日协作任务

每日助手新增协作任务区。点击“同步当日事项为任务”后才写入任务快照；刷新与 GET 汇总仍只读。认领、转交、复核完成均为显式操作。无分配员工看不到任务；认领人来自登录会话，转交人必须在职并拥有老人及每日助手权限。只有负责人或超级管理员可转交/完成，完成须填写复核结果。

任务按来源去重：护理跟进和服务预约跨日期沿用同一任务，用药按日期/计划/时段独立，健康复核按测量记录独立。同步不会清空负责人或重开已完成任务。并发认领由 MySQL 行锁串行处理，旧版本修改返回409。已完成任务的重复完成请求幂等；原业务事项后来发生变化，需要工作人员重新核对，任务不会自动替代原业务状态。

协作截止时间默认来源时间加24小时，逾期依据当前时间计算；完成后解除逾期展示。该截止时间不是医嘱服药时间。任务列表默认显示未完成，最多200条，保留历史任务便于追踪。此版本支持逾期展示与人工闭环，尚无短信/邮件升级提醒。

已有数据库需执行 `数据库/daily_task_upgrade.sql`；新建 Docker MySQL 数据卷会自动执行。PowerShell 可在项目根目录执行：

```powershell
Get-Content -Raw -Encoding UTF8 数据库/daily_task_upgrade.sql | docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" db_beadhouse'
```

接口：`GET /ai/daily/tasks?state=ACTIVE&date=yyyy-MM-dd`、`POST /ai/daily/tasks/sync?date=yyyy-MM-dd`、`GET /ai/daily/tasks/owners?id=任务编号`、`POST /ai/daily/tasks/claim|transfer|complete`。操作提交 `id` 和 `revision`，转交附 `targetStaffId`，完成附 `note`。日常启动不会自动修改已有数据库结构。

真实 HTTP 并发回归需在可丢弃库执行三份初始化脚本，并设置 `DAILY_HTTP_TEST=true` 及前述 MySQL/Redis 测试变量。此测试会写入测试账号与业务样例，不可使用日常或生产数据库。

## 传统业务老人数据范围

老人分配沿用 `elder_staff_assignment`。超级管理员访问全部数据；普通员工还需同时具备原模块菜单权限和该老人启用中的分配。无分配时列表及导出为空，越权详情/写入返回业务码403。分配撤销后下一次请求立即生效，无需重新登录。

本轮覆盖老人档案分页、详情、修改、删除和导出；预存充值及消费记录；事故新增、详情、修改、删除；外出登记、延期、返回、删除和紧急联系人；护理服务预定与执行扣费；点餐订单与送餐扣费；退住申请及费用审核。公共老人选择器按同一权限过滤。列表在 SQL 内使用登录员工身份过滤，分页总数和导出遵循同一范围；记录操作按数据库里的老人归属校验。新增意向/入住若复用已有老人，也会检查该老人权限。其他历史业务接口需单独审计，不能仅凭本轮覆盖认定全系统都已隔离。

集成验证只使用独立临时数据库：导入演示库与 AI 迁移后设置 `SCOPE_TEST_DB_URL` 和 `SCOPE_TEST_DB_PASSWORD` 可启用真实 MySQL Mapper 回归；设置 `SCOPE_HTTP_REDIS_PORT` 可启用 Spring Boot 随机端口 HTTP 回归（临时 Redis 密码为测试专用 `scope-redis-only`）。HTTP 测试会修改测试库角色、演示密码与业务记录，必须使用可丢弃数据库，不能指向日常或生产库。

## 配置回归检查

PowerShell 下运行后端测试时，设置仅用于测试的 `JWT_SECRET`（至少32字节）、`LEGACY_AES_IV`（16字节）和 `LEGACY_AES_KEY`（16字节）再执行 `mvn test`。这些只用于合成测试，不需要生产密钥。Redis 并发测试需要单独的临时 Redis 并设置 `RESET_TEST_REDIS_PORT`。Python 执行 `python -m pytest -q` 覆盖缺失、弱令牌和 Milvus 凭据校验；执行真实 RAG 评测前须提供有效 `RAG_INTERNAL_TOKEN`。
