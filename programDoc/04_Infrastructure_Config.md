# 基础设施配置 (Infrastructure Config)

## 1. 硬件规格 (Hardware)
本系统设计为单节点本地化部署，核心依赖 GPU 算力。

### 1.1 推荐规格
*   **服务器**: Linux Server (Ubuntu 22.04/24.04 LTS)
*   **CPU**: ≥ 16 Cores (推荐 AMD EPYC / Intel Xeon)
*   **RAM**: ≥ 64 GB (建议 128GB，因 Elasticsearch 与 RAGFlow 组件内存消耗较大)
*   **GPU**: NVIDIA RTX 5090 (24GB)
*   **Disk**: NVMe SSD ≥ 1TB (MinIO 存储文档 + ES 索引)

### 1.2 当前服务器核查结果 (2026-02-04)
*   **OS**: Ubuntu 24.04.2 LTS
*   **Kernel**: 6.14.0-37-generic
*   **CPU**: 8 vCPU
*   **RAM**: 31.34GiB (Available: ~12GB, RAGFlow+Xinference 运行中)
*   **Disk**: / 约 1004G (已用约 93G)

## 2. 软件环境
*   **Docker**: 24.0+
*   **NVIDIA Driver**: 550.00+
*   **NVIDIA Container Toolkit**: 1.14+
*   **Kernel Config**: `vm.max_map_count=262144` (Elasticsearch 必需)

### 2.1 当前软件版本核查结果 (2026-02-04)
*   **Docker**: 28.2.2
*   **Docker Compose**: 2.37.1
*   **NVIDIA Container Toolkit (nvidia-ctk)**: 1.18.1
*   **Docker Runtime**: 已存在 `nvidia` runtime（`docker info` 可见）
*   **vm.max_map_count**: 1048576（满足 Elasticsearch 要求）

### 2.2 GPU 驱动核查与问题处理记录 (2026-02-04)
*   **历史问题**: `nvidia-smi` 曾报 `Failed to initialize NVML: Driver/library version mismatch`
*   **原因定位**: 内核侧驱动模块版本与用户态 NVML 库版本不一致（常见于驱动升级后未重启）
*   **处理结果**: 已恢复正常，当前 `nvidia-smi` 输出显示版本一致
    *   NVIDIA-SMI: 580.126.09
    *   Driver Version: 580.126.09
    *   GPU: NVIDIA GeForce RTX 5090 (Memory 32607MiB)

### 2.3 Docker GPU 可用性验证 (2026-02-04)
*   **验证命令**:
    ```bash
    sudo docker run --rm --runtime=nvidia --gpus all ubuntu nvidia-smi
    ```
*   **验证结果**: 容器内 `nvidia-smi` 可正常输出，说明 Docker 已可稳定访问宿主机 GPU

## 3. 端口规划 (Port Mapping)
采用 **8082-8085** 连续端口段作为对外入口端口，便于统一访问与运维排查。

| 组件 | 角色 | 宿主机端口 | 容器内端口 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **Frontend** | 业务前端 | **8082** | 8082 | Vue/React 静态资源服务 |
| **Backend** | 业务后端 | **8083** | 8083 | Spring Boot API 服务 (Docker) |
| **RAGFlow** | 核心引擎 | **8084** | 80 | RAGFlow Server (Nginx) 对外入口 |
| **Xinference** | 模型服务 | **8085** | 8085 | 模型推理 API |

### 3.1 端口冲突复核 (2026-02-04)
已复核 [docker-compose4other.yml](file:///home/ubutnu/code/AI4LocalKnowledgeBase/deploy/docker-compose4other.yml) 中已部署服务端口（如 8080/8081/7860/8005/5432/9000/9001/19530/9091），未占用 8085。

### 依赖组件端口偏移 (RAGFlow Internal)
为避免与现有服务冲突，RAGFlow 内部组件端口已做修改：
*   MinIO API: `9002` (原 9000)
*   MinIO Console: `9003` (原 9001)
*   MySQL: `3307` (原 3306)
*   Redis: `6381` (原 6379)
*   Elasticsearch: `1200` (原 9200)

## 4. 显存分配策略 (VRAM Plan)
针对 24GB 显存的极限压榨方案。

| 模型 | 规格 | 显存占用 (Static) | 备注 |
| :--- | :--- | :--- | :--- |
| **DeepSeek-R1-14B** | Int4 Quantization | ~9.5 GB | 核心生成模型 |
| **bge-m3** | FP16 | ~1.5 GB | 向量检索模型 |
| **bge-reranker-v2-m3** | FP16 | ~2.5 GB | 精准重排模型 |
| **KV Cache / Buffer** | Dynamic | ~10.5 GB | 用于长文档上下文与并发 |
| **Total** | | **24.0 GB** | **刚好填满** |

> **OOM 预案**: 若出现 Out Of Memory，优先降低 Rerank 模型为 `bge-reranker-base`，或将 Embedding 模型迁移至 CPU 运行。

## 5. 网络架构 (Network)
所有容器部署在同一 Docker Network (`ragflow_ragflow`) 下。
*   Backend -> RAGFlow: `http://ragflow-server:80`
*   RAGFlow -> Xinference: `http://xinference:8085`
*   Frontend -> Backend: `http://host-ip:8083` (浏览器端访问)

## 6. 部署操作指南 (Deployment Steps)

### 6.1 部署 Xinference (Port 8085)
```bash
# 创建数据目录
mkdir -p /data/xinference-models

# 启动容器 (注意 -p 8085:8085)
sudo docker run -d --name xinference \
  --gpus all \
  -p 8085:8085 \
  -v /data/xinference-models:/root/.xinference \
  -e XINFERENCE_HOME=/root/.xinference \
  xprobe/xinference:latest xinference-local -H 0.0.0.0 -p 8085
```

**服务可用性验证**:
```bash
sudo ss -ltnp | awk 'NR==1 || $4 ~ /:8085$/'
sudo curl -sS -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:8085/
sudo curl -sS -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:8085/v1/models
sudo docker logs --tail 120 xinference
sudo docker exec xinference bash -lc 'ls -l /dev/nvidia* 2>/dev/null || true'
```

**验证结果 (2026-02-04)**:
*   容器状态: `Up` 且端口映射 `0.0.0.0:8085->8085/tcp`
*   HTTP 探测: `/` 返回 307；`/v1/models` 返回 200，已包含 3 个模型：
    *   `deepseek-r1-distill-qwen-14b` (LLM)
    *   `bge-m3` (Embedding)
    *   `bge-reranker-v2-m3` (Rerank)
*   GPU 设备: 容器内可见 `/dev/nvidia0` 且 `nvidia-smi` 显示对应进程。

**加载模型命令**:
```bash
# 1. 启动 LLM (14B Int4)
xinference launch --model-name deepseek-r1-distill-qwen --size-in-billions 14 --model-format pytorch --quantization int4 --n-gpu 1

# 2. 启动 Embedding
xinference launch --model-name bge-m3 --model-type embedding

# 3. 启动 Rerank
xinference launch --model-name bge-reranker-v2-m3 --model-type rerank
```

### 6.2 部署 RAGFlow (Port 8084)
使用仓库内的 [docker-compose-ragflow.yml](file:///home/ubutnu/code/AI4LocalKnowledgeBase/deploy/docker-compose-ragflow.yml) 一键启动 RAGFlow 与依赖（已内置端口偏移与镜像拉取优化）。

**1. 启动服务**:
```bash
sudo docker compose -f deploy/docker-compose-ragflow.yml up -d
```

**2. 数据库初始化（仅首次）**:
若 `ragflow-server` 日志出现 `Unknown database 'rag_flow'`，执行：
```bash
sudo docker exec ragflow-mysql mysql -uroot -pinfini_rag_flow -e 'CREATE DATABASE IF NOT EXISTS rag_flow CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
sudo docker compose -f deploy/docker-compose-ragflow.yml up -d --force-recreate ragflow
```

**3. 将 Xinference 接入同一网络（用于容器内互访）**:
```bash
sudo docker network connect ragflow_ragflow xinference || true
```

**4. 服务可用性验证**:
```bash
sudo ss -ltnp | awk 'NR==1 || $4 ~ /:(8084|1200|3307|9002|9003|6381)$/'
sudo curl -sS -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:8084/
sudo docker logs --tail 200 ragflow-server
```

### 6.3 部署业务系统 (Port 8083)
使用 [docker-compose-ragflow.yml](file:///home/ubutnu/code/AI4LocalKnowledgeBase/deploy/docker-compose-ragflow.yml) 统一管理，后端服务 `backend` 会自动构建并加入网络。

**管理命令**:
```bash
# 构建并启动后端
sudo docker compose -f deploy/docker-compose-ragflow.yml up -d --build backend

# 查看日志
sudo docker compose -f deploy/docker-compose-ragflow.yml logs -f backend

# 状态检查
curl http://localhost:8083/api/admin/datasets  # 期望返回 RAGFlow 知识库列表
```

### 6.4 RAGFlow UI 模型配置（对接 Xinference）
本节用于把 **Xinference** 已启动的三个模型（LLM/Embedding/Rerank）接入 **RAGFlow**，让 RAGFlow 能完成“解析 -> 向量化 -> 重排 -> 生成回答”的全流程。

**关键原则**:
*   **RAGFlow 在容器内调用模型**，因此 Base URL 必须使用容器网络可达地址：`http://xinference:8085`（而不是 `http://127.0.0.1:8085`）。
*   Xinference 提供 OpenAI 兼容接口，RAGFlow 侧通常选择 **OpenAI / OpenAI-Compatible** 类型进行接入。

**1) 确认 Xinference 模型 ID（宿主机执行）**:
```bash
curl -sS http://127.0.0.1:8085/v1/models
```
期望返回包含以下 `id`（示例）:
*   `deepseek-r1-distill-qwen-14b`（LLM）
*   `bge-m3`（Embedding）
*   `bge-reranker-v2-m3`（Rerank）

**2) 在 RAGFlow UI 中新增模型**（路径可能是：System Settings / Models / Providers）:
*   **LLM**:
    *   Provider: OpenAI-Compatible
    *   Base URL: `http://xinference:8085`
    *   API Key: 留空（如 UI 强制必填则随便填一段字符串即可）
    *   Model: `deepseek-r1-distill-qwen-14b`
*   **Embedding**:
    *   Provider: OpenAI-Compatible
    *   Base URL: `http://xinference:8085`
    *   API Key: 留空（如 UI 强制必填则随便填一段字符串即可）
    *   Model: `bge-m3`
*   **Rerank**:
    *   Provider: 优先选择 **Cohere**（或 UI 中明确标注为 Rerank 的 Provider）
    *   Base URL: `http://xinference:8085`
    *   Model: `bge-reranker-v2-m3`
    
> 说明：
> 1) 部分 UI 表单会把 Base URL 当作“Host”，并在内部自动拼接 `/v1/...`。此时 Base URL 需要填写到 `http://xinference:8085`，否则容易出现把路径拼成 `/v1/v1/...` 导致校验失败。  
> 2) 某些版本在使用 OpenAI-Compatible 添加 `rerank` 时会提示“不支持该模型”。这是 Provider 能力限制（并非 Xinference 模型不可用）。此时请改用 Cohere/Rerank Provider 对接 Xinference 的 `POST /v1/rerank`。

**3) 容器内连通性快速排查（需要 sudo，宿主机执行）**:
```bash
sudo docker exec ragflow-server bash -lc "curl -sS -o /dev/null -w 'HTTP %{http_code}\n' http://xinference:8085/v1/models"
```
