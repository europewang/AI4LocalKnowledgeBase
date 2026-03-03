package com.ai4kb.backend.controller;

import com.ai4kb.backend.client.RagFlowClient;
import com.ai4kb.backend.entity.Permission;
import com.ai4kb.backend.entity.User;
import com.ai4kb.backend.mapper.PermissionMapper;
import com.ai4kb.backend.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final RagFlowClient ragFlowClient;
    private final UserMapper userMapper;
    private final PermissionMapper permissionMapper;

    @GetMapping("/datasets")
    public Mono<JsonNode> listDatasets(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "100") int pageSize) {
        return ragFlowClient.listDatasets(page, pageSize);
    }

    @PostMapping("/datasets")
    public Mono<JsonNode> createDataset(@RequestBody Map<String, String> body) {
        return ragFlowClient.createDataset(body.get("name"));
    }

    @DeleteMapping("/datasets/{id}")
    public Mono<JsonNode> deleteDataset(@PathVariable String id) {
        return ragFlowClient.deleteDataset(id);
    }

    @DeleteMapping("/datasets")
    public Mono<JsonNode> deleteDatasets(@RequestBody Map<String, List<String>> body) {
        return ragFlowClient.deleteDatasets(body.get("ids"));
    }

    @PutMapping("/datasets/{id}")
    public Mono<JsonNode> updateDataset(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        String language = (String) body.get("language");
        String permission = (String) body.get("permission");
        Map<String, Object> parserConfig = (Map<String, Object>) body.get("parser_config");

        return ragFlowClient.updateDataset(id, name, description, language, permission, parserConfig);
    }

    @GetMapping("/datasets/{id}/documents")
    public Mono<JsonNode> listDocuments(@PathVariable String id,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "100") int pageSize) {
        return ragFlowClient.listDocuments(id, page, pageSize);
    }

    @PostMapping("/datasets/{id}/documents")
    public Mono<JsonNode> uploadDocument(@PathVariable String id,
                                         @RequestParam("file") MultipartFile file) {
        return ragFlowClient.uploadDocument(id, file);
    }

    @PostMapping("/datasets/{id}/documents/run")
    public Mono<JsonNode> runDocuments(@PathVariable String id,
                                       @RequestBody Map<String, List<String>> body) {
        return ragFlowClient.runDocuments(id, body.get("doc_ids"));
    }

    @GetMapping("/datasets/{id}/documents/{docId}/chunks")
    public Mono<JsonNode> listChunks(@PathVariable String id,
                                     @PathVariable String docId,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "100") int pageSize) {
        return ragFlowClient.listChunks(id, docId, page, pageSize);
    }

    @GetMapping("/datasets/{id}/documents/{docId}/file")
    public Mono<org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>> getDocumentFile(
            @PathVariable String id, @PathVariable String docId) {
        return ragFlowClient.getDocumentFile(id, docId)
                .map(resource -> org.springframework.http.ResponseEntity.ok()
                        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .body(resource));
    }

    @DeleteMapping("/datasets/{id}/documents")
    public Mono<JsonNode> deleteDocuments(@PathVariable String id, @RequestBody Map<String, List<String>> body) {
        // Frontend sends "ids", but RagFlow might expect "doc_ids" or "ids".
        // Let's support "ids" as per previous implementation, but be aware of RAGFlow API.
        // RagFlowClient.deleteDocuments uses "ids" in map.
        return ragFlowClient.deleteDocuments(id, body.get("ids"));
    }

    @PutMapping("/datasets/{id}/documents/{docId}")
    public Mono<JsonNode> updateDocument(@PathVariable String id, @PathVariable String docId, @RequestBody Map<String, String> body) {
        return ragFlowClient.updateDocument(id, docId, body.get("name"));
    }

    @GetMapping("/users")
    public List<User> listUsers() {
        return userMapper.selectList(null);
    }

    @PostMapping("/permission/sync")
    public Map<String, Object> syncPermissions(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        List<String> datasetIds = (List<String>) body.get("dataset_ids");
        
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
             throw new RuntimeException("User not found");
        }
        
        List<Permission> currentPerms = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, user.getId())
                .eq(Permission::getResourceType, "dataset"));
        
        List<String> currentIds = new java.util.ArrayList<>();
        for(Permission p : currentPerms) {
            currentIds.add(p.getResourceId());
        }

        // Delete removed
        for (Permission p : currentPerms) {
            if (!datasetIds.contains(p.getResourceId())) {
                permissionMapper.deleteById(p.getId());
            }
        }
        
        // Add new
        for (String id : datasetIds) {
            if (!currentIds.contains(id)) {
                 Permission p = new Permission();
                 p.setUserId(user.getId());
                 p.setResourceType("dataset");
                 p.setResourceId(id);
                 permissionMapper.insert(p);
            }
        }
        
        return java.util.Collections.singletonMap("status", "ok");
    }

    @PostMapping("/user")
    public User createUser(@RequestBody User user) {
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, user.getUsername()));
        if (existing != null) {
            return existing;
        }
        if (user.getCreateTime() == null) {
            user.setCreateTime(LocalDateTime.now());
        }
        userMapper.insert(user);
        return user;
    }

    @PostMapping("/permission/grant")
    public Map<String, String> grantPermission(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String resourceType = body.get("resource_type");
        String resourceId = body.get("resource_id");

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new RuntimeException("User not found");
        }

        // Check if permission already exists to avoid duplicate entry error
        Long count = permissionMapper.selectCount(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, user.getId())
                .eq(Permission::getResourceType, resourceType)
                .eq(Permission::getResourceId, resourceId));
        
        if (count > 0) {
            return java.util.Collections.singletonMap("status", "ok");
        }

        Permission permission = new Permission();
        permission.setUserId(user.getId());
        permission.setResourceType(resourceType);
        permission.setResourceId(resourceId);
        
        try {
            permissionMapper.insert(permission);
            return java.util.Collections.singletonMap("status", "ok");
        } catch (Exception e) {
            return java.util.Collections.singletonMap("status", "fail: " + e.getMessage());
        }
    }

    @GetMapping("/permission/{username}")
    public List<Permission> getUserPermissions(@PathVariable String username) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        return permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, user.getId()));
    }
}
