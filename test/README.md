# AI4KB 测试套件 (Test Suite)

本目录包含了一系列测试脚本，用于验证 AI4LocalKnowledgeBase 各个组件（Xinference, RAGFlow, Backend）的可用性。

## 1. 测试数据 (test/data/)
*   `sample_knowledge.txt`: 简单的测绘规范文本，用于上传测试。

## 2. 测试脚本 (test/)

### 2.1 基础模型层测试 (`01_test_xinference.py`)
验证 Xinference 服务及其加载的模型（Embedding, Rerank）。
```bash
python3 test/01_test_xinference.py
```
*   **预期**: 列出 3 个模型，并成功调用 Embedding 和 Rerank 接口。

### 2.2 RAG 引擎层测试 (`02_test_ragflow_api.py`)
验证 RAGFlow 服务的连通性。
```bash
python3 test/02_test_ragflow_api.py
```
*   **预期**: 返回 RAGFlow 版本号或 UI 页面状态 200。

### 2.3 业务后端 Admin 测试 (`03_test_backend_admin.py`)
验证 Java 后端 Admin 接口（获取 KB 列表，创建用户，分配权限）。
```bash
python3 test/03_test_backend_admin.py
```
*   **预期**: 列出知识库列表，成功创建用户 `test_user_01` 并分配权限。
*   **注意**: 运行前请确保 RAGFlow 中至少有一个知识库（可先运行根目录下的 `test_ragflow_e2e.py` 生成）。

### 2.4 业务后端 Chat 测试 (`04_test_backend_chat.py`)
验证 Java 后端 Chat 接口（带权限注入的流式对话）。
```bash
python3 test/04_test_backend_chat.py
```
*   **预期**: 模拟 `test_user_01` 发起对话，并流式输出回答。

## 3. 全链路闭环测试
根目录下的 `test_ragflow_e2e.py` 是最核心的全链路测试脚本，涵盖了从建库到问答的全流程。建议优先运行该脚本确保 RAGFlow 内部逻辑闭环。
```bash
python3 test_ragflow_e2e.py
```
