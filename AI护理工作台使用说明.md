# AI 护理工作台

这部分功能将护理员的口述整理为可确认的护理记录，并生成交班摘要、家属周报草稿、健康趋势、用药核对和每日护理汇总。

## 启用步骤

1. 在 MySQL 的 `db_beadhouse` 数据库执行 `数据库/ai_care_upgrade.sql`。
2. 执行 `启动AI养老院.ps1`，脚本会使用 JDK 8 启动 Redis、Spring Boot 和 Vue 前端；MySQL 需要预先启动。
3. 使用管理员账号重新登录，进入“AI护理工作台”。首次执行迁移或修改角色权限后，需要退出再登录以刷新 Redis 权限缓存。

停止由脚本启动的进程时执行 `停止AI养老院.ps1`。运行日志保存在项目 `.runtime/logs` 目录。

迁移脚本会创建 `care_note` 表、菜单权限，并给管理员角色分配菜单。已有数据库重复执行时，表和菜单写入使用幂等语句。

## 功能入口

- 页面：`/ai-care/workbench`
- 生成护理草稿：`POST /api/ai/care/draft`
- 确认保存：`POST /api/ai/care/save`
- 修改记录：`PUT /api/ai/care/update`
- 查询交班：`GET /api/ai/care/handover?date=yyyy-MM-dd`
- 完成待跟进：`PUT /api/ai/care/complete?id={id}`
- 家属周报草稿：`POST /api/ai/care/family-report`
- 分配老人数据范围：`POST /api/ai/scope/assignment`（仅超级管理员）
- 查询当前账号可访问的老人：`GET /api/ai/scope/elders`
- 导入制度文档：`POST /api/ai/policy/import`
- 上传 txt、md、pdf、docx、png、jpg、jpeg、bmp：`POST /api/ai/policy/upload`
- 查询 OCR 状态：`GET /api/ai/policy/ocr/status`
- 检索制度知识库：`POST /api/ai/policy/query`
- 新增健康测量：`POST /api/ai/health/measurement`
- 查询健康趋势：`GET /api/ai/health/trend?elderId={id}&limit=30`
- 搜索在住老人：`GET /api/ai/health/elders`
- 分页查询 AI 审计：`GET /api/ai/audit/page?pageNum=1&pageSize=20`
- 新增用药执行计划：`POST /api/ai/medication/plan`
- 查询当日用药清单：`GET /api/ai/medication/day?elderId={id}&date=yyyy-MM-dd`
- 登记用药执行结果：`POST /api/ai/medication/execute`
- 停用用药执行计划：`PUT /api/ai/medication/plan/disable?planId={id}`
- 生成每日护理只读汇总：`GET /api/ai/daily/overview?date=yyyy-MM-dd`
- 查询 AI 指标汇总：`GET /api/ai/audit/metrics/summary?days=7`

AI 只生成草稿。护理记录和家属周报都需要工作人员确认或修改后使用，系统不会自动发送家属消息，也不会做医疗诊断。

## 配置模型服务

默认关闭外部模型调用，系统使用本地规则解析和本地摘要。需要启用 OpenAI 兼容接口时配置：

```text
AI_PROVIDER_ENABLED=true
AI_PROVIDER_URL=https://example.com/v1/chat/completions
AI_PROVIDER_API_KEY=your-api-key
AI_PROVIDER_MODEL=your-model
AI_PROVIDER_TIMEOUT_MS=8000
```

模型请求包含护理记录中的老人和护理信息。启用前应确认模型服务的数据处理策略符合院方隐私要求。模型调用失败或超时会自动回退本地结果，护理页面仍可继续使用。

制度上传支持 txt、md、pdf、docx 和扫描图片。文本按段落切片后进入 FastAPI 检索服务；扫描 PDF 先读取文字层，必要时按页调用 Tesseract OCR，不会把空识别结果导入知识库。页面上的“扫描 OCR”状态会说明引擎和语言数据是否就绪。默认语言为 `chi_sim+eng`，首次准备中文语言数据可运行项目根目录的 `配置中文OCR.ps1`；也可以通过 `AI_OCR_COMMAND`、`AI_OCR_LANGUAGE`、`AI_OCR_DATA_DIR`、`AI_OCR_TIMEOUT_MS`、`AI_OCR_DOCUMENT_TIMEOUT_MS` 调整。未安装 Tesseract 或缺少语言包时，普通文本 PDF 仍可导入，扫描文件会返回明确的配置提示。

制度检索已经拆分为 FastAPI 服务 `源码/beadhouse-ai`。默认启用本地 BM25 + `BAAI/bge-small-zh-v1.5` 中文语义向量 + RRF 混合检索，监听 `127.0.0.1:8001`；Spring Boot 会在 MySQL 事务提交后同步索引，并通过 `policy_rag_sync` 自动重试。FastAPI 停机、超时或返回失效片段时，Spring 会在约 2 秒内回退本地关键词检索。制度管理页面会显示每份文档的索引同步状态。

一键启动脚本会在 Docker 可用时启动 Milvus 2.6.24，并将 `RAG_BACKEND` 自动设置为 `milvus`；当前默认使用 Milvus 中文 BM25 + `BAAI/bge-small-zh-v1.5` dense + RRF 混合检索，Milvus 不可用时回退本地 BM25 + 关键词检索。Spring 会校验返回的片段 ID 后再生成引用，FastAPI 会拦截提示注入问题。当前机器已完成真实 Milvus 检索评测。

RAG 验证命令：

```powershell
cd 源码\beadhouse-ai
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe evaluate_rag.py
```

健康趋势页面位于 `/ai-care/health`，支持体温、心率、血压、血氧、体重、空腹血糖和餐后血糖。系统比较每项指标最近两次非空记录，生成可配置的数据变化提醒；提醒只用于工作人员复核，不构成医疗诊断。变化阈值通过 `AI_HEALTH_*_CHANGE` 环境变量配置。

AI 操作审计页面位于 `/ai-care/audit`，默认只给超级管理员。日志记录模块、动作、对象和操作人，不记录护理口述、制度问题、健康指标值、模型输入输出、Token 或 API Key。执行迁移后应退出并重新登录，以刷新 Redis 中的权限缓存和前端菜单。

“AI数据权限”页面位于 `/ai-care/scope`。超级管理员分配员工与老人的关系；护理、健康、用药、交班和每日助手接口都会在服务端再次校验老人范围，不能通过修改前端参数越权。超级管理员可以访问全部在住老人，普通员工只能看到仍处于启用状态的分配记录。

用药执行核对页面位于 `/ai-care/medication`。工作人员依据已核对医嘱人工创建计划，系统生成早、中、晚、睡前的当日执行项，支持登记“已执行”或“未执行及原因”。同一计划、日期和时段使用数据库唯一键和原子 upsert 防止重复记录。本功能不推荐药物、不调整剂量、不判断药物相互作用，也不自动扣减药品库存。

“每日护理助手”页面位于 `/ai-care/daily`，只汇总护理待跟进、用药待核对、健康变化和未完成服务预约，并提供回到原业务页面的入口。页面不会自动完成护理记录、登记用药、修改健康数据或执行会扣费的服务。

护理工作台的原始记录框支持 Chrome/Edge 的中文 Web Speech API。语音结果只会填入原始记录文本框，工作人员仍需点击“生成草稿”、核对字段并确认保存；浏览器不支持或未授权麦克风时可以直接键入。

审计页面同时展示近 31 天的调用量、成功率、回退率、错误率、模型使用率、引用覆盖率、P95 延迟和 RAG 待同步数量。指标只保存功能、阶段、结果和低基数错误码，不保存护理口述、问题正文、健康指标或模型输入输出。

## 推荐验收数据

使用一名在住老人，输入：

> 下午没有参加活动，说昨晚没睡好，已经跟晚班说了，明天再问一下。

确认草稿字段、保存记录、切换交班日期、查看详情、编辑记录、标记待跟进完成，再生成家属周报草稿。
