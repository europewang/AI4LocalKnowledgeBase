# 项目计划书 (Project Plan)

## 1. 项目背景
本项目采用“业务支撑+技术研发”双核心协作模式，聚焦“基于大模型的测绘知识库与质检智能体构建及应用研究”。
*   **业务主导**: 江北分院（需求锚定、资源供给、外业生产法规/智库构建）。
*   **技术支撑**: 智能研究中心（RAG架构搭建、AgentSkills研发、内网本地化部署）。

## 2. 核心目标
构建一套**本地化部署、安全可控、支持多模态（PDF/CAD）**的测绘知识库与智能质检系统。
*   **知识库问答**: 支持规范条文检索、来源溯源（高亮定位）。
*   **智能质检**: 针对成果报告/图件进行一致性校核。
*   **性能指标**: 检索响应≤10秒，单份报告质检≤5分钟。

## 3. 技术路线 (RAGFlow + Xinference)
本项目摒弃单纯的大模型微调，采用 **RAG (检索增强生成)** 架构，结合 **Agent Skills** 实现业务逻辑。

### 3.1 总体架构图
```mermaid
graph TD
    User[用户 (浏览器)] --> Frontend[前端展示层 (Vue/React)]
    Frontend -- HTTP/JSON --> Backend[业务逻辑层 (Java Spring Boot)]
    Backend -- REST API --> RAGFlow[核心引擎层 (RAGFlow Server)]
    RAGFlow -- Model API --> Xinference[基础模型层 (Xinference)]
    
    subgraph "Docker Host (Single Node)"
        Frontend(Port: 8082)
        Backend(Port: 8083)
        RAGFlow(Port: 8084)
        Xinference(Port: 8085)
        
        subgraph "Middleware (RAGFlow Deps)"
            ES[Elasticsearch]
            MinIO[MinIO (Port: 9002)]
            DB[MySQL (Port: 3307)]
            Redis[Redis (Port: 6381)]
        end
    end
```

### 3.2 核心组件
*   **核心引擎**: **RAGFlow** (负责文档解析、DeepDoc 切片、混合检索)。
*   **模型服务**: **Xinference** (Docker部署，管理 LLM/Embedding/Rerank)。
*   **业务系统**: 自研 **Java Backend + Vue/React Frontend**，封装核心能力，提供定制化交互（如原文高亮）。

## 4. 阶段规划
### 第一阶段：基础奠基 (2026.02 - 2026.04)
*   [x] **架构设计**: 确定 RAGFlow + Xinference + 自研前后端架构。
*   [x] **环境部署**: 完成 GPU/Docker/NVIDIA Toolkit 可用性验证，Xinference 服务端口 8085 可访问。
*   [x] **RAGFlow 基础服务部署**: 启动 RAGFlow 及其依赖（ES/MinIO/MySQL/Redis），并完成端口偏移配置。
*   [x] **模型适配**: 在 Xinference 中跑通 DeepSeek-R1-14B (Int4) + BGE-M3 + Reranker（已完成模型加载与 API 可用性验证）。
*   [x] **知识库构建**: 跑通一键闭环验证脚本（Dataset -> 上传 -> 解析 -> Chat），验证了全链路可用性。

### 第二阶段：核心攻坚 (2026.04 - 2026.08)
*   [ ] **高亮溯源开发**: 前端实现基于 `bbox` 的 PDF 高亮显示。
*   [ ] **质检 Agent 开发**: 编写 Python Skills，针对限差表/图件进行规则校核。
*   [ ] **接口对接**: 完成 Java 后端与 RAGFlow 的全量 API 对接。

### 第三阶段：优化落地 (2026.08 - 2026.12)
*   [ ] **压力测试**: 模拟 50 并发用户，优化 ES 索引与推理并发。
*   [ ] **多模态扩展**: 集成 CAD/GIS 解析模块。
*   [ ] **私有化交付**: 输出运维手册，建立日志分析系统 (ELK)。

## 5. 资源需求
*   **硬件**: 单节点 NVIDIA RTX 5090 (24GB) Server。
*   **软件**: Docker, RAGFlow v0.16+, Xinference, JDK 17+, Node.js 18+。

## 6. 当前进度与下一步 (2026-02-04)
### 6.1 当前进度
*   **全栈服务就绪**: 端口 8082-8085 全部就绪，Docker 容器组（RAGFlow + Xinference + Middleware）运行正常。
*   **模型服务可用**: Xinference (8085) 成功加载 DeepSeek-R1-14B (Int4)、BGE-M3、BGE-Reranker-v2-m3，API 均返回 200。
*   **RAGFlow 闭环跑通**: 已通过 `test_ragflow_e2e.py` 脚本验证了“建库-上传-解析-检索-问答”全流程，确认 RAG 链路通畅。
*   **基础设施稳定**: 解决了模型显存占用与 ES 内存溢出问题，系统资源分配合理。

### 6.2 下一步执行清单（按优先级）
1.  **RAGFlow 界面配置固化**: 在 RAGFlow UI 中完成三个模型的持久化配置，确保重启不丢失。
2.  **真实数据入库**: 上传《工程测量规范》PDF，进行真实解析效果测试与调优（Chunk Size / Overlap）。
3.  **Java 后端对接**: 开发 Java 侧的 API Client，对接 RAGFlow 接口。
