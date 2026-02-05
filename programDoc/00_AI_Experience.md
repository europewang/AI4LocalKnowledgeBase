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
