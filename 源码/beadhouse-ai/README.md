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

文档更新和删除先构造候选快照，写入同目录临时文件并 flush/fsync、原子替换成功后，才发布内存状态。磁盘写入或替换失败不会修改已提交的内存/文件，也不会继续写 Milvus；内部接口返回503及固定文案，不返回底层文件路径。读取损坏 JSON 或结构错误的文件会显式失败，不会把它当作空库覆盖。需要恢复时保留原文件，配置新的 `RAG_DATA_FILE` 和 Milvus 集合，再通过知识库“重建混合索引”从 MySQL 源数据恢复。

单个 RAG 进程内的索引更新、删除和查询按操作串行，防止并发更新把较旧版本覆盖到远端。Milvus 写入前先完成所有向量计算，模型计算失败不会先删除旧索引。远端删除/插入不是跨服务事务；网络或插入失败仍可能留下待修复索引，由现有 Spring `policy_rag_sync` 持久队列重试。当前 JSON 存储只支持单进程服务，不提供多个 worker/副本之间的文件锁；大文档索引期间查询可能等待，Spring 保留本地检索回退。

完整故障回归包含真实 Milvus：设置仅指向隔离环境的 `RAG_TEST_MILVUS_URI` 和 `RAG_TEST_MILVUS_TOKEN` 后运行 `python -m pytest -q`。测试只创建并删除随机命名的测试集合，使用 hashed 向量验证写入、故障和重试，不代表 BGE 语义质量评测。CI 自动启动带认证的 Milvus/MinIO/etcd，并检查该测试确实执行成功。
