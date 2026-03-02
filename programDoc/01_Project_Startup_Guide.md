# 项目快速启动指南 (Quick Start Guide)

本文档旨在帮助开发者快速启动本项目，并验证环境是否正常可用。

## 0. 前置环境准备
确保服务器已安装以下组件：
- **Docker & Docker Compose**
- **Python 3.10+** (推荐使用 Conda 环境 `ai4tender`)
- **NVIDIA Driver & CUDA Toolkit** (用于 GPU 推理)

## 1. 启动 Docker 服务
首先确保所有基础服务（数据库、中间件、RAGFlow 后端、Xinference 推理引擎等）已启动。

```bash
# 切换到项目根目录
cd /home/ubutnu/code/AI4LocalKnowledgeBase

# 重启所有容器（确保配置更新生效）
sudo docker restart $(sudo docker ps -aq)

# 检查容器状态（应全部为 Up）
sudo docker ps
```

关键容器说明：
- `xinference`: 模型推理服务 (Port: 8085)
- `ragflow-server`: RAGFlow 核心服务 (Port: 8084/9380)
- `ragflow-mysql`, `ragflow-es01`, `ragflow-minio`: 基础存储组件

## 2. 加载 AI 模型
Docker 启动后，Xinference 服务虽然运行，但模型可能未加载。需运行专用脚本加载 Embedding、Rerank 和 LLM 模型。

**执行脚本**：
```bash
# 使用 conda 环境
conda activate ai4tender

# 运行模型启动脚本
python3 scripts/launch_xinference_models.py
```

**该脚本会自动执行以下操作**：
1. 启动 Embedding 模型 (`bge-m3`)
2. 启动 Rerank 模型 (`bge-reranker-v2-m3`)
3. **注册并启动 DeepSeek-R1-14B** (`deepseek-r1-distill-qwen-14b`)
   - 自动应用 `4-bit` 量化配置以防止 OOM。
   - 自动注册为 `qwen2.5-instruct` family 以支持 Tool Call。

## 3. 验证系统可用性

### 3.1 验证模型服务
运行以下脚本查看当前正在运行的模型列表：

```bash
python3 scripts/list_models.py
```
**预期输出**：
应包含 `bge-m3`, `bge-reranker-v2-m3`, `deepseek-r1-distill-qwen-14b` 三个 UID。

### 3.2 验证 RAGFlow 连通性
运行基础连通性测试，确保 RAGFlow 后端能连接到 Xinference：

```bash
python3 backend/test/01_verify_connectivity.py
```

### 3.3 验证对话功能 (端到端测试)
运行流式对话测试脚本，验证 LLM 是否能正常生成回复（包含 `<think>` 标签）：

```bash
python3 backend/test/debug_ragflow_stream.py
```
**预期结果**：
终端应输出流式生成的文本，且不报错。

## 4. 常见问题排查

- **Docker 权限问题**：
  如果提示 `permission denied`，请在 docker 命令前加 `sudo`。

- **显存不足 (OOM)**：
  如果 `launch_xinference_models.py` 失败或模型无法启动，请检查显存占用：
  ```bash
  sudo docker exec xinference nvidia-smi
  ```
  DeepSeek-14B 4-bit 量化后应占用约 13GB 显存。如果占用过高（>20GB），说明量化未生效，请确认使用的是 `scripts/launch_xinference_models.py` 启动。

- **RAGFlow 报错 "GENERIC_ERROR"**：
  通常是由于 LLM 服务不可用（挂掉或 OOM）。请先验证 3.1 步骤。

---
**维护记录**:
- 2026-03-02: 创建文档，集成 DeepSeek OOM 修复后的启动流程。
