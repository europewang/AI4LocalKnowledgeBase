package com.ai4kb.backend.rag.processor;

import reactor.core.publisher.Flux;

public interface ChatProcessor {
    /**
     * 统一对话处理入口。
     * @param username 当前用户标识
     * @param query 用户问题
     * @param stream 是否按流式返回（实现类可按上游能力降级）
     * @return 事件流文本（由控制器层封装为 SSE）
     */
    Flux<String> process(String username, String query, boolean stream);
}
