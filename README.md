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

1. 准备 JDK 8、Maven 3、Node.js、Python 3.12、PowerShell 7 和 Docker Desktop，不需要单独安装 MySQL 或 Redis。
2. 首次运行 `初始化本地配置.ps1`，生成已被 Git 忽略的 `.env`，其中数据库、Redis、JWT、MinIO、Milvus、RAG 密钥均独立随机生成。已有 `.env` 时脚本拒绝覆盖，也可以手动参考 `.env.example` 配置。
3. 在下面三个目录分别执行依赖安装和构建（终端需能找到 `java`、`mvn`、`node`、`npm`、`python`、`docker`；后端设置 `JAVA_HOME` 指向 JDK 8）：

```powershell
# 源码/beadhouse-backend
mvn package -DskipTests
# 源码/beadhouse-frontend
npm ci
# 源码/beadhouse-ai
python -m venv .venv
.\.venv\Scripts\pip.exe install -r requirements.txt
```

4. 回到根目录执行 `docker compose up -d --wait`，等待首次导入完成。
5. 仅首次导入虚构演示库时执行 `./初始化演示账号.ps1`。脚本仅转换原始演示管理员为随机 BCrypt 密码，登录凭据写入被 Git 忽略的 `.runtime/demo-accounts.txt`；重复执行不会覆盖已改过的密码。不需要旧 AES 密钥。其他旧演示员工不会被重置，仍需历史 AES 配置；也可由管理员新增演示员工并分配角色和老人。
6. 运行 `./启动AI养老院.ps1`。脚本会启动 Docker 基础设施，再启动本机 Spring Boot、FastAPI 和 Vue，访问 `http://127.0.0.1:8080`。

MySQL 容器首次创建数据卷时会依次导入演示库、AI、每日任务和告警四份 SQL。演示库里的身份信息与联系方式均为虚构测试数据。已有数据卷不会重复初始化：先备份，再执行根目录 `./升级数据库.ps1`，按顺序应用三份增量迁移。该脚本不会导入会覆盖历史数据的 `db_beadhouse.sql`。迁移可重复执行，但 MySQL DDL 不能整体回滚，失败时需依据备份和报错处理。不要通过删除已有数据卷来升级。

MySQL 容器内部端口为 `3306`，宿主机映射为 `127.0.0.1:3308`，避免与电脑上已有的 MySQL 服务冲突。Spring Boot 默认连接 3308。

MinIO 的旧预构建镜像已无法拉取，Compose 现在按 [官方源码](https://github.com/minio/minio/tree/16f8cf1c52f0a77eeb8f7565aaf7f7df12454583) 构建相同的 `RELEASE.2024-12-18T13-15-44Z`，源码归档校验 SHA256，基础镜像固定 digest。首次需要联网下载 Go 依赖并编译数分钟，后续复用 Docker 构建缓存；无需本机安装 Go。构建入口为 `docker/minio/Dockerfile`，许可证随镜像保留。已有 MinIO 数据卷继续使用。

## 安全约定

启动脚本会读取根目录 `.env`，然后读取可选 `本地配置.ps1`（后者优先），并检查必填密钥。直接使用 IDE 启动 Java/Python 时，需要把相同环境变量配置到运行配置中；Spring 本身不会读取根目录 `.env`。JWT 至少32字节；Spring 的 `AI_RAG_INTERNAL_TOKEN` 必须与 Python 的 `RAG_INTERNAL_TOKEN` 一致。`RAG_MILVUS_TOKEN` 默认为启动脚本拼接的 `root:<MILVUS_ROOT_PASSWORD>`，代码中不提供默认密码。

Milvus 已启用认证，MinIO 用户和密码同时传给 MinIO 与 Milvus 存储客户端。根 Compose 和旧 `docker-compose.milvus.yml` 入口共用一套配置，要求 Docker Compose 2.23.1 或更新版本（支持内联 configs）。

已有业务库的旧 AES 账号仍需原 `LEGACY_AES_IV`/`LEGACY_AES_KEY` 才能登录，通过本地配置安全分享。不要把 JWT 密钥用作 AES 密钥，也不要生成新的 AES 密钥尝试解密旧密码。旧账号成功登录后自动升级 BCrypt。演示管理员初始化工具仅适用于原始虚构种子账号，不能用于恢复其他业务库的密码。

已有 Docker 数据卷中的 MySQL 和 Milvus 密码不会因修改 `.env` 自动轮换：必须先使用原凭据完成服务内密码变更，再同步客户端配置。不要重新生成 `.env` 或删除数据卷来处理登录失败。JWT 轮换会使旧会话失效，RAG 内部令牌轮换后需同时重启两端服务。

登录采用单账号单活跃会话：每次签发带随机 `jti` 的 JWT，私有请求必须同时匹配 Redis 中当前令牌、JWT 签发方/主题，以及数据库中的在职状态和角色。新登录会替代旧登录，退出后旧令牌不会随下次登录恢复。员工资料修改成功会清除其登录缓存；即使角色更新未成功清理缓存，服务端也不会继续使用旧角色。公开登录和找回接口允许携带旧令牌访问，前端不再为这些接口附加令牌。

普通员工管理接口与既有列表/角色选择器保持一致：不能创建或分配超级管理员角色，也不能通过编号读取、编辑或离职任何超级管理员。服务端按角色校验，不依赖隐藏按钮或固定账号编号。普通员工的管理操作保留，账号登录和密码找回流程独立处理。

上传接口要求有效登录。客户端文件名不能包含目录、盘符或控制字符，最长225字符；服务端使用随机 UUID 文件名，原名仅保存为附件元数据。图片仅接受真实 JPG/PNG，最大2MiB、单边8192像素且总像素不超过1600万；普通附件最大20MiB，读取流时再次检查大小。暂存文件位于未公开映射的 `.staging` 目录，上传或元数据保存失败会清理本次文件，存储故障返回503。上传附件强制下载，上传资源设置 `nosniff` 和沙箱 CSP。资源读取仍沿用现有公开 URL，私有附件的逐文件访问授权需单独建设。

图片组件在服务端确认成功后才替换原图，伪装图片或网络失败保留原图；删除图片会清空表单中的 URL。业务校验失败沿用现有 HTTP 200 + 业务码400的约定，页面检查业务码后显示错误。

上传请求统一经过前端 `/api` 代理，新文件 URL 默认使用当前站点的 `/api/upload/...`；需要独立资源域名时设置 `FILE_UPLOAD_HEAD`。已有数据库中保存的历史绝对 URL 不自动改写。

认证失败返回 HTTP/业务码401，权限不足返回403；会话存储或员工状态查询不可用返回503。前端仅对失效私有会话清理登录状态，403保留登录；并发401只触发一次退出，较早请求的失败不会注销后来新建的会话。其他旧业务码保留既有传输约定。SQL参数默认不输出到日志；仅本地排障时可显式设置 `MYBATIS_LOG_IMPL=org.apache.ibatis.logging.stdout.StdOutImpl`。

密码找回使用已登记邮箱，通过 `POST /account/sendCode` 申请验证码（请求仅包含 `account`），通过 `PUT /account/forget` 提交 `account`、`code`、`pass`。手机号自助找回暂不可用，请联系管理员。需要配置 `PASSWORD_RESET_EMAIL_ENABLED=true`、`MAIL_HOST`、`MAIL_ADDRESS`、`MAIL_PASSWORD` 才能发信；默认关闭。邮件采用 SMTP 587 + 必须启用 STARTTLS。

验证码有效期5分钟、发送冷却60秒、最多5次错误验证；每15分钟每账号每类请求最多5次、每IP最多30次，Redis原子脚本防止并发绕过和验证码重复消费。成功重置会注销现有登录，新密码须8至64位并使用 BCrypt 保存。数据库写入失败也会消费验证码，需要重新申请。

所有有效邮箱均执行相同的限流、验证码状态和60秒冷却逻辑，首次返回统一文案，冷却期内重复请求统一拒绝；仅已登记邮箱实际发信。单次 SMTP 投递失败时作废验证码、保留冷却并返回通用响应，日志只记录异常类别，避免通过错误响应探测邮箱是否登记。邮件服务整体未配置时，所有邮箱均返回服务不可用。

- 不提交账号、API Key、数据库密码、日志、上传文件和本地运行数据。
- MySQL、Redis、JWT、邮件、RAG 与 Milvus 凭据通过 `.env`、环境变量或本地配置提供。
- 提交前检查 `git status`，确认没有 `.runtime`、`.env`、数据库数据文件和账号文件。

详细功能和验收方法见 [AI护理工作台使用说明.md](./AI护理工作台使用说明.md)。
本轮实际检查结果和未覆盖范围见 [协作验收记录.md](./协作验收记录.md)。

RAG 文档缓存已增加磁盘失败保护、损坏文件校验和单进程并发写入保护；向量计算失败会保留旧 Milvus 索引。跨服务失败继续由现有 MySQL 同步队列重试，详见 [RAG 服务说明](./源码/beadhouse-ai/README.md)。

## 健康与用药告警

新增健康测量时，在同一事务中比较当前填写指标与最近历史记录（最多90条），触发已有变化阈值后保存 HEALTH_CHANGE 告警；只填写体重不会重新触发旧体温变化。此功能只描述数据变化，不作诊断。用药登记 SKIPPED 自动保存 MEDICATION_SKIPPED 告警，改为 DONE 后自动解除对应用药告警。

每日助手新增告警区。历史用药核对需显式选择过去31天内的日期并点击核对；没有执行登记的计划/时段保存 MEDICATION_UNRECORDED，表示“缺少记录待核对”，不能认定老人实际漏服。当日或未来日期不允许扫描为漏登记告警。当前没有后台定时扫描或短信/邮件通知。

告警按测量记录或用药日期/计划/时段去重。相同证据重复核对保留确认/解除状态，证据变化才重新打开告警。告警同时关联协作任务；若旧任务已完成，新证据使用新的任务键，保留旧任务结果。解除告警不会修改测量、用药执行、余额或自动完成协作任务。

人工流程为待确认 → 已确认 → 已解除，解除需要1至500字依据，仅确认人或超级管理员可解除。版本冲突返回409；所有查询和操作沿用老人数据范围。服务对原记录与告警实行事务提交；告警写入失败时，原测量/登记也回滚，避免丢失提醒。

已有环境需先执行 `数据库/care_alert_upgrade.sql`（并确保每日任务迁移已执行）。新 Docker 数据卷会自动执行四份初始化脚本。PowerShell：

```powershell
Get-Content -Raw -Encoding UTF8 数据库/care_alert_upgrade.sql | docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" db_beadhouse'
```

接口：`GET /ai/daily/alerts?state=ACTIVE`、`POST /ai/daily/alerts/scan?date=yyyy-MM-dd`、`POST /ai/daily/alerts/ack`、`POST /ai/daily/alerts/resolve`。确认/解除提交 `id`、`revision`，解除附 `note`。返回 `sourceId`、`alertKey` 与 `taskKey` 供追踪原记录及关联任务。

真实集成回归额外设置 `ALERT_HTTP_TEST=true`，仅允许指向可丢弃测试数据库；测试会模拟告警表不可用，以验证原业务回滚。

## 每日协作任务

每日助手新增协作任务区。点击“全量同步所选日期事项”后才写入任务快照；刷新与 GET 汇总仍只读。认领、转交、复核完成均为显式操作。无分配员工看不到任务；认领人来自登录会话，转交人必须在职并拥有老人及每日助手权限。只有负责人或超级管理员可转交/完成，完成须填写复核结果。

概览最多展示200条，服务预约来源最多展示50条；任一展示来源被截断时均标记 `truncated=true`。全量同步独立读取护理、用药、健康与预约来源，不再使用概览的截断列表：按主键游标每批读取100条，健康来源按老人编号分页去重，用药执行按当批计划查询，任务每批最多写入100条。页面展示上限不影响同步范围；返回的数量表示处理的来源事项数，包含已有任务的快照更新，不等于新增任务数。

同步采用一次 REPEATABLE READ 事务，任何后续批次失败都会回滚本次已写入的批次；基于事务快照读取，快照之后新增的来源由下次同步处理。相同来源按唯一键去重，重试不清空负责人或已完成结果。服务端事务超时120秒，页面等待上限150秒，期间显示同步等待状态，完成后显示处理数量；当前不是后台作业，不提供百分比进度或断点续传。若网络断开导致结果未确认，应先刷新核对再重试。未来日期仍返回业务码400。

任务按来源去重：护理跟进和服务预约跨日期沿用同一任务，用药按日期/计划/时段独立，健康复核按测量记录独立。同步不会清空负责人或重开已完成任务。并发认领由 MySQL 行锁串行处理，旧版本修改返回409。已完成任务的重复完成请求幂等；原业务事项后来发生变化，需要工作人员重新核对，任务不会自动替代原业务状态。

协作截止时间默认来源时间加24小时，逾期依据当前时间计算；完成后解除逾期展示。该截止时间不是医嘱服药时间。任务列表默认显示未完成，最多200条，保留历史任务便于追踪。此版本支持逾期展示与人工闭环，尚无短信/邮件升级提醒。

已有数据库需执行 `数据库/daily_task_upgrade.sql`；新建 Docker MySQL 数据卷会自动执行。PowerShell 可在项目根目录执行：

```powershell
Get-Content -Raw -Encoding UTF8 数据库/daily_task_upgrade.sql | docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" db_beadhouse'
```

接口：`GET /ai/daily/tasks?state=ACTIVE&date=yyyy-MM-dd`、`POST /ai/daily/tasks/sync?date=yyyy-MM-dd`、`GET /ai/daily/tasks/owners?id=任务编号`、`POST /ai/daily/tasks/claim|transfer|complete`。操作提交 `id` 和 `revision`，转交附 `targetStaffId`，完成附 `note`。日常启动不会自动修改已有数据库结构。

真实 HTTP 并发回归需在可丢弃库执行四份初始化脚本，并设置 `DAILY_HTTP_TEST=true` 及前述 MySQL/Redis 测试变量。此测试会写入测试账号与业务样例，不可使用日常或生产数据库。

## 传统业务老人数据范围

老人分配沿用 `elder_staff_assignment`。超级管理员访问全部数据；普通员工还需同时具备原模块菜单权限和该老人启用中的分配。无分配时列表及导出为空，越权详情/写入返回业务码403。分配撤销后下一次请求立即生效，无需重新登录。

本轮覆盖老人档案分页、详情、修改、删除和导出；预存充值及消费记录；事故新增、详情、修改、删除；外出登记、延期、返回、删除和紧急联系人；护理服务预定与执行扣费；点餐订单与送餐扣费；退住申请及费用审核。公共老人选择器按同一权限过滤。列表在 SQL 内使用登录员工身份过滤，分页总数和导出遵循同一范围；记录操作按数据库里的老人归属校验。新增意向/入住若复用已有老人，也会检查该老人权限。其他历史业务接口需单独审计，不能仅凭本轮覆盖认定全系统都已隔离。

集成验证只使用独立临时数据库：导入演示库与 AI 迁移后设置 `SCOPE_TEST_DB_URL` 和 `SCOPE_TEST_DB_PASSWORD` 可启用真实 MySQL Mapper 回归；设置 `SCOPE_HTTP_REDIS_PORT` 可启用 Spring Boot 随机端口 HTTP 回归（临时 Redis 密码为测试专用 `scope-redis-only`）。HTTP 测试会修改测试库角色、演示密码与业务记录，必须使用可丢弃数据库，不能指向日常或生产库。

## 配置回归检查

GitHub Actions 的 `CI` 工作流在 main 推送、面向 main 的 PR（含草稿）及手动触发时运行：后端 JDK 8 完整构建和独立 MySQL/Redis 集成测试、前端 Node 24 全新 `npm ci` 与生产构建、Python 3.12 全量服务依赖安装与 pytest，以及 Chromium 浏览器端到端回归。关键的八组后端集成测试（含真实认证会话生命周期及上传/下载响应）若未执行或被跳过，CI 会失败，避免仅单元测试通过。测试报告作为 artifact 保留7天；普通 ESLint 警告不会导致构建失败。

CI 使用临时库和合成测试凭据，不读取本地 `.env`，不需要配置 GitHub Secrets，也不调用真实邮件或外部模型。浏览器 job 使用前两组检查产出的同一份 JAR/dist，在全新的 MySQL 和专用 Redis 上通过真实页面验证登录、老人分配与撤销、健康测量/告警、超过200条的全量同步、任务认领/转交/完成、用药登记及告警解除，不模拟业务接口响应。失败时保留截图、trace、后端日志和 JUnit 报告。OCR 测试仍按现有环境条件执行；当前测试使用 Windows Tesseract 路径，在 Linux runner 中会跳过。CI 不代替 BGE 质量评测、其他浏览器/移动端或完整本机一键启动验证。

Python job 还会启动隔离的认证 Milvus/MinIO/etcd，验证真实向量索引在计算失败、磁盘失败、重试和删除后的状态；若这项测试缺失或跳过，CI 会失败。测试集合和数据卷在结束时清理。

浏览器回归本地复现（先按前文构建 JAR 和前端 dist，`JAVA_HOME` 指向 JDK 8）：

```powershell
python -m venv .runtime/e2e-venv
./.runtime/e2e-venv/Scripts/pip.exe install -r .github/e2e/requirements.txt
./.runtime/e2e-venv/Scripts/python.exe -m playwright install chromium
docker run -d --name silvercare-e2e-local -p 127.0.0.1:13318:3306 -e MYSQL_ROOT_PASSWORD=scope-test-only -e MYSQL_DATABASE=db_beadhouse --tmpfs /var/lib/mysql mysql:8.0.40
$env:E2E_MYSQL_CONTAINER='silvercare-e2e-local'
./.runtime/e2e-venv/Scripts/python.exe .github/e2e/run.py --disposable-test
docker rm -f silvercare-e2e-local
```

该命令会重置指定测试容器中的 `db_beadhouse`，只允许在可丢弃库中运行。脚本要求显式 `--disposable-test`，本地容器名必须以 `silvercare-e2e-` 开头；固定测试端口为13318、16390和19001，需保持空闲。每次生成临时登录密码，结束时清理自己启动的 Redis 和后端进程。证据保存在 Git 忽略的 `.runtime/e2e/<运行编号>/`，仅使用虚构演示数据。

PowerShell 下运行后端测试时，设置仅用于测试的 `JWT_SECRET`（至少32字节）、`LEGACY_AES_IV`（16字节）和 `LEGACY_AES_KEY`（16字节）再执行 `mvn test`。这些只用于合成测试，不需要生产密钥。Redis 并发测试需要单独的临时 Redis 并设置 `RESET_TEST_REDIS_PORT`。Python 执行 `python -m pytest -q` 覆盖缺失、弱令牌和 Milvus 凭据校验；执行真实 RAG 评测前须提供有效 `RAG_INTERNAL_TOKEN`。
