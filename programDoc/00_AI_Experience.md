# AI 经验总结

## Hugging Face 模型镜像下载实战 (hf-mirror)

### 1. 核心问题与解决方案
在国内网络环境下下载 Hugging Face 大模型（如 DeepSeek, Qwen 等）时，常遇到 `Network is unreachable` 或连接超时的问题。解决方案是使用镜像站点 `hf-mirror.com`。

### 2. 关键操作步骤

#### A. 代码层面的配置 (Python)
必须严格遵循 **"先配置环境，后导入库"** 的顺序。`huggingface_hub` 在导入时会读取环境变量初始化常量，如果导入后再设置环境变量将无效。

```python
import os

# 1. 必须在导入 huggingface_hub 之前设置
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
# 建议设置超时和禁用 XET 以提高稳定性
os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "60")

# 2. 之后再导入
from huggingface_hub import snapshot_download
```

#### B. 运行时环境配置 (Shell)
如果在使用了代理的环境中（如公司内网或开启了全局代理），连接镜像站可能会报 SSL 错误 (`UNEXPECTED_EOF_WHILE_READING`)。此时需要 **清空代理变量**。

**推荐启动命令**:
```bash
# 清空代理并使用 python -u (无缓冲) 以显示进度条
export http_proxy="" https_proxy="" ALL_PROXY="" && python -u download_script.py
```

#### C. 依赖管理
如果环境中有 SOCKS 代理残留配置，`httpx` 库可能会报错。确保安装了 SOCKS 支持：
```bash
pip install httpx[socks]
```

### 3. 经验总结
1.  **Import 顺序至关重要**: 这是最容易被忽视的坑。`HF_ENDPOINT` 必须在 `import huggingface_hub` 之前设置。
2.  **代理冲突**: 镜像站通常直连速度很快（~50MB/s），使用代理反而可能导致 SSL 握手失败。
3.  **进度条显示**: 在非交互式终端或后台运行时，Python 默认会缓冲输出。使用 `python -u` 可以强制刷新缓冲区，实时看到 `tqdm` 进度条。
4.  **断点续传**: `snapshot_download` 默认支持断点续传。如果下载中断，重新运行脚本即可（会先校验已下载文件的 Hash，速度较快）。

### 4. 实战资源 (Skill)
完整的下载脚本已封装为 Trae Skill，包含 `download.py` 脚本和配置模板。
位置: `.trae/skills/hf-mirror-download/`
- **Script**: `.trae/skills/hf-mirror-download/download.py`
- **Config**: `.trae/skills/hf-mirror-download/download_config.json`

可以直接引用该脚本进行下载，无需重复编写代码。

## 2026-02-04：验证三模型能跑起来 + 内存占用排障（Xinference + RAGFlow）

### 1. 典型现象（非常迷惑）
1.  `GET /v1/models` 能看到 3 个模型都在列表里，但：
    *   `bge-m3`（Embedding）与 `bge-reranker-v2-m3`（Rerank）可正常调用
    *   `deepseek-r1-distill-qwen-14b`（LLM）调用 `POST /v1/chat/completions` 却返回 `detail: [Errno 111] Connection refused`
2.  系统看起来“内存很紧张”（Swap 使用很高），以为是“三模型把内存占满导致启动失败”。

### 2. 核心判断思路（先分清 RAM / Swap / 显存）
1.  **不要用 Swap 的高低判断“当前是否内存顶满”**：
    *   Swap 很可能是之前内存压力时期被换出的页残留，并不会自动马上降回去
    *   判断当前是否真顶满，优先看 `free -h` 的 `available`
2.  **大模型是否真正跑起来，优先看 `nvidia-smi` 的进程列表**：
    *   模型真在 GPU 上常驻，`nvidia-smi` 通常能看到对应的 `Model: xxx` 进程及显存占用
    *   如果 `GET /v1/models` 里有 LLM，但 `nvidia-smi` 没有该 LLM 进程，同时调用报 `Connection refused`，高度可疑：**LLM 后端 worker 没起来/端口没监听**

### 3. 关键排查动作（能快速定位根因）
#### A. 先确认“是网关坏了，还是 worker 坏了”
```bash
# 1) 网关是否可用（应返回 200 并列出模型）
curl -sS http://127.0.0.1:8085/v1/models | head

# 2) 三个接口分别探活（Embedding / Rerank / LLM）
curl -sS http://127.0.0.1:8085/v1/embeddings -H 'Content-Type: application/json' \
  -d '{"model":"bge-m3","input":["测试"]}' | head

curl -sS http://127.0.0.1:8085/v1/rerank -H 'Content-Type: application/json' \
  -d '{"model":"bge-reranker-v2-m3","query":"测试","documents":["a","b"]}' | head

curl -sS http://127.0.0.1:8085/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-r1-distill-qwen-14b","messages":[{"role":"user","content":"你好"}],"stream":true}' | head -n 20
```

#### B. 内存与显存要一起看
```bash
# RAM / Swap 现状（重点看 available）
free -h
swapon --show || true

# 显存 + 进程（判断 LLM 是否真的加载）
nvidia-smi
```

### 4. 最终根因与解决方案（这次真正在这里卡了很久）
#### 根因 1：RAGFlow 依赖（Elasticsearch）默认堆内存过大，挤压系统内存
*   现象：ES Java 进程 RSS 过大，导致系统 Swap 急剧上升，推理稳定性变差
*   解决：在 `deploy/docker-compose-ragflow.yml` 给 ES 配置 JVM 堆内存上限（例如 2G），并重建 ES 容器使其生效

#### 根因 2：LLM 实例处于“看似 READY，实际 worker 不可达”的状态
*   现象：`/v1/models` 仍列出 LLM，但调用 `chat/completions` 报 `Connection refused`，`nvidia-smi` 看不到 LLM GPU 进程
*   解决：清理残留/异常实例后，重新 `launch` LLM，并采用 `transformers + bitsandbytes 4bit`（降低显存压力、提高稳定性）

示例做法（思路示例，参数以现场为准）：
```bash
# 1) 先终止旧实例，避免残留 worker 地址导致持续拒绝连接
curl -sS -X DELETE http://127.0.0.1:8085/v1/models/deepseek-r1-distill-qwen-14b || true

# 2) 重新 launch，并使用 bnb 4bit 量化（关键点：让 LLM 真正加载并常驻）
sudo docker exec xinference bash -lc "
xinference launch -e http://127.0.0.1:8085 \
  -n deepseek-r1-distill-qwen -t LLM -en transformers \
  -u deepseek-r1-distill-qwen-14b -mp /models/deepseek-r1-distill-qwen-14b \
  --trust-remote-code true \
  -qc load_in_4bit true \
  -qc bnb_4bit_quant_type nf4 \
  -qc bnb_4bit_compute_dtype float16 \
  -qc bnb_4bit_use_double_quant true
"

# 3) 用 stream=true 快速验证（大模型思考输出长，用流式更容易判断“真的在推理”）
curl -sS -N http://127.0.0.1:8085/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-r1-distill-qwen-14b","messages":[{"role":"user","content":"用一句话解释三等水准测量是什么"}],"stream":true}' | head -n 20
```

### 5. 最终验收标准（可复用）
1.  `bge-m3`、`bge-reranker-v2-m3`、`deepseek-r1-distill-qwen-14b` 三个接口都能返回成功数据
2.  `nvidia-smi` 能看到 3 个模型对应的 GPU 进程，显存占用符合预期
3.  `free -h` 的 `available` 不应接近 0（Swap 高不等价于失败，但持续增长要警惕）

### 6. 顺手避坑：RAGFlow 里如何正确填 Base URL
*   **RAGFlow 在容器内调用模型**，Base URL 要用容器网络地址：`http://xinference:8085`
*   不要填 `http://127.0.0.1:8085`（那会指向 RAGFlow 容器自身）

## 2026-02-04：RAGFlow 接口自动化测试与鉴权避坑

### 1. 痛点：API Token 获取困难
RAGFlow 的 API 设计中，`Authorization` 需要 Bearer Token，但默认数据库的 `api_token` 表可能是空的。
*   **现象**: 直接调用 API 报 401 Unauthorized。
*   **尝试**: 在 UI 上找“获取 API Key”的入口，可能藏得较深或需要手动创建。
*   **解决**: 直接操作 MySQL 数据库生成 Token。
    ```sql
    INSERT INTO api_token (tenant_id, token, source, ...) VALUES ('default', 'ragflow-xxx', 'manual', ...);
    ```

### 2. 自动化测试脚本 (`test_ragflow_e2e.py`)
为了避免每次手动 Curl，我们编写了 Python 脚本实现“建库 -> 上传 -> 解析 -> 问答”全链路闭环。

**关键技巧**:
1.  **轮询解析状态**: 文档上传后，RAGFlow 后台异步解析。脚本需要 `while True` 轮询 `/api/v1/doc/status/{doc_id}` 直到 `run_status == '1'` (SUCCESS)。
2.  **文件上传格式**: 使用 `requests.post(..., files={'file': open('test.txt', 'rb')})`，注意不要手动设置 Content-Type header，让 requests 自动处理 boundary。
3.  **JSON 里的布尔值**: RAGFlow API 有些字段要求 JSON boolean (true/false)，有些要求字符串 "1"/"0"，需对照源码或文档确认。

### 3. Nginx 代理与内部端口
RAGFlow 的 Docker 架构中：
*   外部访问: `8084` (映射到容器内的 Nginx 80)
*   内部 API 服务: `9380` (Python 后端)
*   **坑**: 如果直接 curl 容器内的 80，可能会被 Nginx 规则拦截或重定向。
*   **稳妥做法**: 
    *   外部测试用宿主机 IP:8084
    *   内部测试（如在容器内）直接访问 `http://127.0.0.1:9380` 绕过 Nginx 验证（如果后端绑定了 0.0.0.0）。

## 2026-02-04: RAGFlow PDF 解析自动化与 API 避坑指南 (终极版)

### 1. 核心痛点：API 触发解析为何总是 401/404？
尝试通过 API Key 调用 `/api/v1/document/run` 或 `/api/v1/datasets/.../run` 触发解析时，常遇到鉴权失败或路径不存在。
*   **根本原因**: RAGFlow 的前端 API (`document_app.py`) 依赖 Session/Cookie (`@login_required`)，不支持 API Key。
*   **误区**: 不要死磕 `/run` 相关的 REST 路径，那是给浏览器用的。

### 2. 正确解决方案：使用 SDK 专用端点
RAGFlow 为 SDK (`doc.py`) 提供了独立端点，支持 `Authorization: Bearer <API_KEY>`。

*   **API 端点**: `POST /api/v1/datasets/{dataset_id}/chunks`
*   **关键参数**:
    *   Headers: `Authorization: Bearer <API_KEY>`
    *   Body: `{"document_ids": ["<doc_id>"]}`
*   **隐蔽逻辑**: 虽然端点名为 `/chunks`，但 POST 请求实际执行的是 **"Start Parsing" (启动解析任务)**。调用成功后，文档状态会从 `UNSTART` (0) 变为 `RUNNING` (1)。

### 3. 自动化验证脚本 (`backend/test/03_e2e_pdf_verify.py`)
我们已实现从“建库”到“问答”的全链路自动化脚本，包含以下关键处理：

1.  **状态轮询 (Polling)**:
    *   触发解析后，轮询 `/api/v1/datasets/{id}/documents`。
    *   **坑**: 状态字段 `run_status` 可能是字符串 "1" 也可能是数字 1，代码需兼容。
    *   **目标状态**: 等待状态变为 `'3'` (DONE) 且 `chunk_count > 0`。

2.  **权限授予**:
    *   若使用非 Admin 账号调用 Chat API，必须先调 `/api/v1/datasets/{id}/collaborations` 授予权限，否则报 403。

3.  **响应结构兼容**:
    *   API 返回的数据结构有时是 `{data: [...]}`，有时是 `{data: {data: [...]}}`，解析时需防御性编程。

### 4. 产出工具
*   **全流程验证**: `python3 backend/test/03_e2e_pdf_verify.py`
*   **状态诊断**: `python3 scripts/check_pdf_status.py` (快速查看解析进度)


## 2026-02-26：Admin端文档管理功能增强与文件查看实现

写入时间：2026-02-26 12:05

## 2026-02-26：Admin端文档管理功能增强与文件查看实现

### 典型现象
1. 知识库管理Tab中，新建知识库无法直接上传文件。
2. 删除知识库后，前端列表未刷新，后端已删除。
3. 知识库列表仅显示"Docs: 0"，不显示实际文件数。
4. 点击知识库进入详情页后，无法查看文件内容（PDF/文本），且未解析的文件缺乏解析触发入口。
5. 上传文件时报 415 Unsupported Media Type 错误。
6. 获取文件内容时报 500 错误（RAGFlow API 路径不明确）。

### 核心判断思路
1. **上传/解析/查看缺失**：AdminController 和 RagFlowClient 缺乏对应的文档操作接口，需对齐 RAGFlow API。
2. **删除状态不同步**：前端删除操作未等待后端完成或未正确处理异步状态，需引入乐观更新 (Optimistic Update) 和延时刷新。
3. **Docs 显示为 0**：列表接口返回的 `doc_count` 字段可能未正确映射或 RAGFlow 返回结构变更。
4. **415 错误**：Spring WebFlux `FilePart` 与 `MultipartFile` 处理方式不同，RAGFlow 客户端需要正确的 Multipart 请求构建。
5. **500 错误**：RAGFlow 文件下载路径非标准 REST 路径，需尝试 `/api/v1/document/file/{dataset_id}/{doc_id}` 或类似路径，并增加容错回退。

### 关键动作与命令
1. **后端增强**：
   - `RagFlowClient.java`: 新增 `runDocuments` (解析), `getDocumentFile` (下载/查看), 修正 `uploadDocument` 使用 `MultipartFile`。
   - `AdminController.java`: 暴露 `/datasets/{id}/documents/run` 和 `/datasets/{id}/documents/{docId}/file` 接口。
   - 修复删除接口参数：RAGFlow 删除接口需要 `ids` 列表，前端传递 `ids`，后端需正确映射。
2. **前端优化**：
   - `DatasetManager`: 实现乐观更新删除逻辑，增加 `setTimeout` 确保后端处理完成后刷新。
   - `DatasetCard`: 显示 `dataset.doc_count` 而非硬编码，改为 "全部文件"。
   - `DatasetDetail`: 新增 "解析" 按钮（调用 `runDocuments`），新增 "查看" 按钮（调用 `getDocumentFile` 并通过 Blob URL 预览）。
   - PDF 预览：使用 `<iframe src={blobUrl} />` 进行简易预览。
3. **验证脚本**：
   - `test_admin_backend.py`: 覆盖创建 -> 上传 -> 列表 -> 解析 -> 查看 -> 删除 全流程验证。

### 根因与解决方案
1. **上传 415**：原 `FilePart` 实现未正确设置 Content-Type，改为 `MultipartFile` 并使用 `MultipartBodyBuilder` 解决。
2. **文件查看失败**：RAGFlow 文件下载路径未公开文档化，经调试确认为 `/api/v1/document/file/{dataset_id}/{doc_id}`（需进一步验证，当前通过 fallback 机制处理）。
3. **删除延迟**：RAGFlow 删除操作可能为异步，前端需增加延时刷新或轮询机制。

### 验收标准
1. Admin 界面可新建知识库并上传文件。
2. 知识库卡片显示正确的文件数量。
3. 进入详情页可点击 "解析" 触发 RAGFlow 解析任务。
4. 点击 "查看" 可在模态框中预览 PDF 或文本内容。
5. 删除知识库后，列表立即移除该项，且刷新后不再出现。
## 2026-02-28: 修复 Xinference 模型加载 Connection Reset 与 RAGFlow GENERIC_ERROR

**问题现象**:
1.  RAGFlow 界面报错 `ERROR: GENERIC_ERROR - An error occurred during streaming`。
2.  后台日志显示 `openai.APIError: An error occurred during streaming`，深层原因为 `ConnectionRefusedError: [Errno 111] Connection refused`。
3.  重启 Xinference 后，加载模型（bge-m3, bge-reranker-v2-m3, deepseek-r1-distill-qwen-14b）时报错 `ConnectionResetError(104, 'Connection reset by peer')`，导致模型无法启动。
4.  Xinference 日志显示尝试连接 HuggingFace (`cas-bridge.xethub.hf.co`) 超时。

**原因分析**:
1.  **GENERIC_ERROR**: RAGFlow 容器无法连接到 Xinference 服务（端口 8085），通常是因为 Xinference 挂了或者模型没加载起来。
2.  **Connection Reset**: Xinference 在启动模型时，默认尝试联网检查或下载模型文件。由于网络环境限制（无法连接 HuggingFace），导致连接被重置或超时，进而导致模型启动失败。即便本地有模型文件，Xinference 默认行为仍可能触发联网检查。

**解决方案**:
1.  **强制离线模式**: 在 `docker-compose-xinference.yml` 中添加环境变量，强制 HuggingFace 相关库使用离线模式，避免联网检查：
    ```yaml
    environment:
      - HF_HUB_OFFLINE=1
      - TRANSFORMERS_OFFLINE=1
    ```
2.  **缓存清理与软链接**:
    *   发现 Xinference 缓存目录 (`/root/.xinference/cache/v2`) 下存在无效的空文件夹或错误结构。
    *   删除无效缓存：`rm -rf /root/.xinference/cache/v2/bge-m3-pytorch-none ...`
    *   (可选但推荐) 手动创建软链接指向挂载的模型目录，确保 Xinference 能找到本地模型：
        `ln -s /models/bge-m3 /root/.xinference/cache/v2/bge-m3-pytorch-none`
3.  **重启服务**: 重建 Xinference 容器以应用环境变量。
4.  **重新加载模型**: 使用 `launch_xinference_models.py` 脚本重新加载模型。

**验证方法**:
1.  在 RAGFlow 容器内运行测试脚本 `test_ragflow_stream_v2.py`，模拟流式对话。
2.  看到 `Stream started. Receiving chunks:` 并输出模型回复，确认为 `Stream finished successfully`。

## 2026-03-02：解决 Xinference DeepSeek-R1-14B 4-bit 量化 OOM 与 Model Family 校验问题

写入时间：2026-03-02 15:08

### 典型现象
1. RAGFlow 调用模型报错 `GENERIC_ERROR - An error occurred during streaming`。
2. Xinference 日志显示 `torch.OutOfMemoryError: CUDA out of memory`，提示尝试分配 1.45 GiB 但显存不足（总 31GB，已用 24GB）。
3. 使用 `xinference launch` 命令行启动时，若指定 `--model-name qwen2.5-instruct` 和 `--model-uid deepseek-r1-distill-qwen-14b`，会报 `Model not found`（因为内置库无此组合）。
4. 自定义注册模型时，若 `model_family` 设为自定义名称（如 `deepseek-r1-distill-qwen`），RAGFlow 可能会因为该 family 不在支持的 tool-call 列表中而报错，或者 Xinference 校验失败。

### 核心判断思路
1. **显存占用异常**：14B 模型 4-bit 量化应占用约 8-9GB 显存。实际占用 22GB+ 说明量化未生效，可能是以 FP16 加载。
2. **注册配置问题**：Xinference v2 注册配置需要严格遵循 `CustomLLMFamilyV2` 规范。
3. **量化参数传递**：仅在 `model_specs` 中设置 `quantization: "4-bit"` 可能不足以触发 `bitsandbytes` 加载，需要在 `launch_model` 时显式传递 `quantization_config`。

### 关键动作与命令
1. **编写注册脚本**：使用 `xinference.client` Python SDK 进行注册和启动，比命令行更灵活。
2. **强制 4-bit 量化**：
   ```python
   client.launch_model(
       ...,
       quantization="4-bit",
       quantization_config={"load_in_4bit": True, "bnb_4bit_compute_dtype": "float16"}
   )
   ```
3. **选择正确的 Model Family**：
   对于 DeepSeek-R1-Distill-Qwen，应继承 `qwen2.5-instruct` family，以获得正确的 prompt template 和 tool calling 支持。
   ```json
   "model_family": "qwen2.5-instruct"
   ```

### 根因与解决方案
- **根因**：
  1. `xinference launch` 默认不包含 bitsandbytes 量化配置，导致模型以 FP16 加载，超出显存。
  2. 自定义模型注册时 `model_family` 设置不当，导致功能受限或校验失败。
- **解决方案**：
  编写 Python 脚本 `register_and_launch_v2.py`，注册时指定 `model_family="qwen2.5-instruct"`，启动时显式传入 `quantization_config={"load_in_4bit": True}`。

### 验收标准
1. `curl http://localhost:8085/v1/models` 返回模型状态，且 `quantization` 为 `4-bit`。
2. `nvidia-smi` 显示模型进程显存占用在合理范围（约 12-13GB for 14B model）。
3. RAGFlow 对话测试（`debug_ragflow_stream.py`）能正常流式输出并包含 reasoning 内容（`<think>` 标签）。

