# 银龄智慧康护——养老院管理系统

面向养老院日常运营的管理系统，包含 Vue 3 前端、Spring Boot 后端和 FastAPI RAG 服务。MySQL、Redis、Milvus、etcd 与 MinIO 统一由 Docker Compose 运行，业务代码仍在本机运行。

## 项目结构

- `源码/beadhouse-frontend`：Vue 3 + TypeScript + Vite 前端
- `源码/beadhouse-backend`：Spring Boot + MyBatis Plus 后端
- `源码/beadhouse-ai`：FastAPI + BGE + Milvus 混合检索服务
- `数据库/ai_care_upgrade.sql`：AI 功能增量迁移
- `AI护理工作台使用说明.md`：AI 功能、接口和验收说明
- `启动AI养老院.ps1`、`停止AI养老院.ps1`、`重启AI养老院.ps1`、`查看运行状态.ps1`：Windows 本地生命周期入口

## 本地准备

1. 准备 JDK 8、Maven 3、Node.js 24、Python 3.12、PowerShell 7 和 Docker Desktop（Linux 容器模式）。终端须能找到 `java`、`mvn.cmd`、`node`、`npm.cmd`、`py` 或配置下述工具路径，以及 `docker.exe`；不需要本机安装 MySQL 或 Redis。
2. 在项目根目录的 PowerShell 7 中，首次虚构演示环境执行：

```powershell
./启动AI养老院.ps1 -Prepare -InitializeDemo -AcknowledgeDemoOnly
```

`-Prepare` 仅在缺失时生成随机 `.env`，然后构建后端、执行 `npm ci`、创建 Python 3.12 虚拟环境并安装完整 RAG 依赖。已有 `.env` 不会覆盖；准备日志写入 `.runtime/logs/<Compose项目>/prepare-*.log`。已有依赖的日常启动不加 `-Prepare`；准备时应用必须已停止。首次需要网络下载软件包、镜像和 BGE 模型，后续可复用缓存。

`-InitializeDemo -AcknowledgeDemoOnly` 仅用于未改动的原始虚构演示管理员，转换为随机 BCrypt 密码；凭据保存于已被 Git 忽略的 `.runtime/demo-accounts.txt`，不在终端打印。重复初始化不覆盖已修改密码。已有业务库不要加这两个参数。其他旧演示员工仍需历史 AES 配置，或由管理员新增员工并分配角色和老人。

3. 启动成功后访问 `http://127.0.0.1:8080`。后端默认 `9001`，RAG `8001`。日常操作：

```powershell
./启动AI养老院.ps1
./查看运行状态.ps1
./重启AI养老院.ps1
./停止AI养老院.ps1
```

脚本验证容器健康、数据库迁移版本、真实 BGE/Milvus 诊断、后端鉴权和实际前端代理。未知端口占用会失败；重复启动验证原实例，不重复启动应用。部分失败只回滚本次新启动的服务，保留数据卷与原来运行的资源。停止依赖 `.runtime/lifecycle-<Compose项目>.json` 中的项目路径、进程创建时间、命令和容器标签，不按端口杀进程；配置缺失时仍可停止有有效状态的实例。手动运行或其他目录的同名 Compose 项目不会自动接管。

如需与队友其他服务共存，可在 `.env` 中设置 `COMPOSE_PROJECT_NAME` 和 `MYSQL_PORT`、`REDIS_PORT`、`MILVUS_PORT`、`MILVUS_HEALTH_PORT`、`RAG_PORT`、`BACKEND_PORT`、`FRONTEND_PORT`（默认依次为 `silver-care-system`、3308、6379、19530、9091、8001、9001、8080），或向启动入口传 `-ComposeProject`、`-MysqlPort` 等同名参数。重启复用该项目上次保存的端口；停止/状态入口指定同一个 `-ComposeProject`。修改运行中配置前先停止；项目内上传文件和 RAG 本地状态保存在 `.runtime`，停止不会删除。

工具不在 PATH 时可设置 `JAVA_HOME`，或通过 `SILVERCARE_JAVA`、`SILVERCARE_NODE`、`SILVERCARE_MAVEN`、`SILVERCARE_PYTHON` 指定可执行文件绝对路径；Python 路径用于首次创建虚拟环境。不要提交 `.env`、`本地配置.ps1`、`.runtime` 或演示凭据。

MySQL 新数据卷通过统一执行器导入演示库，再按顺序应用 `数据库/migrations.tsv` 中的全部增量迁移；演示身份信息与联系方式均为虚构测试数据。已有库使用 `./升级数据库.ps1`，绝不重新导入演示 dump。首次安装、旧库升级、CI和浏览器测试共用版本清单；MySQL健康检查与本机启动脚本都检查版本完整性，普通启动不会自动升级已有库。升级操作见下节，不要删除数据卷升级。

床位迁移会拒绝重复有效身份证、重复占床、床位与老人状态不一致、多个有效预订或待审退住申请，并输出冲突记录编号。应核对真实业务后修正冲突再重试，不能任意删除历史记录。预订保留有效、已退款、已转入住、已到期四种状态和原床位快照；无法核实的旧退款床位保留为空。入住可使用本人未到期预订床位，退住审核期间禁止换床；正式退住必须通过申请与审核，不能删除合同释放床位。预订退款仍仅登记退款状态，不自动修改账户余额。

MySQL 容器内部端口为 `3306`，宿主机映射为 `127.0.0.1:3308`，避免与电脑上已有的 MySQL 服务冲突。Spring Boot 默认连接 3308。

MinIO 的旧预构建镜像已无法拉取，Compose 现在按 [官方源码](https://github.com/minio/minio/tree/16f8cf1c52f0a77eeb8f7565aaf7f7df12454583) 构建相同的 `RELEASE.2024-12-18T13-15-44Z`，源码归档校验 SHA256，基础镜像固定 digest。首次需要联网下载 Go 依赖并编译数分钟，后续复用 Docker 构建缓存；无需本机安装 Go。构建入口为 `docker/minio/Dockerfile`，许可证随镜像保留。已有 MinIO 数据卷继续使用。

## 数据库版本与备份恢复

先停止应用、定时任务及其他数据库写入，保持 MySQL 运行。备份工具使用容器内凭据，不通过命令行传递密码。Windows示例（Python 3.10+，默认寻找项目虚拟环境或PATH，可用 `-PythonPath` 指定解释器）：

```powershell
$backupResult = ./备份数据库.ps1 -WritersStopped | ConvertFrom-Json
./恢复演练.ps1 -Backup $backupResult.backup
./升级数据库.ps1
./检查数据库.ps1
```

默认备份位于 `.runtime/backups/` 下的新目录，不覆盖旧备份；也可指定 `-Output <新目录>`。备份与检查/升级可用 `-Container <源容器>` 指定已有 MySQL，默认选择本项目 Compose 的 mysql 服务。旧库没有版本记录时健康检查会显示未就绪，此时仍可备份和升级；如需启动已停止的旧容器，用 `docker compose up -d mysql`，待MySQL服务能连接后再备份，不用 `--wait` 等待尚未实施的迁移。

`检查数据库.ps1` 只读，要求所有版本成功且与清单一致。`schema_migration` 保存版本、SHA256、状态、尝试次数和时间；校验和按UTF-8内容的LF换行计算。已成功SQL不得原地修改，后续变更新增有序迁移；未知版本、版本缺口或历史校验和漂移均拒绝升级。初始化拒绝非空数据库，来源记录区分新演示库和旧库接管。

迁移失败保留 `FAILED`；进程异常中断后，下次升级会把残留 `RUNNING` 记录为 `INTERRUPTED` 并停止。根据诊断修复实际原因后，使用 `./升级数据库.ps1 -RetryFailed` 显式重试同一校验和且允许重试的版本。DDL不能整体回滚，不提供强制标成功或自动跳过。版本冲突也允许先取备份，备份会标记 `NOT_READY`；恢复到相同状态不等于版本已可用于启动应用。

备份包含表结构、数据、约束、存储过程、触发器及事件，并附字节数和SHA256清单；不包括上传文件目录、Redis或Milvus。需另行保存附件磁盘，并按 `filesave` 配置核对恢复路径。备份执行前后核对表摘要和结构，迁移与备份通过同一MySQL会话锁互斥；`-WritersStopped` 表示已停止不参与该锁的业务写入。

恢复演练先校验文件，再创建无宿主端口、无宿主数据卷挂载的独立 MySQL 8.0.40 容器，关闭事件调度，逐表比较行数和校验值、迁移记录、约束及存储对象。它不接受日常库作为恢复目标，不改动来源库。成功后清理自建容器；失败日志在 `.runtime/database-drills/`，需要保留故障容器时显式使用 `-KeepOnFailure`。这是恢复验证工具，正式切换数据库由维护者依据已验证备份和部署配置执行。

可执行验收：`python -m unittest discover -s tools -p test_database.py`，以及 `python .github/database/check.py --disposable-test`；后者只创建并操作自有隔离容器，覆盖新旧库、重复、漂移、争锁、故障/中断、备份恢复与篡改拒绝。

## 安全约定

启动脚本会读取根目录 `.env`，然后读取可选 `本地配置.ps1`（后者优先），并检查必填密钥。直接使用 IDE 启动 Java/Python 时，需要把相同环境变量配置到运行配置中；Spring 本身不会读取根目录 `.env`。JWT 至少32字节；Spring 的 `AI_RAG_INTERNAL_TOKEN` 必须与 Python 的 `RAG_INTERNAL_TOKEN` 一致。`RAG_MILVUS_TOKEN` 默认为启动脚本拼接的 `root:<MILVUS_ROOT_PASSWORD>`，代码中不提供默认密码。

Milvus 已启用认证，MinIO 用户和密码同时传给 MinIO 与 Milvus 存储客户端。根 Compose 和旧 `docker-compose.milvus.yml` 入口共用一套配置，要求 Docker Compose 2.23.1 或更新版本（支持内联 configs）。

已有业务库的旧 AES 账号仍需原 `LEGACY_AES_IV`/`LEGACY_AES_KEY` 才能登录，通过本地配置安全分享。不要把 JWT 密钥用作 AES 密钥，也不要生成新的 AES 密钥尝试解密旧密码。旧账号成功登录后自动升级 BCrypt。演示管理员初始化工具仅适用于原始虚构种子账号，不能用于恢复其他业务库的密码。

已有 Docker 数据卷中的 MySQL 和 Milvus 密码不会因修改 `.env` 自动轮换：必须先使用原凭据完成服务内密码变更，再同步客户端配置。不要重新生成 `.env` 或删除数据卷来处理登录失败。JWT 轮换会使旧会话失效，RAG 内部令牌轮换后需同时重启两端服务。

登录采用单账号单活跃会话：每次签发带随机 `jti` 的 JWT，私有请求必须同时匹配 Redis 中当前令牌、JWT 签发方/主题，以及数据库中的在职状态和角色。新登录会替代旧登录，退出后旧令牌不会随下次登录恢复。员工资料修改成功会清除其登录缓存；即使角色更新未成功清理缓存，服务端也不会继续使用旧角色。公开登录和找回接口允许携带旧令牌访问，前端不再为这些接口附加令牌。

普通员工管理接口与既有列表/角色选择器保持一致：不能创建或分配超级管理员角色，也不能通过编号读取、编辑或离职任何超级管理员。服务端按角色校验，不依赖隐藏按钮或固定账号编号。普通员工的管理操作保留，账号登录和密码找回流程独立处理。

上传接口要求有效登录。客户端文件名不能包含目录、盘符或控制字符，最长225字符；服务端使用随机 UUID 文件名，原名仅保存为附件元数据。图片仅接受真实 JPG/PNG，最大2MiB、单边8192像素且总像素不超过1600万；普通附件最大20MiB，读取流时再次检查大小。暂存文件位于 `.staging` 目录，上传或元数据写入失败、确认事务回滚时清理本次文件，存储故障返回503；提交结果不确定时保留可能已提交的文件，避免误删。

新上传返回 `/api/file/{id}/content`，读取必须携带当前登录令牌。草稿仅上传人或管理员可读；绑定后按事故所属老人、员工头像权限、活动全部参与老人及菜单权限授权。撤销老人或菜单权限后下一次读取立即拒绝，上传人身份不能绕过。附件不能跨业务属主复用，替换或删除保留历史绑定；管理员可审计历史、未知和冲突附件，但磁盘路径仍必须位于配置存储根内。图片与附件设置 `private, no-store`、`nosniff` 和沙箱CSP，普通附件强制下载。旧 `/upload/**`、`/download/**` 的磁盘映射已移除。

图片组件在服务端确认成功后才替换原图，伪装图片或网络失败保留原图；删除图片会清空表单中的 URL。业务校验失败沿用现有 HTTP 200 + 业务码400的约定，页面检查业务码后显示错误。

上传与读取统一经过前端 `/api` 代理，前端使用认证请求获取Blob预览，保存表单仍传资源URL；令牌不会发往外域。`FILE_UPLOAD_HEAD` 等旧配置不再生成新的公共文件链接。Excel导出通过认证接口直接返回XLSX字节，保留老人范围，不写公开下载目录。

已有库先运行 `./升级数据库.ps1`，其中包含附件权限迁移。迁移只按数据库内URL精确匹配回填引用，不改旧业务URL、不猜测文件名。唯一归属标记BOUND，多属主或同URL多元数据标记CONFLICT，未匹配附件保留LEGACY，均不会伪装成新草稿。SQL输出回填/冲突/未匹配报告；管理员可调用 `GET /file/diagnostics?afterId=0&limit=100` 分页检查磁盘状态，响应不暴露路径或旧URL。关联回填不代表磁盘文件存在；旧文件缺失或在配置目录之外时，应核对备份并重新上传。前端对不可预览的历史图片显示占位并保留原字段，允许修改其他资料；未知URL不能绑定到新业务。

认证失败返回 HTTP/业务码401，权限不足返回403；会话存储或员工状态查询不可用返回503。前端仅对失效私有会话清理登录状态，403保留登录；并发401只触发一次退出，较早请求的失败不会注销后来新建的会话。其他旧业务码保留既有传输约定。SQL参数默认不输出到日志；仅本地排障时可显式设置 `MYBATIS_LOG_IMPL=org.apache.ibatis.logging.stdout.StdOutImpl`。

密码找回使用已登记邮箱，通过 `POST /account/sendCode` 申请验证码（请求仅包含 `account`），通过 `PUT /account/forget` 提交 `account`、`code`、`pass`。手机号自助找回暂不可用，请联系管理员。需要配置 `PASSWORD_RESET_EMAIL_ENABLED=true`、`MAIL_HOST`、`MAIL_ADDRESS`、`MAIL_PASSWORD` 才能发信；默认关闭。邮件采用 SMTP 587 + 必须启用 STARTTLS。

验证码有效期5分钟、发送冷却60秒、最多5次错误验证；每15分钟每账号每类请求最多5次、每IP最多30次，Redis原子脚本防止并发绕过和验证码重复消费。成功重置会注销现有登录，新密码须8至64位并使用 BCrypt 保存。数据库写入失败也会消费验证码，需要重新申请。

登录后“修改密码”使用实际输入的原密码和新密码，8至64位且UTF-8不超过72字节，首尾空格按真实口令保存。失败保留输入并允许重试；成功撤销原会话并返回登录页。换账号后旧弹窗不能提交。老人编辑及选择弹窗、老人读取API也绑定原会话，迟到响应不能覆盖新搜索或重开的表单；表格显示加载/错误及重试入口。

所有有效邮箱均执行相同的限流、验证码状态和60秒冷却逻辑，首次返回统一文案，冷却期内重复请求统一拒绝；仅已登记邮箱实际发信。单次 SMTP 投递失败时作废验证码、保留冷却并返回通用响应，日志只记录异常类别，避免通过错误响应探测邮箱是否登记。邮件服务整体未配置时，所有邮箱均返回服务不可用。

- 不提交账号、API Key、数据库密码、日志、上传文件和本地运行数据。
- MySQL、Redis、JWT、邮件、RAG 与 Milvus 凭据通过 `.env`、环境变量或本地配置提供。
- 提交前检查 `git status`，确认没有 `.runtime`、`.env`、数据库数据文件和账号文件。

详细功能和验收方法见 [AI护理工作台使用说明.md](./AI护理工作台使用说明.md)。
本轮实际检查结果和未覆盖范围见 [协作验收记录.md](./协作验收记录.md)。

RAG使用MySQL版本快照与领取租约、Python持久化墓碑与跨进程文件锁、Milvus版本行过滤处理重试和乱序。Java与Python需要一起升级；恢复较旧MySQL时使用新的JSON路径和集合后重建，详见 [RAG 服务说明](./源码/beadhouse-ai/README.md)。

## 健康与用药告警

新增健康测量时，在同一事务中比较当前填写指标与最近历史记录（最多90条），触发已有变化阈值后保存 HEALTH_CHANGE 告警；只填写体重不会重新触发旧体温变化。此功能只描述数据变化，不作诊断。用药登记 SKIPPED 自动保存 MEDICATION_SKIPPED 告警，改为 DONE 后自动解除对应用药告警。

后台自动核对当前入住/退住审核老人的历史用药登记，首次从上海日期的最近31个已结束自然日开始，按日期和计划ID分批推进；已停用计划仍按其有效日期核对。游标与租约保存在数据库，停机后继续未处理进度，并定期重扫近期窗口以发现回填计划。已退住历史仍可在每日助手手动核对。缺少记录保存 MEDICATION_UNRECORDED，只表示待人工核对，不能认定实际漏服；当天和未来日期不会产生自动漏登记告警。

后台默认启用，每分钟运行一次，每次最多4批、每批100个计划，租约120秒；完成一个窗口后默认15分钟重扫。初次大范围补扫需要多个批次，失败不推进本批游标，后续自动重试。可通过 `.env.example` 中 `AI_ALERT_SCAN_*` 调整，关闭自动核对用 `AI_ALERT_SCAN_ENABLED=false`，手动核对仍可使用。状态接口只返回运行状态和日期时间，不公开租约、计划游标或原始错误。

导航“护理提醒”和每日助手“我的未读提醒”只展示当前有权限的未解除告警；未读数为全量计数，列表按游标加载。个人已读与人工确认/解除独立，其他员工已读不受影响；新证据会产生新通知版本，旧版本标记请求返回409并刷新。可见页面每45秒刷新，隐藏或离开后停止相应轮询，换账号清理旧会话数据。此功能不发送短信或邮件。

告警按测量记录或用药日期/计划/时段去重。相同证据重复核对保留确认/解除状态，证据变化才重新打开告警。告警同时关联协作任务；若旧任务已完成，新证据使用新的任务键，保留旧任务结果。解除告警不会修改测量、用药执行、余额或自动完成协作任务。

人工流程为待确认 → 已确认 → 已解除，解除需要1至500字依据，仅确认人或超级管理员可解除。版本冲突返回409；所有查询和操作沿用老人数据范围。服务对原记录与告警实行事务提交；告警写入失败时，原测量/登记也回滚，避免丢失提醒。

已有环境通过统一升级脚本应用告警及其前置迁移。新 Docker 数据卷会自动执行版本清单。PowerShell：

```powershell
./升级数据库.ps1
./检查数据库.ps1
```

接口：`GET /ai/daily/alerts?state=ACTIVE`、`POST /ai/daily/alerts/scan?date=yyyy-MM-dd`、`POST /ai/daily/alerts/ack`、`POST /ai/daily/alerts/resolve`。确认/解除提交 `id`、`revision`，解除附 `note`。返回 `sourceId`、`alertKey` 与 `taskKey` 供追踪原记录及关联任务。个人通知为 `GET /ai/daily/notifications?afterId=0&limit=20`、`GET /ai/daily/notification-count`、`POST /ai/daily/notifications/read`（仅 `id` 和 `noticeRevision`）；`GET /ai/daily/scan-status` 返回安全运行状态。所有HTTP接口仍需登录、每日助手菜单及当前老人范围，后台无HTTP系统绕过入口。

真实集成回归额外设置 `ALERT_HTTP_TEST=true`，仅允许指向可丢弃测试数据库；测试会模拟告警表不可用，以验证原业务回滚。

## 每日协作任务

每日助手新增协作任务区。点击“全量同步所选日期事项”后才写入任务快照；刷新与 GET 汇总仍只读。认领、转交、复核完成均为显式操作。无分配员工看不到任务；认领人来自登录会话，转交人必须在职并拥有老人及每日助手权限。只有负责人或超级管理员可转交/完成，完成须填写复核结果。

概览最多展示200条，服务预约来源最多展示50条；任一展示来源被截断时均标记 `truncated=true`。全量同步独立读取护理、用药、健康与预约来源，不再使用概览的截断列表：按主键游标每批读取100条，健康来源按老人编号分页去重，用药执行按当批计划查询，任务每批最多写入100条。页面展示上限不影响同步范围；返回的数量表示处理的来源事项数，包含已有任务的快照更新，不等于新增任务数。

同步采用一次 REPEATABLE READ 事务，任何后续批次失败都会回滚本次已写入的批次；基于事务快照读取，快照之后新增的来源由下次同步处理。相同来源按唯一键去重，重试不清空负责人或已完成结果。服务端事务超时120秒，页面等待上限150秒，期间显示同步等待状态，完成后显示处理数量；当前不是后台作业，不提供百分比进度或断点续传。若网络断开导致结果未确认，应先刷新核对再重试。未来日期仍返回业务码400。

任务按来源去重：护理跟进和服务预约跨日期沿用同一任务，用药按日期/计划/时段独立，健康复核按测量记录独立。同步不会清空负责人或重开已完成任务。并发认领由 MySQL 行锁串行处理，旧版本修改返回409。已完成任务的重复完成请求幂等；原业务事项后来发生变化，需要工作人员重新核对，任务不会自动替代原业务状态。

协作截止时间默认来源时间加24小时，逾期依据当前时间计算；完成后解除逾期展示。该截止时间不是医嘱服药时间。任务列表默认显示未完成，最多200条，保留历史任务便于追踪。此版本支持逾期展示与人工闭环，尚无短信/邮件升级提醒。

已有数据库通过统一升级脚本安装每日任务及其前置版本；新建 Docker MySQL 数据卷会自动执行。PowerShell 可在项目根目录执行：

```powershell
./升级数据库.ps1
./检查数据库.ps1
```

接口：`GET /ai/daily/tasks?state=ACTIVE&date=yyyy-MM-dd`、`POST /ai/daily/tasks/sync?date=yyyy-MM-dd`、`GET /ai/daily/tasks/owners?id=任务编号`、`POST /ai/daily/tasks/claim|transfer|complete`。操作提交 `id` 和 `revision`，转交附 `targetStaffId`，完成附 `note`。日常启动不会自动修改已有数据库结构。

真实 HTTP 并发回归需在可丢弃库执行全部初始化脚本，并设置 `DAILY_HTTP_TEST=true` 及前述 MySQL/Redis 测试变量。此测试会写入测试账号与业务样例，不可使用日常或生产数据库。

## 充值与业务扣费

充值金额必须大于0，金额最多两位小数且不超过数据库 `DECIMAL(10,2)` 上限99999999.99元；护理/点餐允许合法的零元订单，负数金额、非法数量、溢出和不存在/停用的菜品被拒绝。余额通过数据库条件 SQL 原子增加或扣减，余额不足不会扣成负数；超过余额上限或入住状态并发变化返回业务码409。老人档案、入住状态等普通实体更新不会写回旧余额。

护理和送餐执行在同一事务内锁定来源记录，再更新完成状态、扣费及写入消费记录；并发执行仅一次成功，重复执行返回既有“该订单已完成”。消费记录失败会回滚余额和完成状态。护理预定新增请求使用 `elderId`、`serviceId`、`frequency`，服务名称、单价、时长和总额由服务端读取有效按次项目后生成快照；客户端提交的报价字段不参与计费。当前页面已携带服务编号，其他调用方需同时传入 `serviceId`。

充值接口必填客户端生成的规范 UUID `requestId`。同一操作人、老人、金额和单号的重试只入账一次，返回同一凭证；同单号改金额、老人或操作人返回业务码409。不同单号表示不同充值，正常累计入账。每次都重新检查老人权限；老人退住后仍可查询或重放原成功凭证，新单不能充值。凭证中的充值后余额是入账当时的余额，当前余额通过账户列表刷新获取。

余额和 `deposit_recharge_ledger` 流水在同一事务写入，任一失败均回滚；唯一单号约束处理跨老人并发撞单，数据库检查金额和余额差额。已有库需先应用 `数据库/recharge_ledger_upgrade.sql`，只记录升级后的成功充值，保留原余额，历史流水不回填。接口兼容性变更：旧客户端未发送 `requestId` 会返回400，前后端需同步升级；外部支付接入另行实施。

充值页面提交期间禁止重复点击、修改金额和关闭弹窗。在同一标签页，待确认单按真实账号和老人隔离保存到 `sessionStorage`，刷新、关闭弹窗或重新登录后可恢复相同金额并沿用原单重试。修改金额会新建单据，应先核对“充值记录”；收到匹配成功凭证后才清理待确认信息。浏览器存储不可写时阻止提交。充值记录按老人权限在数据库分页，每页最多100条；经办人可查自己的单据凭证，管理员可审计，所有读取仍受老人范围限制。

在另一个标签页切换账号时，旧充值和流水窗口自动关闭；发送前及响应处理时复核账号，请求始终绑定原会话，防止把旧表单的充值记在新账号名下。待确认单继续保留在原账号下。

## 传统业务老人数据范围

老人分配沿用 `elder_staff_assignment`。超级管理员访问全部数据；普通员工需同时具备原模块菜单权限和老人访问范围。有效分配始终授予访问；仅咨询中、意向跟进、预定阶段，老人创建人、咨询经办人或 ACTIVE 未退款预订经办人也可访问。入住、退住审核及历史状态不会沿用旧销售关系；无授权时列表及导出为空，越权详情/写入返回403。分配撤销后下一次请求立即生效，无需重新登录。

覆盖老人档案分页、详情、修改、删除和导出；预存充值及消费记录；事故；外出及紧急联系人；护理预定与执行扣费；点餐与送餐；退住申请及费用审核；咨询、意向客户及其标签/回访计划/沟通记录、预订和来访。公共老人选择器按同一权限过滤，分页总数与导出遵循同一范围。记录操作按数据库里的老人归属校验，记录ID与老人ID不得拼接。新增业务复用已有老人时也会检查权限。床位全景保留床位库存与占用状态，无老人权限时隐藏姓名、性别、年龄，姓名搜索只搜索可访问的老人。其他历史业务接口仍需单独审计，不能仅凭此覆盖认定全系统都已隔离。

集成验证只使用独立临时数据库：导入演示库与 AI 迁移后设置 `SCOPE_TEST_DB_URL` 和 `SCOPE_TEST_DB_PASSWORD` 可启用真实 MySQL Mapper 回归；设置 `SCOPE_HTTP_REDIS_PORT` 可启用 Spring Boot 随机端口 HTTP 回归（临时 Redis 密码为测试专用 `scope-redis-only`）。HTTP 测试会修改测试库角色、演示密码与业务记录，必须使用可丢弃数据库，不能指向日常或生产库。

普通老人选择器、AI选择器、老人档案列表、外出选择器和退住选择器在数据库执行同范围COUNT/LIMIT，默认每页10条、最多100条并保持全局序号。未返回外出和待审退住在分页前排除，撤权实时生效；档案Excel仍导出全部授权记录。首页趋势图在离页时释放图表和独立resize监听，迟到请求不会访问已卸载DOM。

## 配置回归检查

前端使用 Node 24：在 `源码/beadhouse-frontend` 执行 `npm ci`、`npm run dev`（127.0.0.1:8080）或 `npm run build`。构建依次执行 `vue-tsc --noEmit`、ESLint 和 Vite；Vite 自身不做类型检查，Sass 并发限制为2以控制多核机器上的编译进程数量。开发代理从 `SILVERCARE_BACKEND_URL` 读取后端地址（默认 `http://127.0.0.1:9001`），`/api` 去前缀后转发，端口占用直接失败。一键启动自动传入当前实例的后端地址和前端端口。

旧 Vue CLI 构建链已迁移为 Vite。Tailwind 4 使用独立 PostCSS 插件和 `src/styles/tailwind.css`，显式加载原 JS 配置；保留页面现用色值、边框默认色及未分层覆盖顺序。浏览器最低要求为现代 Chromium、Safari 16.4+、Firefox 128+，不承诺旧浏览器支持；详见 [Tailwind 迁移说明](https://tailwindcss.com/docs/upgrade-guide)。`external-editor` 的 `tmp` 覆盖为0.2.7，保留中文交互式提交；`brace-expansion` 与 `picomatch` 仅覆盖有漏洞的旧版本范围，不降低新主版本。

Tailwind 使用 `source(none)` 和显式 `@source '../'` 仅扫描 `src`，避免工具文件、父目录及运行数据进入应用样式或拖慢隔离副本的首次编译；新增页面继续放在 `src` 内。构建入口为根 `index.html`，旧 `public/index.ejs` 模板已移除。

`npm run audit:dependencies` 分别审计完整依赖和生产依赖，任何级别告警都失败；不能以转移到开发依赖或忽略公告代替修复。兼容回归命令为 `npm run test:build-compat` 和 `npm run test:dependency-security`，覆盖懒路由、ESM中文语言包、SVG/床位图片、真实 CSS 生成与提交工具的临时文件安全。

GitHub Actions 的 `CI` 工作流在 main 推送、面向 main 的 PR（含草稿）及手动触发时运行：数据库迁移/恢复、后端 JDK 8 完整构建和独立 MySQL/Redis 集成、前端 Node 24 全新 `npm ci`与79项业务/依赖兼容契约、完整/生产依赖审计及构建、Python 3.12全依赖/pytest/真实BGE质量门禁，以及33项Chromium端到端回归和 Windows 启停所有权/恢复行为回归。关键的十七组后端集成（含权限、认证、附件、资金/床位并发、告警、RAG租约及实际SQL分页）若缺失或跳过，CI会失败。报告作为artifact保留7天；普通ESLint警告不导致构建失败。

CI使用临时库和合成凭据，不读取本地 `.env`，不需要GitHub Secrets，不投递真实邮件或调用付费模型。浏览器使用已验证的同一JAR/dist，在全新MySQL/专用Redis验证真实业务页面及数据库写入，并对丢响应、延迟和暂时失败做定向故障注入。失败保留截图、trace、后端日志和JUnit；Vue捕获后输出的console.error也纳入异常门禁。OCR仍按环境执行，Windows Tesseract相关项在Linux跳过。BGE评测仅覆盖当前合成制度语料；除登录/找回密码320px和390px输入回归外，其他移动业务页及其他浏览器需另行验证；完整 Windows/Docker Desktop 一键启动另通过隔离副本验收，不由 Linux 浏览器代理测试代替。

Python job启动隔离认证Milvus/MinIO/etcd，强制5项真实索引故障/乱序/集合重建及Linux fork回归；再以实际BGE执行独立服务和随机集合的质量门禁，报告模型SHA、逐题结果及分组延迟，详见 [RAG评测与基线](./源码/beadhouse-ai/README.md)。测试集合和数据卷结束时清理。

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

## 完整一键启动验收

Windows 上的 `python tools/startup_acceptance.py --action fresh` 会把当前已跟踪源码复制到 `.runtime/startup-check/<运行编号>/中文 空格 项目`，自动分配独立端口与 Compose 项目、新建数据卷，并实际调用 `启动AI养老院.ps1 -Prepare -InitializeDemo -AcknowledgeDemoOnly`。不会复制源项目的私有配置、依赖、构建产物或业务数据；先将新增公开脚本加入 Git 跟踪。已有的软件包、镜像、模型下载缓存可复用，因此不等同于新装操作系统或无缓存联网安装验收。

启动后默认保留隔离服务供业务验收，输出的 `acceptance.json` 是该次运行的恢复入口。下面的 `RUN`、`COPY`、`PORT` 用该次输出值替换，业务浏览器脚本另需 `.github/e2e/requirements.txt` 和已安装的 Chromium：

```powershell
python tools/startup_business_smoke.py --project-root "COPY" --frontend-port PORT --evidence "RUN" --phase write
python tools/startup_system_checks.py --manifest "RUN/acceptance.json" --phase running
python tools/startup_business_smoke.py --project-root "COPY" --frontend-port PORT --evidence "RUN" --phase verify
python tools/startup_system_checks.py --manifest "RUN/acceptance.json" --phase recovery
python tools/startup_system_checks.py --manifest "RUN/acceptance.json" --phase repair
python tools/startup_business_smoke.py --project-root "COPY" --frontend-port PORT --evidence "RUN" --phase verify
python tools/startup_acceptance.py --manifest "RUN/acceptance.json" --action stop
```

这组检查覆盖真实登录、老人测量写入与复读、Java异步制度同步到Python/Milvus及可见引用、重复启动、记录端口重启、缺配置停止、外来监听拒绝、失败回滚，以及只修复前端失败时保留原Java/RAG和五个容器。运行环境与首次启动保持一致（JDK 8、工具路径、模型缓存等）；证据、日志和截图留在该次被Git忽略的目录，停止保留卷，不操作日常数据库。浏览器人工复核可用独立命名的 `playwright-cli` 会话完成；不要把凭据放进命令行或提交截图中的私人数据。

本轮没有进行旧依赖升级：2026-10-21 的 `npm ci` 报告98项审计告警（10低、44中、38高、6严重），尚需单独评估与修复，不能把构建成功解释为依赖安全审计通过。真实SMTP、付费模型、OCR中文模型和其他浏览器/移动端不属于本次启动闭环的已验证范围。
