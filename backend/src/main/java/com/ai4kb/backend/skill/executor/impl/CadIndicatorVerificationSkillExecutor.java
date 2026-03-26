package com.ai4kb.backend.skill.executor.impl;

import com.ai4kb.backend.skill.executor.SkillExecutor;
import com.ai4kb.backend.skill.model.SkillFileRecord;
import com.ai4kb.backend.skill.model.ToolExecutionRequest;
import com.ai4kb.backend.skill.model.ToolExecutionResult;
import com.ai4kb.backend.skill.model.ToolSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class CadIndicatorVerificationSkillExecutor implements SkillExecutor {

    @Value("${skill.cad.script-dir:src/main/java/com/ai4kb/backend/skill/impl/cad_text_extractor}")
    private String scriptDirConfig;

    @Override
    public ToolSpec getToolSpec() {
        return ToolSpec.builder()
                .name("cad_text_extractor_indicator_verification")
                .description("指标校核：上传 CAD 图纸后输出校核结果文件")
                .triggerKeywords(List.of("指标校核", "指标核验", "指标检查"))
                .inputMode("FILE_AND_PARAMS")
                .outputMode("MIXED")
                .uploadRequired(true)
                .acceptedFileTypes(List.of(".dxf"))
                .maxFiles(200)
                .parametersSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "checker", Map.of("type", "string"),
                                "reviewer", Map.of("type", "string")
                        ),
                        "required", List.of()
                ))
                .build();
    }

    @Override
    public Map<String, Object> buildDraftArgs(String query) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("checker", "");
        args.put("reviewer", "");
        args.put("query", query == null ? "" : query);
        return args;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) throws Exception {
        if (request.getInputFiles() == null || request.getInputFiles().isEmpty()) {
            return ToolExecutionResult.builder()
                    .success(false)
                    .summary("缺少输入文件")
                    .errorMessage("该技能需要先上传 dxf 文件")
                    .generatedFiles(List.of())
                    .build();
        }
        String checker = String.valueOf(request.getArgs().getOrDefault("checker", ""));
        String reviewer = String.valueOf(request.getArgs().getOrDefault("reviewer", ""));

        Path taskRoot = Path.of("runtime/skills").toAbsolutePath().normalize().resolve(request.getToolCallId());
        Path inputDir = taskRoot.resolve("execution_input");
        Path outputDir = taskRoot.resolve("execution_output");
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);

        int copied = 0;
        for (SkillFileRecord input : request.getInputFiles()) {
            Path source = Path.of(input.getAbsolutePath());
            if (Files.exists(source)) {
                Path target = inputDir.resolve(input.getFileName());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        if (copied == 0) {
            return ToolExecutionResult.builder()
                    .success(false)
                    .summary("输入目录中没有可处理文件")
                    .errorMessage("未找到上传文件")
                    .generatedFiles(List.of())
                    .build();
        }

        String safeChecker = checker.replace("'", "\\'");
        String safeReviewer = reviewer.replace("'", "\\'");
        String pythonExpr = "from cad_text_extractor import run_batch; "
                + "run_batch(r'" + inputDir + "', r'" + outputDir + "', '" + safeChecker + "', '" + safeReviewer + "')";
        Path scriptDir = Path.of(scriptDirConfig).toAbsolutePath().normalize();
        List<String> command = List.of(
                "python3",
                "-c",
                pythonExpr
        );
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(scriptDir.toFile());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        StringBuilder logs = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logs.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(20, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            return ToolExecutionResult.builder()
                    .success(false)
                    .summary("指标校核超时")
                    .errorMessage("处理超时")
                    .structuredData(Map.of("logs", logs.toString()))
                    .generatedFiles(List.of())
                    .build();
        }
        if (process.exitValue() != 0) {
            return ToolExecutionResult.builder()
                    .success(false)
                    .summary("指标校核执行失败")
                    .errorMessage("执行脚本失败，退出码=" + process.exitValue())
                    .structuredData(Map.of("logs", logs.toString()))
                    .generatedFiles(List.of())
                    .build();
        }

        List<ToolExecutionResult.GeneratedFile> generatedFiles = new ArrayList<>();
        try (var paths = Files.walk(outputDir)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                try {
                    generatedFiles.add(ToolExecutionResult.GeneratedFile.builder()
                            .absolutePath(file.toAbsolutePath().toString())
                            .fileName(file.getFileName().toString())
                            .size(Files.size(file))
                            .build());
                } catch (Exception ignored) {
                }
            });
        }
        return ToolExecutionResult.builder()
                .success(true)
                .summary("指标校核完成，生成文件数：" + generatedFiles.size())
                .structuredData(Map.of(
                        "logs", logs.toString(),
                        "input_file_count", copied
                ))
                .generatedFiles(generatedFiles)
                .build();
    }
}
