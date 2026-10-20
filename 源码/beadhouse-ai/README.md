# 银龄智慧康护 RAG 服务

FastAPI 服务负责制度文档索引和检索，Spring Boot 继续负责登录、权限、MySQL 数据、模型回答与审计。

Spring Boot 不信任检索服务返回的正文。FastAPI 只返回 MySQL 片段 ID 和得分，Spring 会重新查询 `policy_doc` 构造最终引用；失效 ID 会被丢弃并触发本地回退。

默认使用可离线运行的本地混合检索：中文二元词 BM25 + `BAAI/bge-small-zh-v1.5` 中文语义向量 + RRF 融合。配置 Milvus 后可切换到 Milvus 混合检索；任何外部服务异常时 Spring Boot 会回退原有本地检索。

## 本地启动

```powershell
python -m venv .venv
.\.venv\Scripts\pip.exe install -r requirements-local.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

健康检查：`GET http://127.0.0.1:8001/health`。

内部索引与查询接口要求请求头：

```text
X-Internal-Token: <RAG_INTERNAL_TOKEN>
```

必须显式配置至少32字节的 `RAG_INTERNAL_TOKEN`，与 Spring 的 `AI_RAG_INTERNAL_TOKEN` 一致。代码不提供默认令牌。Milvus 模式还须设置 `RAG_MILVUS_TOKEN=root:<MILVUS_ROOT_PASSWORD>`。根目录启动脚本会从根 `.env` 读取并传给服务；单独运行时需要先设置环境变量或本目录 `.env`。

## Milvus Standalone

安装 Docker 后执行：

```powershell
docker compose -f ../../docker-compose.yml up -d
.\.venv\Scripts\pip.exe install "pymilvus==2.6.17"
$env:RAG_BACKEND="milvus"
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

Milvus 使用中文 BM25 sparse 检索、BGE 中文稠密向量检索与 RRF 融合。默认模型维度为 512；更换 embedding 模型时需要同步修改 `RAG_DENSE_DIMENSION` 和集合名称，然后重建索引。

## 自动评测

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe evaluate_rag.py
```

评测脚本会临时导入测试制度、验证 citation recall@5 和 MRR，随后自动删除测试数据。

## 写入失败与恢复

写入统一使用 `POST /v2/documents/sync`，必须携带文档键、正整数版本、`UPSERT/DELETE`、不可变片段和规范JSON的SHA256摘要。旧 `/v1/documents/index` 与 `/v1/documents/delete` 明确拒绝无版本写入；查询仍使用 `/v1/query`。Java和Python必须一起升级，不能把HTTP200或旧服务应答当成新版同步成功。

MySQL的 `policy_rag_sync` 是版本权威，源片段和期望快照在同一事务保存；RAG关闭时仍排队。worker以短事务领取租约，在事务外调用Python，完成时核对版本、操作、摘要及有效租约。旧worker迟到不会把新版本标成成功。升级前曾在关闭RAG时导入、没有同步行的制度，需要通过“重建混合索引”补入队列。

JSON文件是Python端持久化版本清单，保留删除墓碑。每次操作重新加载文件，使用稳定旁路锁文件实现同一主机、同一路径多个进程互斥；不承诺跨主机或不支持可靠文件锁的共享盘。原列表格式首次读取时保存原始字节为 `.legacy.bak`，转换为版本0待重建状态。磁盘发布使用临时文件、flush/fsync及原子替换；损坏文件显式失败，不当空库覆盖。

Milvus使用独立版本集合，物理名称为 `RAG_MILVUS_COLLECTION` 加 `_v2`，`/health` 返回实际名称，旧集合保留。每行绑定文档版本与摘要，旧请求只能删除不高于自身版本的行；检索按当前JSON清单过滤，删除后到达的旧向量不会重新可见。索引完成标记还绑定集合代次和embedding配置；同版重试可修复持久化成功而索引未完成的请求。未完成时查询明确返回 `INDEX_SYNC_PENDING` 并使用最新清单做内存回退。

正常故障由持久队列重试，不删除墓碑或降低版本。如果从较旧MySQL备份恢复，必须同时改用新的 `RAG_DATA_FILE` 和新的 `RAG_MILVUS_COLLECTION`，再从恢复后的MySQL重建；不能向仍保留较高版本的旧缓存强写低版本。备份恢复边界还包括单独保存的上传附件，MySQL备份不包含JSON或Milvus。

完整故障回归包含真实 Milvus：设置仅指向隔离环境的 `RAG_TEST_MILVUS_URI` 和 `RAG_TEST_MILVUS_TOKEN` 后运行 `python -m pytest -q`。测试只创建并删除随机命名的测试集合，使用 hashed 向量验证写入、故障和重试，不代表 BGE 语义质量评测。CI 自动启动带认证的 Milvus/MinIO/etcd，并检查该测试确实执行成功。
