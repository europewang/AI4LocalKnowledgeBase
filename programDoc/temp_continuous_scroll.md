
## 2026-03-02: PDF 连续滚动模式 (Continuous Scrolling) 实现
**操作人**: AI Assistant (Trae IDE)
**操作内容**:
*   **需求**: 用户希望 PDF 预览不再是分页点击查看，而是连续滚动的模式。
*   **实现**:
    *   修改 `DocumentViewer` 组件，移除 `<Page>` 的单一分页逻辑。
    *   使用 `Array.from(new Array(numPages))` 循环渲染所有页面，每个页面包裹在独立的 `div` 容器中。
    *   **高亮适配**: 每个页面容器内都包含一个 `ChunkHighlights` 组件，并传入对应的 `pageNumber`，确保高亮能正确显示在对应的页面上。
    *   **自动定位**: 当点击切片列表时，通过 `document.getElementById('pdf-page-X').scrollIntoView()` 实现平滑滚动跳转到目标页面。
    *   **UI 调整**: 移除了底部的分页导航按钮，改为仅显示总页数。
*   **部署**:
    *   执行 `docker compose build --no-cache frontend` 重建镜像。
    *   执行 `docker compose up -d frontend` 重启容器。
