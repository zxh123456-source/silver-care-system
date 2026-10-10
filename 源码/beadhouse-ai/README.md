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
