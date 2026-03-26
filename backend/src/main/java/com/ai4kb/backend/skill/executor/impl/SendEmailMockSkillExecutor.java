package com.ai4kb.backend.skill.executor.impl;

import com.ai4kb.backend.skill.executor.SkillExecutor;
import com.ai4kb.backend.skill.model.ToolExecutionRequest;
import com.ai4kb.backend.skill.model.ToolExecutionResult;
import com.ai4kb.backend.skill.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
/**
 * 发送邮件工具的 mock 执行器。
 * 用于演示工具调用流程，不触发真实邮件发送。
 */
public class SendEmailMockSkillExecutor implements SkillExecutor {
    @Override
    /**
     * 返回工具规格定义。
     */
    public ToolSpec getToolSpec() {
        return ToolSpec.builder()
                .name("send_email")
                .description("发送邮件")
                .triggerKeywords(List.of("邮件", "email", "发给"))
                .inputMode("PARAMS")
                .outputMode("TEXT")
                .uploadRequired(false)
                .acceptedFileTypes(List.of())
                .maxFiles(0)
                .parametersSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "to", Map.of("type", "string", "format", "email"),
                                "subject", Map.of("type", "string"),
                                "content", Map.of("type", "string")
                        ),
                        "required", List.of("to", "content")
                ))
                .build();
    }

    @Override
    /**
     * 根据用户问题生成草稿参数。
     */
    public Map<String, Object> buildDraftArgs(String query) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("to", "");
        args.put("subject", "AI 助手邮件");
        args.put("content", query == null ? "" : query);
        return args;
    }

    @Override
    /**
     * 执行 mock 发送并返回结构化回执。
     */
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String to = String.valueOf(request.getArgs().getOrDefault("to", ""));
        String subject = String.valueOf(request.getArgs().getOrDefault("subject", ""));
        String content = String.valueOf(request.getArgs().getOrDefault("content", ""));
        String summary = "已模拟发送邮件，收件人=" + to + "，主题=" + subject;
        return ToolExecutionResult.builder()
                .success(true)
                .summary(summary)
                .structuredData(Map.of(
                        "to", to,
                        "subject", subject,
                        "content", content
                ))
                .generatedFiles(List.of())
                .build();
    }
}
