package com.ai4kb.backend.skill.service;

import com.ai4kb.backend.skill.model.SkillFileRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
/**
 * 工具文件存储服务。
 * 负责工具输入/输出文件的落盘、元数据登记与按 fileId 读取资源。
 */
public class ToolFileStorageService {

    private final Path runtimeRoot;
    private final Map<String, SkillFileRecord> recordsByFileId = new ConcurrentHashMap<>();
    private final Map<String, List<String>> inputFileIdsByToolCallId = new ConcurrentHashMap<>();

    public ToolFileStorageService(@Value("${ai4kb.skill.runtime-dir:./runtime/skills}") String runtimeDir) {
        this.runtimeRoot = Path.of(runtimeDir).toAbsolutePath().normalize();
    }

    /**
     * 保存工具输入文件，并建立 fileId 索引。
     */
    public SkillFileRecord storeInputFile(String toolCallId, MultipartFile file) throws IOException {
        String fileId = "sf-" + UUID.randomUUID();
        String sanitizedName = sanitize(file.getOriginalFilename());
        Path targetDir = resolveToolDir(toolCallId).resolve("input");
        Files.createDirectories(targetDir);
        Path targetFile = targetDir.resolve(fileId + "_" + sanitizedName);
        Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);
        SkillFileRecord record = SkillFileRecord.builder()
                .fileId(fileId)
                .toolCallId(toolCallId)
                .fileName(sanitizedName)
                .contentType(file.getContentType())
                .size(file.getSize())
                .absolutePath(targetFile.toAbsolutePath().toString())
                .role("INPUT")
                .createdAt(LocalDateTime.now())
                .build();
        recordsByFileId.put(fileId, record);
        inputFileIdsByToolCallId.computeIfAbsent(toolCallId, k -> new ArrayList<>()).add(fileId);
        return record;
    }

    /**
     * 查询某次工具调用已上传输入文件（按创建时间升序）。
     */
    public List<SkillFileRecord> listInputFiles(String toolCallId) {
        List<String> fileIds = inputFileIdsByToolCallId.getOrDefault(toolCallId, List.of());
        List<SkillFileRecord> records = new ArrayList<>();
        for (String fileId : fileIds) {
            SkillFileRecord record = recordsByFileId.get(fileId);
            if (record != null) {
                records.add(record);
            }
        }
        records.sort(Comparator.comparing(SkillFileRecord::getCreatedAt));
        return records;
    }

    /**
     * 注册工具结果文件到运行目录，并生成可下载记录。
     */
    public SkillFileRecord registerResultFile(String toolCallId, Path filePath, String fileName) throws IOException {
        String fileId = "sf-" + UUID.randomUUID();
        String outputName = sanitize(fileName == null || fileName.isBlank() ? filePath.getFileName().toString() : fileName);
        Path targetDir = resolveToolDir(toolCallId).resolve("result");
        Files.createDirectories(targetDir);
        Path targetFile = targetDir.resolve(fileId + "_" + outputName);
        if (!filePath.toAbsolutePath().normalize().equals(targetFile.toAbsolutePath().normalize())) {
            Files.copy(filePath, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
        SkillFileRecord record = SkillFileRecord.builder()
                .fileId(fileId)
                .toolCallId(toolCallId)
                .fileName(outputName)
                .contentType(Files.probeContentType(targetFile))
                .size(Files.size(targetFile))
                .absolutePath(targetFile.toAbsolutePath().toString())
                .role("RESULT")
                .createdAt(LocalDateTime.now())
                .build();
        recordsByFileId.put(fileId, record);
        return record;
    }

    public SkillFileRecord getFileRecord(String fileId) {
        return recordsByFileId.get(fileId);
    }

    public Resource getFileResource(String fileId) {
        SkillFileRecord record = recordsByFileId.get(fileId);
        if (record == null) {
            return null;
        }
        Path path = Path.of(record.getAbsolutePath());
        if (!Files.exists(path)) {
            return null;
        }
        return new FileSystemResource(path);
    }

    /**
     * 解析当前工具调用运行目录。
     */
    public Path resolveToolDir(String toolCallId) {
        return runtimeRoot.resolve(toolCallId);
    }

    /**
     * 过滤文件名中的非法路径字符。
     */
    private String sanitize(String filename) {
        String source = filename == null || filename.isBlank() ? "unknown.bin" : filename;
        return source.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
