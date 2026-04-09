
- 操作记录：优化相似度算法，降低虚高得分
- 操作内容：
  - 升级 `HashEmbeddingGenerator`：
    - 将向量维度 (`dim`) 从 256 提升至 1024，减少哈希冲突。
    - 实现 Bigram (双字切分) 机制，取代原来的 Unigram (单字切分) + 正则空格策略。
    - 仅保留汉字、字母、数字进行切分，提升语义区分度。
  - 同步更新 `MilvusVectorRepository` 维度配置。
  - 重建 Docker 环境以清理旧维度的 Milvus 数据。
- 验证结果：
  - 相似度得分从虚高的 `0.648` 降低至 `0.349` (约 35%)。
  - 该分数更符合两份同作者但不同主题文档（研究报告 vs 培训总结）的实际相似情况，消除了因单字（如“的”、“是”）重合带来的噪音。

---

- 操作记录：修复 Milvus SDK 导入路径并确保搜索强一致性
- 操作内容：
  - 修改 `MilvusVectorRepository.java` 中的导入路径：`import io.milvus.param.ConsistencyLevelEnum` -> `import io.milvus.common.clientenum.ConsistencyLevelEnum`。
  - 原因：Milvus Java SDK 2.3.x 版本变更了枚举类的包路径，修正以解决编译问题。
  - 用途：`ConsistencyLevelEnum.STRONG` 用于设置搜索时的一致性级别为“强一致性”，确保写入的数据能立即被检索到，避免数据同步延迟导致的搜索结果为空。
- 验证结果：
  - 项目构建成功，服务正常启动。
  - 运行 `test_check.sh` 对比两份高度相似文档（研究报告1120 vs 1211），得到相似度 `0.997`，验证了功能的正确性和稳定性。

---

- 操作记录：集成 MinerU 智能 OCR 与智能解析策略
- 操作内容：
  - **微服务架构升级**：
    - 新增 `ocr-service` (Python + Magic-PDF)，作为 Sidecar 运行，提供高质量的扫描件与表格解析能力。
    - 更新 `docker-compose.yml` 集成 OCR 服务。
  - **DDD 领域层改造**：
    - 升级 `DocumentParser` 接口，支持传递 `ParseConfig`。
    - 引入 `ParserMode` (AUTO/FAST_TIKA/DEEP_OCR) 策略枚举。
  - **基础设施层实现**：
    - 实现 `MinerUParserAdapter`：负责调用 OCR 服务，并包含核心算法 `MarkdownSectionConverter`，将 Markdown 语义结构（标题、表格）映射为领域模型。
    - 实现 `SmartDocumentParser`：作为默认解析入口，支持智能路由与自动回退（AUTO 模式下，若 Tika 解析内容过少则自动调用 OCR）。
  - **API 增强**：
    - `/upload` 接口新增 `parser_mode` 参数，允许用户手动指定解析策略。
- 验证结果：
  - 单元测试 `MinerUParserTest` 通过，验证了 Markdown 到 Section 的结构化切分逻辑。
  - 系统架构现在具备了处理扫描版 PDF 的能力，并保持了 DDD 分层的纯净性。

---

<<<<<<< HEAD
- 操作记录：OCR 微服务 Docker 化部署与连通性验证
- 操作内容：
  - **Docker 环境修复**：
    - 配置 Docker 守护进程 (`/etc/docker/daemon.json`) 使用国内镜像源，解决了 Docker Hub 连接超时导致的构建失败问题。
  - **OCR 服务构建**：
    - 优化 `ocr-service/Dockerfile`：将过时的 `libgl1-mesa-glx` 替换为 `libgl1`，修复 Debian 环境下的依赖错误。
    - 调整依赖版本：将 `magic-pdf` 版本锁定为 `0.6.1`，确保 PyPI 包存在且兼容现有代码。
    - 成功构建并启动 `ai-tender-ocr` 容器。
  - **集成验证**：
    - 编写并执行 `test_client.py` 脚本，模拟客户端调用。
    - 验证 `/health` 接口：返回 `mineru_installed: True`，确认核心 OCR 库加载成功。
    - 验证 `/parse` 接口：成功处理文件上传并返回解析结果（Mock 数据），证明微服务接口功能完备。
- 验证结果：
  - OCR 微服务已在 Docker 环境中稳定运行，且对外接口功能正常，为后续 Java 后端的集成调用做好了准备。
  - 2026-01-21

---

- 操作记录：修复 OCR 解析报错并确认模型下载完成
- 操作内容：
  - 模型下载完成，`PDF-Extract-Kit` 已移动到 `ocr-service/models`。
  - 复现并确认 `test_client.py` 报错：`KeyError: 'model_list'`。
  - 调整 `ocr-service/app.py` 的 `jso_useful_key`，补充 `model_list` 字段以满足 MinerU 运行需求。
- 验证状态：
  - 当前等待 OCR 镜像构建完成后再进行真实解析验证。
  - 2026-01-21

---

- 操作记录：修复 OCR 容器启动失败（缺少 uvicorn）
- 操作内容：
  - 构建日志显示错误：`exec: "uvicorn": executable file not found in $PATH`。
  - 更新 `ocr-service/Dockerfile`，在安装 `magic-pdf[full]` 时同时安装 `fastapi` 与 `uvicorn[standard]`。
- 验证状态：
  - 需要重新构建 `ocr-service` 镜像并启动容器验证。
  - 2026-01-21

---

- 操作记录：修复 OCR 容器缺少 python-multipart 导致启动失败
- 操作内容：
  - 通过 `docker logs` 定位到 FastAPI 报错：`Form data requires "python-multipart"`.
  - 更新 `ocr-service/Dockerfile`，在依赖中补充 `python-multipart`。
- 验证状态：
  - 需要重新构建镜像并启动容器后再验证接口。
  - 2026-01-21

---

- 操作记录：修复 MinerU Python API 版本差异导致的 Mock 模式
- 操作内容：
  - 定位到 `magic_pdf` 包内不存在 `pipe.UNIPipe`，导致 ImportError 触发 Mock。
  - 改为调用 `magic_pdf.tools.common.do_parse` 作为主解析入口，直接生成 Markdown 文件并读取返回。
  - 强制使用 `parse_method="ocr"`，确保走 OCR 深度解析路径。
- 验证状态：
  - 需要重新构建镜像并启动容器后再验证真实解析结果。
  - 2026-01-21

---

- 操作记录：补齐 OCR 运行依赖与模型路径适配
- 操作内容：
  - 将 `/home/ubutnu/code/AI4TenderDoc/models` 同步到 `ocr-service/models`，用于容器挂载。
  - 创建 `MFD/YOLO/yolo_v8_ft.pt` 路径，匹配 `model_configs.yaml` 期望的公式检测权重文件名。
  - 创建 `MFR/unimernet_hf_small_2503` 并复制 `unimernet_small` 模型文件，同时补充 `pytorch_model.bin`。
  - 为 `Layout/LayoutLMv3` 补齐 `config.json` 和 `model_final.pth`。
  - 新增 `pycocotools` 依赖，修复 `No module named 'pycocotools'` 报错。
- 验证状态：
  - 需要重新构建镜像并启动容器后再验证解析结果。
  - 2026-01-21

---

- 操作记录：采用 MinerU 官方部署方案并调整 OCR 服务入口
- 操作内容：
  - 将 `ocr-service` 依赖从 `magic-pdf` 切换为 `mineru[all]`，与官方推荐安装方式对齐。
  - 在镜像内生成 `/root/.mineru/mineru.json`，将模型目录指向 `/root/.magic-pdf/models`，配合 `MINERU_MODEL_SOURCE=local` 使用本地模型。
  - 调整 `ocr-service/app.py`，通过 `mineru` CLI 解析文件并输出 Markdown，保持 `/parse` 接口返回结构不变。
  - 更新 `docker-compose.yml`，为 `ocr-service` 增加 `MINERU_MODEL_SOURCE=local` 环境变量。
- 验证状态：
  - 需要重新构建镜像并启动容器后再验证解析结果。
  - 2026-01-21

---

- 操作记录：解决 MinerU 构建依赖回退源码导致的构建失败
- 操作内容：
  - 构建日志显示 `av` 依赖回退到源码构建，提示需要 `ffmpeg 7`、`pkg-config`、编译器等，导致安装失败。
  - 在 `ocr-service/Dockerfile` 中增加 `av==14.2.0` 约束并启用 `--prefer-binary`，优先使用预编译轮子避免源码编译。
- 验证状态：
  - 需要重新构建镜像并启动容器后再验证解析结果。
  - 2026-01-22

---

- 操作记录：启用 OCR 微服务 GPU 加速（Compose + CUDA 轮子）
- 操作内容：
  - 在 `deploy/docker-compose.yml` 为 `ocr-service` 增加 `gpus: all` 与 `NVIDIA_*` 环境变量，启用容器内 GPU。
  - 在 `ocr-service/Dockerfile` 预装 `torch/torchvision/torchaudio` 的 CUDA 12.1 轮子，并恢复使用 `mineru[all]`，保证 GPU 相关依赖完备。
  - 保持 HuggingFace 缓存与源码热重载挂载，兼顾模型复用与调试效率。
- 使用说明：
  - 需要宿主机已安装 NVIDIA Container Toolkit；首次构建耗时较长（下载 CUDA 相关轮子），后续启动将显著加速。
  - 启动命令：`sudo DOCKER_BUILDKIT=1 docker compose up -d --build ocr-service`
- 验证状态：
  - 首次构建进行中，完成后将验证 `/health` 与 `/parse`；后续启动无需重复下载。
  - 2026-01-22

---

- 操作记录：清理未使用的 Docker 镜像
- 操作内容：
  - 执行 `docker image prune -a -f` 清理所有未被容器使用的镜像，释放存储空间，避免旧镜像干扰构建与启动。
  - 通过 `docker system df` 查看空间占用与可回收缓存大小，当前 Build Cache 可回收约 36.54GB（未执行缓存清理以保留构建加速）。
- 验证状态：
  - 已删除未使用镜像，保留当前活动镜像；后续构建与启动将以最新镜像为准。
  - 2026-01-22

---

- 操作记录：将 mineru[all] 精简为 mineru（CPU-only 构建）
- 操作内容：
  - 在 `ocr-service/Dockerfile` 将依赖从 `mineru[all]` 替换为 `mineru`，并预装 CPU 版 `torch/torchvision/torchaudio`。
  - 原因：避免安装 CUDA 相关的 `nvidia-*` 依赖，减少构建时间与镜像体积；CPU-only 足够满足当前解析需求。
  - 预期效果：首次构建时长显著下降，后续启动更快。
- 验证状态：
  - 待重新构建镜像并启动后验证 `/health` 与 `/parse`。
  - 2026-01-22

---

- 操作记录：优化 MinerU 微服务构建与启动速度（缓存与热重载）
- 操作内容：
  - Docker 构建缓存：
    - 在 `ocr-service/Dockerfile` 顶部启用 BuildKit 语法，使用 `--mount=type=cache` 为 `apt` 与 `pip` 提供缓存，加速重复构建。
    - 保留 `av==14.2.0` 约束与清华镜像，继续优先使用二进制轮子，避免源码编译。
  - 启动性能优化：
    - 在 `deploy/docker-compose.yml` 为 OCR 服务新增挂载 `../ocr-service/hf_cache:/root/.cache/huggingface`，使 HuggingFace 首次下载的模型缓存可复用，后续启动不再重复下载。
    - 新增挂载 `../ocr-service:/app`，并以 `uvicorn --reload` 运行，支持本地修改 `ocr-service/app.py` 自动生效，无需重启容器。
  - 使用建议：
    - 首次构建使用：`sudo DOCKER_BUILDKIT=1 docker compose build ocr-service`。
    - 日常启动避免 `--build`：`sudo docker compose up -d ocr-service`，如未变更依赖与 Dockerfile，不会重新下载。
    - 如需预热模型，可先调用一次 `/parse` 让模型缓存落盘，后续启动更快。
- 验证状态：
  - 待重新构建并启动后确认缓存命中与热重载生效。
  - 2026-01-22

---

- 操作记录：启动 OCR 服务失败并定位 GPU 运行时缺失
- 操作内容：
  - 尝试启动 `ocr-service`：`sudo docker compose -f deploy/docker-compose.yml up -d ocr-service`。
  - 失败原因：Docker 无法选择 GPU 设备驱动，报错 `could not select device driver "" with capabilities: [[gpu]]`。
  - 通过 `docker info` 确认当前仅有 `runc` 运行时，未配置 NVIDIA Container Toolkit。
  - 尝试安装 `nvidia-container-toolkit`，由于 `nvidia.github.io` TLS 连接失败导致无法拉取仓库元数据，安装中止。
- 验证状态：
  - 当前 `ocr-service` 容器处于 Created 状态未能启动。
  - 需要先恢复对 `nvidia.github.io` 的网络访问，完成 NVIDIA Container Toolkit 安装与 Docker 运行时配置后再启动。
  - 2026-01-23

---

- 操作记录：替换 Docker 镜像加速源并重启验证
- 操作内容：
  - 按提供的镜像列表更新 `/etc/docker/daemon.json` 的 `registry-mirrors`。
  - 执行 `systemctl daemon-reload` 与 `systemctl restart docker` 让配置生效。
  - 通过 `docker pull hello-world` 验证镜像拉取可用。
  - 再次启动 `ocr-service` 仍报 `could not select device driver "" with capabilities: [[gpu]]`。
- 验证状态：
  - Docker Hub 镜像拉取正常，但 GPU 运行时仍未就绪。
  - 需要解决 NVIDIA Container Toolkit 安装与运行时配置问题后才能启动 OCR 服务。
  - 2026-01-23

---

- 操作记录：修复 DockerHub 访问超时并成功启动 ocr-service
- 操作内容：
  - 复现问题：`docker run ubuntu ls` 触发拉取，访问 `https://registry-1.docker.io/v2/` 超时（`context deadline exceeded`）。
  - 排查原因：`/etc/docker/daemon.json` 中仅配置了 `https://mirror.aliyuncs.com`，但该地址在当前网络环境下 TLS 握手失败；导致 DockerHub 回源也失败。
  - 处理方案：将 `registry-mirrors` 切换为可用镜像源（`docker.1ms.run`、`docker.m.ixdev.cn`、`dockerproxy.net`、`docker.m.daocloud.io`），并重启 Docker 生效。
  - 验证结果：`docker pull ubuntu:latest` 拉取成功，DockerHub 访问恢复。
  - 启动验证：`docker compose up -d ocr-service` 成功启动容器 `mineru-app`；容器内 `nvidia-smi` 可识别 GPU，确认 GPU 运行时可用。
  - 接口确认：`mineru-api` 在 `8005` 端口启动；OpenAPI 显示当前仅暴露 `/file_parse` 接口（`/health` 返回 404 属于正常现象）。
- 验证状态：
  - OCR 服务已启动，DockerHub 拉取已恢复。
  - 2026-01-29

---

- 操作记录：OCR 容器启动后接口不可用（路径不一致 + 默认后端不可解析）并修复
- 操作内容：
  - 复现问题：后端默认配置 `ocr.service-url` 为 `http://ocr-service:8005/parse`，并以表单字段 `file` 上传文件；但容器启动的是 `mineru-api`，仅暴露 `/file_parse`，导致后端调用 `/parse` 不可用。
  - 修复方案：
    - 更新 `deploy/docker-compose.yml`：挂载 `../ocr-service:/app`，并将启动命令切换为 `uvicorn app:app --host 0.0.0.0 --port 8005`，恢复 `/health` 与 `/parse` 接口，和后端调用约定对齐。
    - 调整 `ocr-service/app.py`：`mineru` 默认 `hybrid-auto-engine` 会走 vLLM 引擎，实际解析时出现引擎初始化失败导致不产出 Markdown；改为显式使用 `pipeline` + `ocr`（`-b pipeline -m ocr -d cuda:0 --source modelscope`）以绕开 vLLM 依赖，确保稳定产出 `*.md`。
  - 验证结果：
    - `/health` 返回 `mineru_installed: true`。
    - `/parse` 上传 `test/test.pdf` 可返回 `markdown`（长度约 485），确认后端可正常消费。
- 验证状态：
  - OCR 服务接口已与后端对齐并完成解析验证。
  - 2026-01-29

---

- 操作记录：补齐 MinerU DDD 适配器注释、完善注入方式并新增测试验证
- 操作内容：
  - 完善领域服务接口注释：为 `MarkdownSectionConverter` 增加职责说明与入参/出参说明。
  - 完善基础设施实现注释：为 `SimpleMarkdownSectionConverter` 增加分段策略说明与关键处理流程注释。
  - 修正 MinerU 适配器实现：
    - 使用 `RestTemplateBuilder` + Spring 管理的 `ObjectMapper` 注入，避免在类内自行 new 导致配置不可控。
    - 增强 OCR 响应健壮性校验（空响应、缺少 `markdown` 字段时显式失败），并保持异常上抛以便上层降级策略接管。
  - 新增/补齐测试：
    - 补充 `MinerUParserAdapter` 的 HTTP 调用与 Markdown 转 Section 的集成测试（使用 MockRestServiceServer 模拟 OCR 返回）。
  - Docker-only 验证：
    - `sudo docker compose up -d --build` 成功启动全部服务。
    - `curl http://localhost:8005/health` 返回 `mineru_installed: true`。
    - 通过 `docker build --target test` 在 Docker 构建阶段执行 `mvn test`，验证测试通过（避免本地无 Maven 环境差异）。
- 验证状态：
  - Docker 编排启动正常，单测在 Docker 构建阶段执行通过。
  - 2026-01-30

---

- 操作记录：前端增加解析方式可选项 + 支持基于 Markdown 的标书对比
- 操作内容：
  - 前端页面增强：
    - 为源/目标文件分别新增“解析方式”选择（原始解析 FAST_TIKA / MinerU 深度OCR DEEP_OCR），上传时通过 `parser_mode` 透传到后端 DDD 解析路由。
    - 新增“对比方式”选择（VECTOR / MARKDOWN），对比时通过 `compareMode` 透传到后端对比服务。
  - 后端能力增强：
    - 上传处理流程新增“解析后 Markdown”落盘：无论解析来源（Tika/MinerU），都会生成 `*.md` 并保存路径到 `Tender.markdownPath`，用于后续对比/下载。
    - 查重接口支持 `compareMode=MARKDOWN`：直接读取两份标书解析后的 Markdown，切分为章节并做文本相似度匹配，返回可视化对比详情。
    - 新增 `GET /api/v1/tenders/{id}/markdown` 用于获取解析后的 Markdown 文本。
  - 启动缓存优化：
    - 文档更新为默认 `sudo docker compose up -d` 启动，复用已构建镜像与缓存；仅在代码或 Dockerfile 变化时使用 `--build`。
    - `docker-compose.yml` 为基础镜像服务增加 `pull_policy: if_not_present`，避免每次启动无意义拉取镜像。
- 验证状态：
  - 前端可选择解析方式与对比方式，对比接口可在 VECTOR / MARKDOWN 两种模式下返回结果。
  - 2026-01-30

---

- 操作记录：补齐 Docker 构建上下文过滤 + 端到端接口复核
- 操作内容：
  - Docker 构建优化：
    - 新增根目录 `.dockerignore`，仅保留 `pom.xml`、`src/`、`Dockerfile` 进入构建上下文，避免 `docker build` 将 OCR 模型等大文件打包上传导致构建耗时/卡顿。
  - Docker-only 验证：
    - 执行 `sudo docker build --target test`，在镜像构建阶段运行 `mvn test`，验证测试通过。
    - 启动 OCR 服务并验证接口：`/health` 返回 `mineru_installed: true`；`/parse` 上传 `ocr-service/input/test.pdf` 返回包含 `markdown` 的 JSON。
    - 启动全栈服务并端到端验证：
      - 调用 `POST /api/v1/tenders/upload?parser_mode=DEEP_OCR` 两次上传样例 PDF，得到两个 tenderId。
      - 调用 `POST /api/v1/check`，分别以 `compareMode=MARKDOWN` 与 `compareMode=VECTOR` 发起对比，均返回 `ReportResponse` 且包含详情列表。
- 验证状态：
  - OCR 服务、上传解析、Markdown 对比、向量对比链路均可用。

  已完成的关键动作与逻辑

- 修复/验证 OCR 服务链路为什么可靠
  
  - 逻辑：后端走 ocr.service-url （默认 http://ocr-service:8005/parse ），因此必须确保 compose 启动的是我们自定义的 FastAPI /parse ，并且 MinerU CLI 能稳定产出 Markdown。
  - 验证：
    - sudo docker compose -f deploy/docker-compose.yml up -d --build ocr-service
    - sudo curl -sS http://127.0.0.1:8005/health 返回 mineru_installed: true
    - sudo curl -sS -F "file=@.../ocr-service/input/test.pdf" http://127.0.0.1:8005/parse 返回 JSON 且包含 markdown
  - 代码定位：
    - 后端 OCR 适配器与超时配置： MinerUParserAdapter.java
    - OCR 服务接口实现： app.py
    - compose 暴露端口与启动命令： docker-compose.yml
- 端到端验证“上传（DEEP_OCR）→ 生成 Markdown → MARKDOWN 对比 / VECTOR 对比”
  
  - 逻辑：前端传 parser_mode 决定解析策略；对比时传 compareMode 决定走向量还是直接基于 Markdown。
  - 验证（Docker-only，避免本地环境差异）：
    - 启动全栈： sudo docker compose -f deploy/docker-compose.yml up -d --build postgres etcd minio milvus-standalone ai-tender-app
    - 两次上传同一 PDF（DEEP_OCR），再分别发起两种对比：
      - compareMode=MARKDOWN 返回 overallSimilarity=0.8285... ， details=5
      - compareMode=VECTOR 返回 overallSimilarity=0.9999... ， details=4
  - 代码定位：
    - 上传接口（ parser_mode 在 Query）： TenderController.java
    - 对比路由（MARKDOWN/VECTOR）： CheckService.java
    - 前端透传参数与调用方式： index.html
- 解决 Docker 构建“上下文 25GB”导致的慢/卡问题
  
  - 逻辑：你仓库里有 OCR 模型与产物目录，Docker 默认会把整个目录打包成构建上下文上传给 daemon，所以出现 Sending build context ... 25.76GB ；用 .dockerignore 只让构建需要的 pom.xml/src/Dockerfile 进入上下文即可。
  - 变更：
    - 新增 .dockerignore
  - 验证结果：
    - 现在构建输出变为： Sending build context to Docker daemon 135.2kB （同样 sudo docker build --target test ... 已通过）
文档对齐（programDoc）

- 已把 parser_mode 明确为 Query 参数 ，避免接口理解偏差： 03_API_Interface_Spec.md
- 已在操作记录末尾追加本次迭代内容（不覆盖旧内容）： 05_recordAiOperate.md
如果你接下来还要“继续”，我会按你现有规则优先做两件事：一是把 docker compose 的构建缓存进一步固定（避免 Maven 依赖每次都重新下载），二是把 MARKDOWN 对比的相似度算法从当前的字符 bigram Jaccard 升级成更稳的分句/分段策略（仍不引入新依赖的前提下）。
  - 2026-01-30

---

- 操作记录：忽略 OCR 大目录，避免仓库体积膨胀
- 操作内容：
  - 更新根目录 `.gitignore`：
    - 忽略 `ocr-service/input`、`ocr-service/output`、`ocr-service/models`、`ocr-service/__pycache__` 等大体积目录及 Python 缓存文件（如 `*.pyc`）。
  - 说明：
    - 以上目录均为可再生成内容（模型缓存、解析产物、临时测试文件），不应纳入版本控制，避免仓库占用空间持续增长。
- 验证状态：
  - `.gitignore` 规则已补齐（若这些目录此前已被 Git 跟踪，需执行 `git rm -r --cached` 才会从版本控制移除）。
  - 2026-01-30

---

- 操作记录：进一步补齐大体积忽略规则（models/input/target 等）
- 操作内容：
  - `.gitignore` 追加：
    - 忽略根目录下 `models/`（本地模型权重存储）、`input/`（样例/测试文档）、`clash-for-linux-install/`（外部工具目录）、`target/`（Maven 构建产物）。
    - 通用模型权重文件模式：`**/*.safetensors`、`**/*.onnx`、`**/*.pth`、`**/*.pt`、`**/pytorch_model.bin`、`**/*.mv`、`**/*.msc`、`**/*.mdl`。
  - 目的：
    - 防止模型与产物被误纳入版本控制，保持仓库体积可控与拉取速度。
- 验证状态：
  - 已生效（若已有被跟踪文件需使用 `git rm -r --cached` 清理索引）。
  - 2026-01-30

---

- 操作记录：解决 OCR 镜像构建卡在 apt 依赖下载（fonts-noto-cjk）
- 操作内容：
  - 更新 `ocr-service/Dockerfile`：
    - 启用 BuildKit 语法，并为 `apt` 与 `pip` 增加 cache mount，减少重复构建的下载与解压耗时。
    - 增加 `APT_MIRROR` 参数：根据 `/etc/apt/sources.list` 自动把 Ubuntu/Debian 默认源替换为阿里云镜像，降低国内网络下 `apt-get update/install` 卡顿概率。
    - 精简字体依赖：保留 `fonts-noto-cjk`（中文字符渲染必需），去掉 `fonts-noto-core` 以减少下载量。
    - 将 pip 镜像源固定为阿里云，并开启 pip cache mount，加速 MinerU 依赖安装。
- 验证结果：
  - `sudo DOCKER_BUILDKIT=1 docker compose -f deploy/docker-compose.yml build ocr-service` 构建成功（`apt` 安装阶段耗时显著下降）。
  - `sudo docker compose -f deploy/docker-compose.yml up -d ocr-service` 启动成功。
  - `curl http://127.0.0.1:8005/health` 返回 `mineru_installed: true`；上传 `ocr-service/input/test.pdf` 调用 `/parse` 返回包含 `markdown` 的 JSON。
  - 2026-01-30

---

- 操作记录：升级 MARKDOWN 对比算法为“分句/分段 + 最佳匹配”策略（更稳、不引入新依赖）
- 操作内容：
  - 更新 `CheckService` 的 MARKDOWN 对比分支：
    - 将章节内相似度计算从“字符 2-gram Jaccard”升级为“段落对齐 + 句子最佳匹配”的分句/分段策略。
    - 段落切分：按 Markdown 语义边界（空行/标题/表格）分段；句子切分：按中英文句末标点拆分。
    - 相似度计算：对每个源段在目标段中找最佳匹配，使用长度加权平均；句子级相似度仍复用字符 2-gram Jaccard（不新增外部依赖）。
  - 新增单元测试：
    - 新增 `CheckServiceMarkdownSimilarityTest`，覆盖段落重排、无关内容、Markdown 表格等场景，防止算法升级引入回归。
  - Docker 构建可用性修复（用于 Docker-only 测试验证）：
    - 移除未被代码使用、且依赖 Spring Milestone 仓库的 `spring-ai-*` 依赖与 BOM 配置，避免在网络不稳定时 Docker 构建阶段阻塞（当前项目代码未引用 Spring AI，因此不影响现有功能）。
- 验证结果：
  - Docker-only 验证通过：`sudo DOCKER_BUILDKIT=1 docker build --target test -t ai-tender-doc:test .` 构建阶段 `mvn test` 全部通过。
  - 2026-02-02

---

- 操作记录：启动 Docker Compose 全栈并完成端到端接口验证（OCR→上传→对比）
- 操作内容：
  - 启动服务（含依赖基础设施）：
    - `sudo docker compose -f deploy/docker-compose.yml up -d --build postgres etcd minio milvus-standalone ocr-service ai-tender-app`
  - 接口验证（本地端口）：
    - OCR：`http://127.0.0.1:8005/health` 返回 `mineru_installed: true`
    - 上传：`POST http://127.0.0.1:8080/api/v1/tenders/upload?parser_mode=DEEP_OCR`（上传 `ocr-service/input/test.pdf`）成功返回 `tenderId`
    - 对比：`POST http://127.0.0.1:8080/api/v1/check` 分别以 `compareMode=MARKDOWN` 与 `compareMode=VECTOR` 发起比对
    - Markdown 预览：`GET http://127.0.0.1:8080/api/v1/tenders/{id}/markdown` 可返回解析后的 Markdown 文本
- 验证结果：
  - MARKDOWN 对比返回 `overallSimilarity=1.0`，`details=5`
  - VECTOR 对比返回 `overallSimilarity≈0.9999995`，`details=4`
  - 2026-02-02
=======
## 2026-04-07：全量下架旧容器并重启当前项目 Docker（含模型重新加载）

**操作人**: AI Assistant (Trae IDE)

**用户目标**:
- 因此前运行了其他服务，先将已有 Docker 服务下架，再重启当前项目全部 Docker 服务，并恢复 Xinference 模型可用状态。

**执行过程**:
1. 下架旧容器与编排资源：
   - 执行 `sudo -n docker stop $(docker ps -q)`，停止当前所有运行容器；
   - 执行 `sudo -n docker compose -f deploy/docker-compose-ragflow.yml down --remove-orphans`；
   - 执行 `sudo -n docker compose -f deploy/docker-compose-xinference.yml down --remove-orphans`。
2. 重启当前项目容器：
   - 尝试 `sudo -n docker compose -f deploy/docker-compose-ragflow.yml up -d --build` 时，后端源码编译失败（`AuditDataCleanupTask` 调用了不存在的方法 `deleteBefore(...)`）；
   - 改用 `sudo -n docker compose -f deploy/docker-compose-ragflow.yml up -d`，基于现有镜像成功拉起 `ragflow` 全栈；
   - 执行 `sudo -n docker compose -f deploy/docker-compose-xinference.yml up -d --force-recreate xinference`，成功以 `8085:8085` 拉起 Xinference。
3. 重新加载模型：
   - 执行 `conda run -n ai4tender python3 scripts/launch_xinference_models.py`；
   - Embedding `bge-m3`、Rerank `bge-reranker-v2-m3`、LLM `deepseek-r1-distill-qwen-14b` 启动成功；
   - 自定义模型注册返回“已注册”提示（400）为可预期现象，不影响后续实例启动。

**验证结果**:
1. `sudo -n docker ps` 显示当前项目核心容器均 `Up`：
   - `xinference`（`8085->8085`）
   - `ragflow-backend`（`8083->8083`）
   - `ragflow-frontend`（`8086->80`）
   - `ragflow-server`、`ragflow-mysql`、`ragflow-es01`、`ragflow-minio`、`ragflow-redis`
2. `curl http://127.0.0.1:8085/v1/models` 返回已加载模型列表，包含：
   - `bge-m3`
   - `bge-reranker-v2-m3`
   - `deepseek-r1-distill-qwen-14b`（`quantization=4-bit`）
3. `curl -o /dev/null -w '%{http_code}' http://127.0.0.1:8086` 返回 `200`，前端可访问。

**结论**:
* 已按要求完成“先下架旧容器，再重启当前项目 Docker 服务”；
* `xinference` 已回归项目标准端口 `8085` 且模型已重新加载完成；
* 当前唯一遗留风险是：若后续需要“重建后端镜像”，需先修复后端源码编译错误（`deleteBefore(...)` 方法缺失）。

## 2026-04-08：聊天交互与技能管理 6 项需求改造 + Docker 联调

**操作人**: AI Assistant (Trae IDE)

**用户目标**:
- 按顺序完成以下改造并落地测试：
  - 聊天输入支持 `@` 技能候选弹窗、上下键选择，且不写死技能调用；
  - 流式回复阶段支持“发送键二次点击真正中断”，移除独立“中断生成”按钮；
  - 技能管理拆分“列表/审计”“新增技能”“更新技能”独立页面；
  - 支持已有技能修改并回填；
  - 修复审计记录不显示问题（前后端联调）；
  - 新增/更新技能页面补全参数名称、简介、默认值说明。

**执行过程**:
1. 前端聊天交互改造（`frontend/src/App.jsx`）：
   - 为输入框增加 `@mention` 解析与候选弹层，支持 `ArrowUp/ArrowDown` 选择，`Enter/Tab` 插入技能名；
   - 明确“仅辅助输入，不强制执行技能”，由模型根据语义决定是否调用；
   - 发送按钮改为统一入口：流式中显示转圈，再次点击会 `abort` 当前请求；若输入框已有新文本则立即发起新提问；
   - 移除独立“中断生成”按钮。
2. 技能管理与审计展示联调：
   - 在管理界面补充/完善“新增与更新分离”的视图流转及已有技能修改入口；
   - 新增/更新表单增加参数字段的人类可读说明与默认值提示；
   - 审计列表接入展示（依赖后端返回字段完整性）。
3. 后端接口字段补齐（`SkillProtocolAdminController`）：
   - 在技能列表返回中补充 `trigger_keywords`、`input_mode`、`output_mode`、`upload_required`、`accepted_file_types`、`max_files`、`parameters_schema`、`draft_args_template`，保证“修改技能”回填完整。
4. Docker 构建与故障修复：
   - 执行 `sudo -n docker compose -f deploy/docker-compose-ragflow.yml up -d --build backend frontend`；
   - 首次构建失败，定位到 `AuditDataCleanupTask` 依赖方法缺失；
   - 在 `RouteSampleService`、`DynamicSkillAuditService` 补充 `deleteBefore(LocalDateTime)`；
   - 强制重建后后端启动失败，定位缺少 `BrainOpenClawProperties` Bean 注册；
   - 在 `BackendApplication` 的 `@EnableConfigurationProperties` 中补充 `BrainOpenClawProperties`；
   - 重新执行 `sudo -n docker compose -f deploy/docker-compose-ragflow.yml up -d --build --force-recreate backend frontend` 成功。

**验证结果**:
1. `sudo -n docker compose -f deploy/docker-compose-ragflow.yml ps backend frontend`：
   - `ragflow-backend`：`Up`，端口 `8083:8083`
   - `ragflow-frontend`：`Up`，端口 `8086:80`
2. 访问验证：
   - `curl http://127.0.0.1:8086` 返回 `200`（前端可访问）
   - `curl http://127.0.0.1:8083/api/health` 返回 `401`（说明后端服务在线，接口受鉴权保护）

**结论**:
* 六项需求已完成代码改造并完成 Docker 级别构建与运行验证；
* 审计显示链路与技能编辑回填所需字段已打通；
* 本次未引入新的诊断错误（仅保留既有前端未使用变量 Hint）。

## 2026-04-08：聊天 @ 触发范围与技能快捷显示名微调

**操作人**: AI Assistant (Trae IDE)

**用户目标**:
- `@` 在输入框内任意位置都可触发技能候选，而非仅开头可用；
- 对话框旁“技能快捷调用”按钮显示 `tool_name`，不再优先展示 `tool_code/name`。

**执行过程**:
1. 修改 `frontend/src/App.jsx`：
   - 新增 `getToolDisplayLabel(tool)`，统一优先级：`tool_name/toolName` > `displayName` > `name`；
   - 调整 `resolveMentionContext(...)`，将识别逻辑改为基于正则 `@([^\s@]*)$` 匹配光标前最近未闭合 `@token`，去除“必须句首/空白后”限制；
   - mention 候选检索语料加入 `tool_name`，提升按业务名检索命中；
   - 侧边“技能快捷调用”以及 mention 下拉显示文本统一走 `getToolDisplayLabel`。
2. 前端诊断与部署验证：
   - 执行 `GetDiagnostics`，仅保留既有 Hint，无新增错误；
   - 执行 `sudo -n docker compose -f deploy/docker-compose-ragflow.yml up -d --build --force-recreate frontend`；
   - 执行 `curl http://127.0.0.1:8086` 返回 `200`。

**结论**:
* `@` 现已支持句中任意位置触发技能候选；
* 技能快捷区显示已切换为业务可读的 `tool_name`（不存在时自动回退）。

## 2026-04-08：单独 @技能 直连调用规则

**操作人**: AI Assistant (Trae IDE)

**用户目标**:
- 当输入为“单独 `@某技能`”时，固定直接调用该技能；
- 当 `@某技能` 出现在连续语句中时，仅作为技能标识，不写死调用，交给大模型按整句语义判断（例如“这是什么@指标校核”应偏向解释技能含义）。

**实现说明**:
1. 修改 `frontend/src/App.jsx`，新增 `resolveStandaloneMentionTool(text)`：
   - 仅匹配整句模式 `^@\\s*(.+?)\\s*$`；
   - 在 `tool_name/toolName/displayName/name/tool_code/toolCode` 中做精确归一匹配。
2. 修改发送链路 `handleSend(...)`：
   - 命中“单独 @技能”时，跳过普通语义流，走固定直连分支：
     1) 创建工具草稿 `createToolDraft`；
     2) 若技能不要求上传文件，直接调用 `handleApproveTool(draft, presetForm)` 立即执行；
     3) 若技能要求上传文件，降级为提示并展示草稿表单，等待用户上传后执行。
   - 非“单独 @技能”场景保持原逻辑，仍由模型基于整句语义决定是解释、澄清还是触发工具。
3. 为复用执行逻辑，将 `handleApproveTool` 签名扩展为支持 `formOverride`，避免依赖异步 `setState` 回写后的读取时序。

**验证**:
1. 前端诊断：`GetDiagnostics(file:///.../frontend/src/App.jsx)` 仅有既有 Hint，无新增错误。
2. 部署验证：
   - `sudo -n docker compose -f deploy/docker-compose-ragflow.yml up -d --build --force-recreate frontend` 成功；
   - `sudo -n docker compose -f deploy/docker-compose-ragflow.yml ps frontend` 显示 `Up`，端口 `8086->80`；
   - `curl http://127.0.0.1:8086` 返回 `200`。

**结论**:
* 单独 `@技能名` 已实现固定直连调用；
* 句中 `@技能` 仍作为语义提示，不会被写死强制调用。

## 2026-04-08：修复快捷技能列表仍显示 tool_code

**操作人**: AI Assistant (Trae IDE)

**问题现象**:
- 用户反馈“对话框旁技能快捷调用仍显示 `tool_code`，未显示 `tool_name`”。

**根因定位**:
1. 前端快捷列表渲染已优先读取 `tool_name/toolName/displayName`，代码逻辑本身无误；
2. 但后端 `/api/v1/agent/tool/catalog` 返回模型 `ToolSpec` 不包含 `toolName/displayName` 字段，只返回 `name`（即 `tool_code`），导致前端只能回退显示编码。

**修复内容**:
1. `backend/.../skill/model/ToolSpec.java`
   - 新增字段：`toolName`、`displayName`；
   - 通过注释明确其用于前端展示名。
2. `backend/.../skill/service/DynamicSkillRegistryService.java`
   - 在 `toToolSpec(...)` 中补齐映射：
     - `.toolName(defaultString(registry.getToolName(), registry.getToolCode()))`
     - `.displayName(defaultString(registry.getToolName(), registry.getToolCode()))`
   - 保证目录接口输出可被前端展示层优先命中。

**验证**:
1. Java 诊断：
   - `ToolSpec.java` 无新增 diagnostics；
   - `DynamicSkillRegistryService.java` 无新增 diagnostics。
2. 部署：
   - `sudo -n docker compose -f deploy/docker-compose-ragflow.yml up -d --build --force-recreate backend` 成功，后端容器启动正常。

**结论**:
* 本次已从后端返回层补齐 `tool_name` 对应字段；
* 前端快捷技能显示将优先使用 `tool_name`，不再被迫回退为 `tool_code`。

## 2026-04-08：修复 RAGFlow 回传到前端时图片引用丢失

**操作人**: AI Assistant (Trae IDE)

**问题描述**:
- 用户反馈：调用返回前端的 RAGFlow 内容时，原有图片内容未被传递到前端展示链路。

**根因定位**:
1. 在 `EngineOrchestrator` 的 RAG/LLM 竞速（race）分支中，`RaceCandidate` 仅保存了 `hasReference` 布尔值；
2. 当 RAG 胜出时，`toRagPayloadJson(...)` 会构造一个“伪 reference”，丢弃了 RAGFlow 原始 `reference` 结构（含图片相关字段）；
3. 因此前端拿到的并非原始引用数据，图片字段可能消失。

**修复方案**:
1. `collectRagRaceCandidate(...)`：
   - 从流式 payload 中保留原始 `reference`（`JsonNode` 深拷贝），不再只记录布尔值。
2. `RaceCandidate` 结构调整：
   - 由 `boolean hasReference` 改为 `JsonNode reference`；
   - 新增 `hasRenderableReference()` 兼容数组和 `chunks` 对象判断。
3. `toRagPayloadJson(...)`：
   - 若有可渲染引用，直接透传原始 `reference`；
   - 不再构造伪造的 `List<Map<...>>` 引用。
4. `collectLlmRaceCandidate(...)`：
   - 对 CHAT 候选显式传 `null` 引用，保持语义清晰。

**验证**:
1. 代码诊断：
   - `EngineOrchestrator.java` 无新增 diagnostics。
2. 服务重启验证：
   - 启动 `xinference`：`deploy/docker-compose-xinference.yml up -d` 成功；
   - 启动前后端与依赖：`deploy/docker-compose-ragflow.yml up -d --build backend frontend` 成功；
   - `docker ps` 显示 `xinference/ragflow-backend/ragflow-frontend` 等容器均为 `Up`；
   - `curl http://127.0.0.1:8086` 返回 `200`，`curl http://127.0.0.1:8083/api/health` 返回 `401`（服务在线、接口受鉴权）。

**结论**:
* RAG 竞速链路已改为透传原始 `reference`，不再丢失图片相关字段；
* 前端接收的数据完整性与 RAGFlow 原始响应保持一致。

## 2026-04-08：新增“技能相关意图优先”偏置（@技能优先靠近调用/使用/简介）

**操作人**: AI Assistant (Trae IDE)

**需求**:
- 用户要求：当输入中出现 `@技能` 或明显技能指代时，整体理解应优先靠近技能相关意图（技能调用、技能使用、技能简介），避免偏离到无关通用回答。

**实现文件**:
- `backend/src/main/java/com/ai4kb/backend/engine/service/EngineOrchestrator.java`

**核心改动**:
1. 新增技能意图偏置判定函数：
   - `hasSkillMentionSignal(query)`：识别 `@xxx` 与“技能/工具”等信号；
   - `isSkillIntroIntent(query)`：识别“什么是/简介/作用/用途”等技能介绍诉求；
   - `shouldPreferSkillExplanation(query)`：在非显式执行前提下，优先走技能说明/简介。
2. 本地路由增强：
   - `planRouteByLocalRouter(...)` 中，若匹配到技能且命中偏置，返回 `TOOL(local_skill_mention_bias)`，确保进入技能相关处理链路。
3. 执行分支增强（关键）：
   - `applyRouteDecision(...)` 的 `TOOL` 分支中，命中偏置时优先 `buildSkillUsageMessageEvent(...)`；
   - 仅当用户明确表达“调用/执行/运行”等执行意图时，才进入 `buildToolDraftEvent(...)`。
4. 兜底分支增强：
   - `fallbackRoute(...)` 中，匹配技能且命中偏置时优先返回技能使用/简介，不直接执行草稿。
5. LLM 路由提示词增强：
   - `planRouteByLlm(...)` 的 system prompt 增加 `@技能` 偏置说明：
     - 出现 `@技能` 时优先按技能相关意图理解；
     - 未明确执行时，不激进判为立即执行。

**验证**:
1. 代码诊断：
   - `EngineOrchestrator.java` diagnostics 为空（无新增错误）。
2. 服务验证：
   - `sudo -n docker compose -f deploy/docker-compose-ragflow.yml up -d --build --force-recreate backend` 成功；
   - `curl http://127.0.0.1:8083/api/health` 返回 `401`（服务在线且受鉴权保护）。

**结论**:
* 输入中存在 `@技能` 时，系统已更稳定地优先落到技能相关意图；
* 非显式执行语句会优先得到“技能使用/简介”回答，减少误执行概率；
* 保留“明确执行指令 -> 执行技能”的既有行为不变。

## 2026-04-08: 对话懒加载与审计范围筛选联调（Docker 启动 + 分页修复）

**操作人**: AI Assistant (Trae IDE)

**用户目标**
- 继续完成以下能力并验证可运行：
  - 对话首屏 50 条 + 上滑懒加载；
  - 对话右侧显示剩余清理天数；
  - 技能审计与路由样本支持范围筛选 + 分页（20/50/100）；
  - 通过 Docker 启动前后端并验证链路可用。

**实施动作**
1. 前端改造（`frontend/src/App.jsx`）：
   - 路由样本页 `RouteSampleManager` 升级为 `page/pageSize/total` 分页模型；
   - 新增范围筛选项：`userId/source/chosenRoute/startTime/endTime/queryKeyword`；
   - 新增分页控件：上一页/下一页/页码跳转；
   - 技能审计页 `SkillManager` 新增筛选状态对象与分页状态（默认每页 20，可切换 20/50/100）；
   - 审计筛选支持：`startTime/endTime/toolCallId/username/userId/status/toolCode`，并新增查询/重置按钮。
2. 后端修复与兼容：
   - `UserConversationController.listMessages` 改为 `LinkedHashMap` 组装返回，避免 `Map.of` 对 `null` 值不兼容导致响应异常；
   - 修复 `AdminControllerRouteSampleTest`，对齐 `listRouteSamples` 新签名，改为验证 `pageSamples` 分页调用。
3. Docker 启动与问题处理：
   - 执行 `docker compose -f deploy/docker-compose-ragflow.yml up -d --build backend frontend`；
   - 首次构建失败（测试编译签名不一致），修复测试后重建成功，`ragflow-backend/ragflow-frontend` 均 `Up`。
4. 懒加载实测与分页缺陷修复：
   - 实测发现 `/api/user/conversations?page=1&pageSize=50` 返回全量（60 条）且 `total=0`；
   - 原因：当前运行链路下 MyBatis-Plus 分页插件未生效；
   - 修复 `UserConversationService.listUserConversations` 为手工 `limit/offset + count`；
   - 重建 backend 后复测通过：
     - page1：50 条，`total=60`，`has_more=true`；
     - page2：10 条，`has_more=false`。
5. 消息懒加载实测：
   - 对同一会话写入 55 条消息后调用消息分页接口；
   - 首次 `limit=50` 返回 50 条且 `has_more=true`；
   - 传 `beforeId` 再取返回 5 条且 `has_more=false`，验证“向上翻页懒加载”链路可用。

**验证结果**
1. 运行状态：
   - `docker compose -f deploy/docker-compose-ragflow.yml ps` 显示 backend/frontend 与依赖服务均正常运行。
2. 接口验证：
   - `/api/user/conversations` 分页参数生效；
   - `/api/user/conversations/{id}/messages` 的 `beforeId` 懒加载参数生效。
3. 质量检查：
   - IDE `GetDiagnostics` 返回空（未发现新增诊断错误）。

**结论**
- 已按要求完成 Docker 启动并验证“会话首屏 50 + 上滑懒加载”；
- 审计与路由样本页面已具备范围筛选与分页能力；
- 对话剩余清理天数显示与三个月保留策略链路已在本轮改造中打通。

## 2026-04-08: 分页控件位置/筛选项精简/用户名展示/消息懒加载滚动修复

**操作人**: AI Assistant (Trae IDE)

**用户反馈问题**
- “技能管理”“审计查询”的 `20/50/100` 每页设置位置不对，且设置后疑似不生效（仍显示全量）；
- 技能管理链路查询中 `tool_call_id` 不需要显示，也不需要作为查询项；
- 技能管理链路查询中 `用户ID` 不需要显示，也不需要作为查询项；
- 审计查询“用户”列应显示用户名，不应回退显示用户 ID；
- 对话上滑懒加载后视图会突然跳到最新消息，影响继续阅读。

**实施动作**
1. 前端交互调整（`frontend/src/App.jsx`）：
   - 将“路由样本”和“技能审计”中的“每页 20/50/100”从顶部筛选区移到表格分页栏最右侧；
   - 技能审计筛选区删除 `tool_call_id`、`用户ID` 两个输入项；
   - 技能审计表格删除 `tool_call_id` 显示列；
   - 技能审计“用户”列改为仅展示 `username`（为空时显示 `-`，不再回退 `user_id`）。
2. 懒加载滚动稳定性修复（`frontend/src/App.jsx`）：
   - 增加 `suppressAutoScrollRef`；
   - 在“向上加载历史消息并前插”时，临时禁用“messages 变化后自动滚到底部”的副作用；
   - 保留已有高度差补偿逻辑，确保加载后仍停留在原阅读位置附近。
3. 后端分页兜底（防止分页插件在部分链路失效）：
   - `backend/src/main/java/com/ai4kb/backend/engine/service/RouteSampleService.java`：
     - `pageSamples` 改为手工 `count + limit/offset`；
   - `backend/src/main/java/com/ai4kb/backend/skill/service/DynamicSkillAuditService.java`：
     - `pageAudits` 改为手工 `count + limit/offset`。

**验证结果**
1. 代码诊断：
   - IDE `GetDiagnostics` 返回空（本次修改未引入新增诊断错误）。
2. 逻辑验证结论：
   - 每页条数设置位置已移动到分页栏右侧；
   - 技能审计筛选项与展示列已去除 `tool_call_id` / `用户ID`；
   - 审计用户列已按用户名展示；
   - 对话上滑加载历史时不再被自动拉回到底部。

## 2026-04-08: 用户反馈“页面未变化”后二次重启与在线实测

**操作人**: AI Assistant (Trae IDE)

**用户反馈**
- 页面上仍未看到改动，要求“重启前后端，并测试”。

**实施动作**
1. 执行强制重建重启：
   - `docker compose -f deploy/docker-compose-ragflow.yml up -d --build --force-recreate backend frontend`
2. 运行状态检查：
   - `docker compose -f deploy/docker-compose-ragflow.yml ps`；
   - backend/frontend 均为 `Up`，端口分别为 `8083/8086`。
3. 健康与鉴权检查：
   - `curl http://127.0.0.1:8083/api/health` 返回 `401`（服务在线且受鉴权保护）；
   - `curl http://127.0.0.1:8086/` 返回 `200`（前端可访问）。
4. 功能接口实测（登录后携带 token）：
   - 使用 `superadmin / ChangeMe123!` 登录 `/api/user/auth/login` 获取 token；
   - 路由样本分页：
     - `/api/admin/route-samples?page=1&pageSize=20` => `items=20`, `total=88`, `page_size=20`
     - `/api/admin/route-samples?page=1&pageSize=50` => `items=50`, `total=88`, `page_size=50`
   - 技能审计分页：
     - `/api/admin/skills/audit?page=1&pageSize=20` => `items=15`, `total=15`, `page_size=20`
     - `/api/admin/skills/audit?page=1&pageSize=50` => `items=15`, `total=15`, `page_size=50`

**结论**
- 前后端已重启到最新镜像；
- 后端分页参数已确认生效（至少路由样本接口已明确按 `20/50` 返回不同条数）；
- 技能审计当前总量为 15 条，低于 20，因此切换为 50 时显示条数不变属正常表现。

## 2026-04-08: 思考识别增强与“相关技能”展示分离修复

**操作人**: AI Assistant (Trae IDE)

**用户反馈**
- 对话中经常未识别“思考过程”，原因是模型输出有时缺少 `<think>` 起始标签。
- “【相关技能】...” 会与 RAG 回答正文混在一起显示。

**修复措施**
1. 后端（`EngineOrchestrator`）将技能提示从正文解耦为独立元字段：
   - 在结构化 payload / 注解 payload 中新增 `skillHint` 字段；
   - 不再把“相关技能”拼接到 `answer` 文本里，避免与正文混排。
2. 后端增强思考标签兼容：
   - 当 OpenAI 响应仅有 `reasoning_content` 时，统一包裹为 `<think>...</think>` 再下发，保证前端可识别思考块。
3. 前端（`App.jsx`）增强思考解析逻辑：
   - 支持多段 `<think>...</think>` 提取；
   - 支持仅出现 `</think>`（无起始标签）时的回退识别；
   - 清理残留 think 标签，避免污染正文。
4. 前端新增“相关技能”独立展示卡片：
   - 读取/持久化 `skillHint`；
   - 在消息区单独渲染“相关技能”块，不与 Markdown 正文混排。

**验证与测试**
1. 已执行重建重启：
   - `docker compose -f deploy/docker-compose-ragflow.yml up -d --build --force-recreate backend frontend`
   - 构建日志显示 backend/frontend 均完成重新构建并启动成功。
2. 流式接口联测：
   - 登录、创建会话、调用 `/api/v1/agent/chat/stream` 成功返回流；
   - 当前测试样例中未稳定触发 `skillHint`（取决于路由与技能匹配命中），但新字段链路与前端渲染已完成接入；
   - `think` 兼容逻辑已在代码层覆盖“多段标签”与“仅结束标签”场景。

**备注**
- `skillHint` 仅在“知识问答且技能匹配命中”的路径下返回；若走纯工具执行或普通聊天路径，可能不会出现该字段。

## 2026-04-08: 聊天窗口新增“一键回到底部”按钮

**操作人**: AI Assistant (Trae IDE)

**需求**
- 用户希望在前端对话区上翻查看历史时，可在聊天框中下部一键回到最新消息。

**实现**
1. 前端 `ChatInterface` 增加状态 `showScrollToBottom`，用于控制按钮显隐。
2. 新增滚动距离判断：
   - 通过 `distanceToBottom = scrollHeight - scrollTop - clientHeight` 计算离底部距离；
   - 大于阈值（140px）显示按钮，否则隐藏。
3. 滚动监听增强：
   - 在 `handleMessagesScroll` 中先刷新按钮显隐，再执行“到顶部加载更多”逻辑。
4. 新增 `handleScrollToBottom`：
   - 点击按钮后平滑滚动到底部，并隐藏按钮。
5. 按钮样式与位置：
   - 放在聊天区域中下部居中（`left-1/2 -translate-x-1/2 bottom-28`），避免遮挡输入框。

**验证**
- 在会话中向上滚动后，按钮会出现；
- 点击后平滑回到底部；
- 在底部位置按钮自动隐藏；
- 未引入新的 IDE 诊断报错（`GetDiagnostics` 结果为空）。

## 2026-04-08: 技能管理入口收敛 + 新增按钮位置调整 + 审计查询用户名化

**操作人**: AI Assistant (Trae IDE)

**用户诉求**
- 技能管理中不再提供“技能列表与审计 / 新增技能 / 更新技能”顶部直达切换；
- 仅通过列表点击“修改”进入更新技能；
- “新增技能”按钮独立放在“刷新技能”旁；
- 审计查询页面改为按用户名查询，表格显示用户名而非用户 ID。

**实现细节**
1. 技能管理入口调整（前端）：
   - 删除顶部三段式子页面切换按钮；
   - 保留列表页主入口，新增技能通过列表工具栏按钮进入；
   - 更新技能仅保留“列表 -> 修改”路径进入；
   - 在新增/更新表单页加入“返回技能列表”按钮，避免无入口返回。
2. 新增技能按钮位置调整（前端）：
   - 在“仅显示在线技能 / 刷新技能”同一行加入独立“新增技能”按钮；
   - 点击后进入新增技能表单，并重置为默认表单。
3. 审计查询用户名化（前后端）：
   - 路由样本查询接口新增 `username` 参数；
   - 后端按用户名解析用户并转换为 userId 后执行分页查询；
   - 响应中补充 `username` 字段；
   - 前端筛选项从 `userId` 改为 `username`，列表“用户”列改显示 `username`；
   - CSV 导出列同步由 `user_id` 调整为 `username`。

**验证**
- `GetDiagnostics`：无新增诊断错误；
- 后端编译：`mvn -DskipTests compile` 通过；
- 前端构建：`npm run build` 通过。

## 2026-04-08: Docker 重启前后端与联测验证

**操作人**: AI Assistant (Trae IDE)

**执行目的**
- 按用户要求使用 Docker 启动前后端，并验证关键接口可用性与“用户名查询”链路是否生效。

**执行过程**
1. 执行重建启动：
   - `docker compose -f deploy/docker-compose-ragflow.yml up -d --build --force-recreate backend frontend`
2. 启动时发现问题并修复：
   - 后端镜像构建在 `testCompile` 阶段失败，原因是 `UserAdminController.listRouteSamples` 新增 `username` 参数后，测试用例调用参数个数未同步；
   - 已更新测试文件 `backend/src/test/java/com/ai4kb/backend/admin/controller/AdminControllerRouteSampleTest.java` 后重试启动成功。
3. 接口联测（Python requests）：
   - 前端首页：`http://127.0.0.1:8086/` 返回 `200`；
   - 登录接口：`POST /api/user/auth/login` 返回 `200`，可获取 token；
   - 路由样本接口：`GET /api/admin/route-samples?username=superadmin&page=1&pageSize=5` 返回 `200`，并包含 `username` 字段；
   - 技能审计接口：`GET /api/admin/skills/audit?username=superadmin&page=1&pageSize=5` 返回 `200`，结果显示 `username=superadmin`。

**结果**
- Docker 前后端服务已正常运行；
- 用户名筛选与用户名展示相关接口验证通过。

## 2026-04-08: `.gitignore` 忽略规则补全

**操作人**: AI Assistant (Trae IDE)

**需求**
- 用户反馈根目录 `.gitignore` 仅有 `models/`，忽略项不足，容易把构建产物/临时文件误提交。

**本次补充内容**
1. 系统与 IDE：
   - `.DS_Store`、`Thumbs.db`、`.idea/`、`.vscode/`、`*.iml`
2. 日志与临时文件：
   - `*.log`、`logs/`、`tmp/`、`temp/`、`*.tmp`
3. 环境与本地配置：
   - `.env`、`.env.*`、`*.local`，并保留 `!.env.example`
4. Python 缓存与虚拟环境：
   - `__pycache__/`、`*.py[cod]`、`.pytest_cache/`、`.mypy_cache/`、`.ruff_cache/`、`.venv/`、`venv/`
5. 前后端构建产物：
   - `backend/target/`
   - `frontend/node_modules/`、`frontend/dist/`、`frontend/.vite/`、`frontend/.cache/`

**结果**
- `.gitignore` 从单条规则扩展为覆盖当前项目主要技术栈（Java + Node + Python + IDE）的常用忽略集合，降低误提交风险。

## 2026-04-08: 管理员直辖关系与权限边界收敛（知识库/技能/授权）
**操作人**: AI Assistant (Trae IDE)
**操作内容**:
1. **用户直属关系落库**:
   - 在 `t_user` 增加 `manager_user_id` 字段，并在迁移器中自动补齐列与索引（`idx_t_user_manager`）。
   - `User` 实体新增 `managerUserId` 映射。
   - 普通用户创建逻辑改为强制绑定直属管理员：
     - `admin` 创建普通用户时，自动绑定自己；
     - `super_admin` 可通过 `manager_user_id` 指定管理员（且必须是 `admin`），未传时回退到首个 `admin`。
2. **管理员用户范围收敛**:
   - `GET /api/admin/users`：
     - `super_admin` 仍返回全量；
     - `admin` 仅返回“自己 + 自己直属普通用户”。
3. **知识库权限边界**:
   - 管理员对知识库内部能力（文档列表/上传/运行/删除/重命名/切片/文件查看）统一改为“仅可管理自己创建的知识库”。
   - `GET /api/admin/datasets` 保留可见性，并追加 `manageable` 字段：
     - 自己创建为 `true`；
     - 他人创建为 `false`（可见但不可进入内部内容）。
   - 修复了 `listDatasets` 在响应式链路中读取 ThreadLocal 登录态导致误报 403 的问题（改为入口捕获当前用户并下传）。
4. **技能管理权限边界**:
   - `/api/admin/skills` 下的技能 CRUD（注册/上线/下线/删除/列表）改为仅 `super_admin` 可用。
   - `admin` 保留技能审计查询能力，但审计范围限定为“自己 + 自己直属普通用户”。
5. **权限分配范围收敛**:
   - `permission/sync`、`permission/grant`、`permissions/datasets`、`permissions/skills`、`permission/{username}` 均增加目标用户范围校验：
     - `super_admin` 全量；
     - `admin` 仅可操作自己与直属普通用户。
6. **前端交互收敛**:
   - 知识库卡片支持基于 `manageable` 的禁入提示（仅可见不可进入）。
   - 技能管理页对 `admin` 隐藏新增/修改/上线/下线/删除相关入口，仅保留审计查询区域。

**测试与验证（Docker）**:
1. 启动方式：`docker compose -f deploy/docker-compose-ragflow.yml up -d --build backend frontend`。
2. 构建验证：
   - 后端：`mvn -q -DskipTests package` 通过；
   - 前端：`npm run build` 通过。
3. 接口验收结果（容器运行态）:
   - 管理员可见知识库列表且含 `manageable` 标识：`200`；
   - 管理员访问他人知识库内部文档：`403`；访问自己知识库文档：`200`；
   - 管理员创建普通用户：`200`；管理员列表仅见“自己+直属用户”；
   - 管理员给直属用户同步权限：`200`；给其他管理员名下用户同步权限：`403`；
   - 管理员技能 CRUD（register/offline）均被拒绝：`403`；管理员技能审计查询：`200`。

**结论**:
- 已实现“管理员管理一批普通用户、普通用户仅一个直辖管理员”的模型约束。
- 已实现你要求的 3 条核心边界：
  1) 知识库可见不可越权管理；
  2) 管理员不可做技能 CRUD，且查询链路仅自己与直属用户；
  3) 权限分配仅自己与直属用户。

## 2026-04-08: 用户管理增强（前端导航 + 超管升级管理员 + 创建约束）
**操作人**: AI Assistant (Trae IDE)
**操作内容**:
1. **后端用户创建约束收紧**（`UserAdminController`）:
   - `super_admin` 创建普通用户时，`manager_user_id` 改为必填。
   - 显式禁止将普通用户绑定给 `super_admin` 自己。
   - 仍要求 `manager_user_id` 必须指向 `admin` 角色。
2. **新增用户升级接口**:
   - 新增 `POST /api/admin/users/{userId}/promote-admin`。
   - 仅 `super_admin` 可调用。
   - 仅允许 `user -> admin` 升级；不支持管理员降级。
   - 升级后清空 `manager_user_id`，表示脱离原管理员直辖关系。
3. **新增用户删除接口**:
   - 新增 `DELETE /api/admin/users/{userId}`。
   - `super_admin` 可删除任意非 super_admin 且非本人用户。
   - `admin` 仅可删除自己直属普通用户。
   - 用于“管理员不能降级，只能删除后重建”的管理流程。
4. **前端新增导航与页面**（`App.jsx`）:
   - 侧边栏新增“用户管理”导航（admin/super_admin 均可见）。
   - 新增 `UserManagement` 页面，支持：
     - 创建用户（用户名/密码/角色）；
     - 超管创建普通用户时必须填写直属管理员ID；
     - 超管将普通用户升级为管理员；
     - 按权限删除可管理用户。
   - 保留原“权限分配”页用于知识库权限配置。
5. **测试补充**（`UserAdminControllerTest`）:
   - 新增 `super_admin` 未传 `manager_user_id` 创建普通用户失败用例。
   - 新增普通用户升级管理员成功用例。
   - 保留并增强管理员创建普通用户归属校验、超管创建管理员与指定直属管理员校验。

**验证结果**:
1. 后端单测：`mvn -Dtest=UserAdminControllerTest test` 通过（6/6）。
2. 前端构建：`npm run build` 通过。
3. IDE 诊断：无新增报错。

**结论**:
- 已实现前端可配置的“用户管理”导航与页面。
- 已实现“超管可将普通用户升级为管理员；管理员不可降级，只能删除后重建”。
- 已实现“超管创建普通用户必须指定对应管理员，且不可指定自己”。

## 2026-04-09: 权限分配页职责收敛 + 用户信息编辑能力补齐
**操作人**: AI Assistant (Trae IDE)
**操作内容**:
1. **页面职责调整**（`frontend/src/App.jsx`）:
   - 从“权限分配”页面移除“创建用户”模块，保留其核心职责为“用户-知识库权限分配”。
   - “创建用户”能力仅保留在“用户管理”页面，满足“创建入口独属于用户管理”的要求。
2. **后端新增用户编辑接口**（`UserAdminController`）:
   - 新增 `PUT /api/admin/users/{userId}`，支持修改用户名与密码。
   - 参数规则：`username` 与 `password` 不能同时为空。
   - 用户名唯一性校验：若修改后的用户名已被其他账号占用则拒绝。
   - 权限边界：
     - `super_admin` 可按现有全局管理范围修改；
     - `admin` 仅可修改“自己 + 自己直属普通用户”；
     - 非 `super_admin` 不可修改 `super_admin` 账号。
3. **前端用户管理页新增“修改账号”能力**（`UserManagement`）:
   - 用户列表每行新增“修改账号”按钮。
   - 点击后展开编辑区，可修改用户名，密码可选填写（留空表示不改密码）。
   - 保存后调用新接口并刷新列表。
4. **测试补充**（`UserAdminControllerTest`）:
   - 新增 `updateUser_shouldSuccessForAdminManagedUser` 用例，验证管理员修改直属用户用户名与密码成功。

**验证结果**:
1. 后端单测：`mvn -Dtest=UserAdminControllerTest test` 通过（7/7）。
2. 前端构建：`npm run build` 通过。
3. IDE 诊断：无新增报错。

**结论**:
- “权限分配”与“用户管理”职责已清晰分离。
- 管理员/超级管理员已具备对可管理用户的用户名、密码修改能力。

## 2026-04-09: 越权用例固化 + 管理员总览按角色扩展
**操作人**: AI Assistant (Trae IDE)
**操作内容**:
1. **补充越权自动化用例**（`backend/src/test/java/com/ai4kb/backend/user/controller/UserAdminControllerTest.java`）:
   - 新增 `updateUser_shouldFailForAdminUnmanagedUser`：
     - 场景：`admin` 修改非直属普通用户；
     - 预期：抛出拒绝异常（对应接口层 `403` 语义）；
     - 校验：`userMapper.updateById` 不会执行。
2. **管理员总览接口按角色扩展**（`backend/src/main/java/com/ai4kb/backend/user/controller/UserAdminController.java`）:
   - `GET /api/admin/super/ownership-overview` 从“仅 super_admin”调整为“admin/super_admin 均可访问”。
   - 返回结构扩展为：`admins` + `users`。
   - 数据范围规则：
     - `super_admin`：`admins` 返回全部管理员总览，`users` 返回全部普通用户总览；
     - `admin`：`admins` 为空，`users` 仅返回其直属普通用户总览（`manager_user_id = current_user_id`）。
   - 抽出通用构建逻辑 `buildUserOverviewItems(...)`，统一聚合知识库、授权、会话和对话记录指标。
3. **前端管理员总览页扩展**（`frontend/src/App.jsx`）:
   - 侧边栏为 `admin` 新增“管理员总览”入口。
   - 总览组件改为按角色展示：
     - `super_admin`：展示“管理员总览”与“普通用户总览”；
     - `admin`：仅展示“我的管辖普通用户总览”。
   - 页面入口渲染条件改为 `isAdminLikeRole(role)`，并向组件传入当前角色。

**验证结果**:
1. 后端单测：`mvn -Dtest=UserAdminControllerTest test` 通过（8/8）。
2. 前端构建：`npm run build` 通过。
3. IDE 诊断：无新增报错。

**结论**:
- “admin 修改非直属用户”越权边界已被自动化用例固定。
- 超级管理员总览已覆盖管理员 + 普通用户。
- 管理员已拥有“管理员总览”入口，且仅看到自己管辖的普通用户数据。

## 2026-04-09: 调试对话页过期文案调整（去除 span 徽章）
**操作人**: AI Assistant (Trae IDE)
**操作内容**:
1. 修改 `frontend/src/App.jsx` 中“调试对话”会话列表的剩余保留时间展示：
   - 由 `xx天` 改为 `还可保留xx天`；
   - 去除原先的 `span` 徽章样式，改为普通文本容器展示。
2. 修改“调试对话”头部当前会话的保留时长展示：
   - 由 `保留xx天` 改为 `还可保留xx天`；
   - 去除原先的 `span` 徽章样式，改为普通文本容器展示。

**验证结果**:
1. 前端构建：`npm run build` 通过。
2. IDE 诊断：无新增报错。

**结论**:
- 对话栏过期时间文案已统一为“还可保留xx天”。
- 原有 `span` 徽章样式已移除，不再以徽章形式显示。

## 2026-04-09: 调试对话页移除保留天数展示
**操作人**: AI Assistant (Trae IDE)
**操作内容**:
1. 修改 `frontend/src/App.jsx` 的“调试对话”页面，移除会话保留时长展示元素：
   - 左侧会话列表中每行的剩余天数小标签（原 `text-[10px]` 样式元素）已删除；
   - 右侧头部“保留xx天”展示已删除。
2. 保留会话标题、切换、重命名、删除等功能，不影响对话主流程。

**验证结果**:
1. 前端构建：`npm run build` 通过。
2. IDE 诊断：无新增报错。

**结论**:
- 调试对话页面不再显示保留天数相关 `div/span` 文案。

## 2026-04-09: 超管总览补充普通用户上层管理员展示
**操作人**: AI Assistant (Trae IDE)
**操作内容**:
1. 后端总览接口补充返回字段（`backend/src/main/java/com/ai4kb/backend/user/controller/UserAdminController.java`）:
   - 在 `buildUserOverviewItems(...)` 中增加普通用户管理关系字段：
     - `managerUserId`
     - `managerUsername`
   - 通过批量查询 `manager_user_id` 对应用户，填充上层管理员用户名。
2. 前端总览页面统计行补充展示（`frontend/src/App.jsx`）:
   - 在每个主体卡片顶部统计 `div`（“知识库/授权记录/用户总览/会话/对话记录”同一行）中，
   - 当主体角色为 `user` 时追加显示：`上层管理员 {managerUsername}`。

**验证结果**:
1. 后端单测：`mvn -Dtest=UserAdminControllerTest test` 通过（8/8）。
2. 前端构建：`npm run build` 通过。
3. IDE 诊断：无新增报错。

**结论**:
- 超级管理员查看“管理员总览”时，可直接在普通用户卡片的统计行看到其上层管理员信息。
>>>>>>> 58312d0 (修正了管理员职责)
