package com.ai4kb.backend.user.controller;

import com.ai4kb.backend.engine.entity.RouteSample;
import com.ai4kb.backend.engine.service.RouteSampleService;
import com.ai4kb.backend.user.auth.AuthContextHolder;
import com.ai4kb.backend.user.auth.AuthenticatedUser;
import com.ai4kb.backend.user.auth.PasswordCodecService;
import com.ai4kb.backend.user.client.RagFlowClient;
import com.ai4kb.backend.user.entity.Permission;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.entity.UserConversation;
import com.ai4kb.backend.user.entity.UserConversationMessage;
import com.ai4kb.backend.user.mapper.PermissionMapper;
import com.ai4kb.backend.user.mapper.UserConversationMapper;
import com.ai4kb.backend.user.mapper.UserConversationMessageMapper;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
/**
 * 用户模块统一管理控制器。
 * 合并原 admin 模块能力，统一承载用户管理、知识库管理、授权、审计与总览接口。
 */
public class UserAdminController {
    private final RagFlowClient ragFlowClient;
    private final RouteSampleService routeSampleService;
    private final UserMapper userMapper;
    private final PermissionMapper permissionMapper;
    private final UserConversationMapper userConversationMapper;
    private final UserConversationMessageMapper userConversationMessageMapper;
    private final PasswordCodecService passwordCodecService;

    @GetMapping("/datasets")
    public Mono<JsonNode> listDatasets(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "100") int pageSize) {
        requireAdminLikeUser();
        return ragFlowClient.listDatasets(page, pageSize)
                .map(this::appendDatasetCreator);
    }

    @PostMapping("/datasets")
    public Mono<JsonNode> createDataset(@RequestBody Map<String, String> body) {
        AuthenticatedUser current = requireAdminLikeUser();
        return ragFlowClient.createDataset(body.get("name"))
                .doOnNext(result -> bindDatasetOwnerPermission(current.getUserId(), result));
    }

    @DeleteMapping("/datasets/{id}")
    public Mono<JsonNode> deleteDataset(@PathVariable String id) {
        AuthenticatedUser current = requireAdminLikeUser();
        validateDatasetDeletePermission(current, id);
        return ragFlowClient.deleteDataset(id)
                .doOnNext(result -> cleanupDatasetPermission(id));
    }

    @DeleteMapping("/datasets")
    public Mono<JsonNode> deleteDatasets(@RequestBody Map<String, List<String>> body) {
        AuthenticatedUser current = requireAdminLikeUser();
        List<String> ids = body.get("ids");
        if (ids != null) {
            for (String id : ids) {
                validateDatasetDeletePermission(current, id);
            }
        }
        return ragFlowClient.deleteDatasets(ids)
                .doOnNext(result -> {
                    if (ids == null) {
                        return;
                    }
                    for (String id : ids) {
                        cleanupDatasetPermission(id);
                    }
                });
    }

    @PutMapping("/datasets/{id}")
    public Mono<JsonNode> updateDataset(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireAdminLikeUser();
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
        requireAdminLikeUser();
        return ragFlowClient.listDocuments(id, page, pageSize);
    }

    @PostMapping(value = "/datasets/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<JsonNode> uploadDocument(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        requireAdminLikeUser();
        return ragFlowClient.uploadDocument(id, file);
    }

    @PostMapping("/datasets/{id}/documents/run")
    public Mono<JsonNode> runDocuments(@PathVariable String id, @RequestBody Map<String, List<String>> body) {
        requireAdminLikeUser();
        return ragFlowClient.runDocuments(id, body.get("doc_ids"));
    }

    @DeleteMapping("/datasets/{id}/documents")
    public Mono<JsonNode> deleteDocuments(@PathVariable String id, @RequestBody Map<String, List<String>> body) {
        AuthenticatedUser current = requireAdminLikeUser();
        validateDatasetDeletePermission(current, id);
        return ragFlowClient.deleteDocuments(id, body.get("ids"));
    }

    @PutMapping("/datasets/{id}/documents/{docId}")
    public Mono<JsonNode> updateDocument(@PathVariable String id, @PathVariable String docId, @RequestBody Map<String, String> body) {
        requireAdminLikeUser();
        return ragFlowClient.updateDocument(id, docId, body.get("name"));
    }

    @GetMapping("/datasets/{id}/documents/{docId}/chunks")
    public Mono<JsonNode> listChunks(@PathVariable String id,
                                     @PathVariable String docId,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "100") int pageSize) {
        requireAdminLikeUser();
        return ragFlowClient.listChunks(id, docId, page, pageSize);
    }

    @GetMapping("/datasets/{id}/documents/{docId}/file")
    public Mono<org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>> getDocumentFile(
            @PathVariable String id,
            @PathVariable String docId) {
        requireAdminLikeUser();
        return ragFlowClient.getDocumentFile(id, docId)
                .map(resource -> org.springframework.http.ResponseEntity.ok()
                        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .body(resource));
    }

    @GetMapping("/users")
    public List<User> listUsers() {
        requireAdminLikeUser();
        return userMapper.selectList(null);
    }

    /**
     * 超级管理员创建管理员账号。
     */
    @PostMapping("/users/admin")
    public Map<String, Object> createAdmin(@RequestBody CreateUserRequest request) {
        AuthenticatedUser current = requireCurrentUser();
        if (!"super_admin".equalsIgnoreCase(current.getRole())) {
            throw new ForbiddenException("仅 super_admin 可创建管理员");
        }
        User user = createUser(request, "admin");
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole()
        );
    }

    /**
     * 管理员或超级管理员创建普通用户。
     */
    @PostMapping("/users/normal")
    public Map<String, Object> createNormal(@RequestBody CreateUserRequest request) {
        AuthenticatedUser current = requireCurrentUser();
        if (!isAdminLikeRole(current.getRole())) {
            throw new ForbiddenException("仅 admin/super_admin 可创建普通用户");
        }
        User user = createUser(request, "user");
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole()
        );
    }

    /**
     * 分配知识库读取权限。
     */
    @PostMapping("/permissions/datasets")
    public Map<String, String> grantDatasetPermission(@RequestBody GrantDatasetPermissionRequest request) {
        upsertPermission(request.getTargetUserId(), "DATASET", request.getDatasetId());
        return Collections.singletonMap("status", "ok");
    }

    /**
     * 分配技能使用权限。
     */
    @PostMapping("/permissions/skills")
    public Map<String, String> grantSkillPermission(@RequestBody GrantSkillPermissionRequest request) {
        upsertPermission(request.getTargetUserId(), "SKILL", request.getSkillCode());
        return Collections.singletonMap("status", "ok");
    }

    @PostMapping("/permission/sync")
    public Map<String, Object> syncPermissions(@RequestBody Map<String, Object> body) {
        requireAdminLikeUser();
        String username = (String) body.get("username");
        List<String> datasetIds = (List<String>) body.get("dataset_ids");
        datasetIds = datasetIds == null ? List.of() : datasetIds;
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        List<Permission> currentPerms = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, user.getId())
                .eq(Permission::getResourceType, "DATASET"));
        Set<String> currentIds = currentPerms.stream().map(Permission::getResourceId).collect(Collectors.toSet());
        for (Permission p : currentPerms) {
            if (!datasetIds.contains(p.getResourceId())) {
                permissionMapper.deleteById(p.getId());
            }
        }
        for (String id : datasetIds) {
            if (!currentIds.contains(id)) {
                Permission p = new Permission();
                p.setUserId(user.getId());
                p.setResourceType("DATASET");
                p.setResourceId(id);
                permissionMapper.insert(p);
            }
        }
        return Collections.singletonMap("status", "ok");
    }

    @PostMapping("/permission/grant")
    public Map<String, String> grantPermission(@RequestBody Map<String, String> body) {
        requireAdminLikeUser();
        String username = body.get("username");
        String resourceType = body.get("resource_type");
        String resourceId = body.get("resource_id");
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        upsertPermission(user.getId(), resourceType, resourceId);
        return Collections.singletonMap("status", "ok");
    }

    @GetMapping("/permission/{username}")
    public List<Permission> getUserPermissions(@PathVariable String username) {
        requireAdminLikeUser();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        return permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, user.getId()));
    }

    @GetMapping("/route-samples")
    public List<RouteSample> listRouteSamples(@RequestParam(defaultValue = "100") int limit,
                                              @RequestParam(required = false) Long userId,
                                              @RequestParam(required = false) String source) {
        requireSuperAdminUser();
        return routeSampleService.listSamples(limit, userId, source);
    }

    @GetMapping("/route-samples/sources")
    public List<String> listRouteSampleSources() {
        requireSuperAdminUser();
        return routeSampleService.listSources();
    }

    @GetMapping("/super/ownership-overview")
    public Map<String, Object> superOwnershipOverview() {
        requireSuperAdminUser();
        List<User> admins = userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getRole, "admin"));
        List<Permission> ownerPermissions = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getResourceType, "DATASET_OWNER"));
        Map<Long, List<Permission>> adminOwnedPermissionMap = ownerPermissions.stream()
                .collect(Collectors.groupingBy(Permission::getUserId));
        Map<String, String> datasetNameMap = loadDatasetNameMap();
        Map<String, Integer> datasetDocCountMap = new HashMap<>();
        Map<String, List<Map<String, Object>>> datasetGrantedUsersMap = loadDatasetGrantedUsersMap();
        List<Map<String, Object>> adminItems = new ArrayList<>();
        for (User admin : admins) {
            List<Permission> ownedPermissions = adminOwnedPermissionMap.getOrDefault(admin.getId(), List.of());
            List<Map<String, Object>> ownedDatasets = new ArrayList<>();
            int grantedPermissionCount = 0;
            Set<Long> uniqueGrantedUserIds = new HashSet<>();
            for (Permission permission : ownedPermissions) {
                String datasetId = permission.getResourceId();
                int documentCount = datasetDocCountMap.computeIfAbsent(datasetId, this::loadDocumentCount);
                List<Map<String, Object>> grantedUsers = datasetGrantedUsersMap.getOrDefault(datasetId, List.of());
                grantedPermissionCount += grantedUsers.size();
                for (Map<String, Object> user : grantedUsers) {
                    Object uid = user.get("userId");
                    if (uid instanceof Number number) {
                        uniqueGrantedUserIds.add(number.longValue());
                    }
                }
                Map<String, Object> datasetItem = new HashMap<>();
                datasetItem.put("datasetId", datasetId);
                datasetItem.put("datasetName", datasetNameMap.getOrDefault(datasetId, datasetId));
                datasetItem.put("datasetCreatedAt", permission.getCreateTime());
                datasetItem.put("documentCount", documentCount);
                datasetItem.put("grantedUsers", grantedUsers);
                ownedDatasets.add(datasetItem);
            }
            Map<String, List<UserConversationMessage>> messageMap = new HashMap<>();
            List<UserConversation> conversations = new ArrayList<>();
            int messageCount = 0;
            try {
                conversations = userConversationMapper.selectList(new LambdaQueryWrapper<UserConversation>()
                        .eq(UserConversation::getUserId, admin.getId())
                        .orderByDesc(UserConversation::getUpdatedAt)
                        .last("limit 20"));
                List<String> conversationIds = conversations.stream().map(UserConversation::getId).toList();
                if (!conversationIds.isEmpty()) {
                    List<UserConversationMessage> messages = userConversationMessageMapper.selectList(new LambdaQueryWrapper<UserConversationMessage>()
                            .in(UserConversationMessage::getConversationId, conversationIds)
                            .orderByAsc(UserConversationMessage::getId));
                    messageCount = messages.size();
                    messageMap = messages.stream().collect(Collectors.groupingBy(UserConversationMessage::getConversationId));
                }
            } catch (Exception ignore) {
                conversations = List.of();
                messageMap = Map.of();
                messageCount = 0;
            }
            List<Map<String, Object>> conversationItems = new ArrayList<>();
            for (UserConversation conversation : conversations) {
                List<UserConversationMessage> messages = messageMap.getOrDefault(conversation.getId(), List.of());
                List<Map<String, Object>> detail = messages.stream().map(m -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", m.getId());
                    item.put("role", m.getRole());
                    item.put("content", m.getContent());
                    item.put("recordTime", m.getCreatedAt());
                    return item;
                }).toList();
                conversationItems.add(Map.of(
                        "conversationId", conversation.getId(),
                        "title", conversation.getTitle(),
                        "createdAt", conversation.getCreatedAt(),
                        "updatedAt", conversation.getUpdatedAt(),
                        "messageCount", messages.size(),
                        "records", detail
                ));
            }
            Map<String, Object> adminItem = new HashMap<>();
            adminItem.put("adminUserId", admin.getId());
            adminItem.put("adminUsername", admin.getUsername());
            adminItem.put("ownedDatasetCount", ownedDatasets.size());
            adminItem.put("totalGrantedPermissionCount", grantedPermissionCount);
            adminItem.put("userOverviewCount", uniqueGrantedUserIds.size());
            adminItem.put("conversationOverviewCount", conversations.size());
            adminItem.put("conversationRecordCount", messageCount);
            adminItem.put("ownedDatasets", ownedDatasets);
            adminItem.put("conversations", conversationItems);
            adminItems.add(adminItem);
        }
        return Map.of("generatedAt", Instant.now().toString(), "admins", adminItems);
    }

    private User createUser(CreateUserRequest request, String role) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new BadRequestException("username 不能为空");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BadRequestException("password 不能为空");
        }
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (existing != null) {
            throw new BadRequestException("用户名已存在");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordCodecService.encode(request.getPassword()));
        user.setRole(role);
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }

    private void upsertPermission(Long userId, String resourceType, String resourceId) {
        if (userId == null || resourceId == null || resourceId.isBlank()) {
            throw new BadRequestException("授权参数不完整");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BadRequestException("目标用户不存在");
        }
        Long count = permissionMapper.selectCount(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, userId)
                .eq(Permission::getResourceType, resourceType)
                .eq(Permission::getResourceId, resourceId));
        if (count != null && count > 0) {
            return;
        }
        Permission permission = new Permission();
        permission.setUserId(userId);
        permission.setResourceType(resourceType);
        permission.setResourceId(resourceId);
        permissionMapper.insert(permission);
    }

    private JsonNode appendDatasetCreator(JsonNode jsonNode) {
        if (!(jsonNode instanceof ObjectNode root)) {
            return jsonNode;
        }
        JsonNode dataNode = root.path("data");
        if (!(dataNode instanceof ArrayNode arrayNode)) {
            return jsonNode;
        }
        List<Permission> owners = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getResourceType, "DATASET_OWNER")
                .orderByAsc(Permission::getCreateTime)
                .orderByAsc(Permission::getId));
        Map<String, Long> datasetOwnerMap = new HashMap<>();
        for (Permission permission : owners) {
            datasetOwnerMap.putIfAbsent(permission.getResourceId(), permission.getUserId());
        }
        Set<Long> ownerIds = new HashSet<>(datasetOwnerMap.values());
        Map<Long, User> ownerUserMap = ownerIds.isEmpty() ? Map.of() :
                userMapper.selectBatchIds(ownerIds).stream().collect(Collectors.toMap(User::getId, u -> u));
        for (JsonNode item : arrayNode) {
            if (!(item instanceof ObjectNode datasetNode)) {
                continue;
            }
            String datasetId = datasetNode.path("id").asText("");
            Long ownerId = datasetOwnerMap.get(datasetId);
            if (ownerId == null) {
                continue;
            }
            User owner = ownerUserMap.get(ownerId);
            datasetNode.put("creatorUserId", ownerId);
            datasetNode.put("creatorUsername", owner == null ? "" : owner.getUsername());
        }
        return root;
    }

    private void bindDatasetOwnerPermission(Long userId, JsonNode response) {
        if (userId == null || response == null) {
            return;
        }
        String datasetId = extractDatasetId(response);
        if (datasetId == null || datasetId.isBlank()) {
            return;
        }
        upsertPermission(userId, "DATASET_OWNER", datasetId);
    }

    private void cleanupDatasetPermission(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            return;
        }
        permissionMapper.delete(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getResourceId, datasetId)
                .in(Permission::getResourceType, List.of("DATASET", "DATASET_OWNER")));
    }

    private void validateDatasetDeletePermission(AuthenticatedUser current, String datasetId) {
        if (current == null || datasetId == null || datasetId.isBlank()) {
            throw new ForbiddenException("缺少删除知识库所需参数");
        }
        if ("super_admin".equalsIgnoreCase(current.getRole())) {
            return;
        }
        Permission ownerPermission = permissionMapper.selectOne(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getUserId, current.getUserId())
                .eq(Permission::getResourceType, "DATASET_OWNER")
                .eq(Permission::getResourceId, datasetId)
                .last("limit 1"));
        if (ownerPermission == null) {
            throw new ForbiddenException("admin 仅可删除自己创建的知识库及内部内容");
        }
    }

    private String extractDatasetId(JsonNode response) {
        String fromData = response.path("data").path("id").asText("");
        if (!fromData.isBlank()) {
            return fromData;
        }
        return response.path("id").asText("");
    }

    private Map<String, String> loadDatasetNameMap() {
        try {
            JsonNode json = ragFlowClient.listDatasets(1, 1000).block();
            if (json == null || !json.path("data").isArray()) {
                return Map.of();
            }
            Map<String, String> datasetMap = new HashMap<>();
            for (JsonNode item : json.path("data")) {
                String id = item.path("id").asText("");
                if (!id.isBlank()) {
                    datasetMap.put(id, item.path("name").asText(id));
                }
            }
            return datasetMap;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private int loadDocumentCount(String datasetId) {
        try {
            JsonNode json = ragFlowClient.listDocuments(datasetId, 1, 1).block();
            if (json == null) {
                return 0;
            }
            JsonNode data = json.path("data");
            if (data.has("total")) {
                return data.path("total").asInt(0);
            }
            JsonNode docs = data.path("docs");
            return docs.isArray() ? docs.size() : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    private Map<String, List<Map<String, Object>>> loadDatasetGrantedUsersMap() {
        List<Permission> datasetPermissions = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getResourceType, "DATASET"));
        Set<Long> userIds = datasetPermissions.stream().map(Permission::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userIds.isEmpty() ? Map.of() :
                userMapper.selectBatchIds(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        for (Permission permission : datasetPermissions) {
            User user = userMap.get(permission.getUserId());
            if (user == null) {
                continue;
            }
            Map<String, Object> userItem = new HashMap<>();
            userItem.put("userId", user.getId());
            userItem.put("username", user.getUsername());
            userItem.put("role", user.getRole());
            userItem.put("authorizedAt", permission.getCreateTime());
            result.computeIfAbsent(permission.getResourceId(), key -> new ArrayList<>()).add(userItem);
        }
        return result;
    }

    private AuthenticatedUser requireCurrentUser() {
        AuthenticatedUser user = AuthContextHolder.get();
        if (user == null) {
            throw new ForbiddenException("认证上下文不存在");
        }
        return user;
    }

    private boolean isAdminLikeRole(String role) {
        return "admin".equalsIgnoreCase(role) || "super_admin".equalsIgnoreCase(role);
    }

    private AuthenticatedUser requireAdminLikeUser() {
        AuthenticatedUser user = requireCurrentUser();
        if (!isAdminLikeRole(user.getRole())) {
            throw new ForbiddenException("仅 admin/super_admin 可执行该操作");
        }
        return user;
    }

    private AuthenticatedUser requireSuperAdminUser() {
        AuthenticatedUser user = requireCurrentUser();
        if (!"super_admin".equalsIgnoreCase(user.getRole())) {
            throw new RuntimeException("仅 super_admin 可执行该操作");
        }
        return user;
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    private static class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    private static class ForbiddenException extends RuntimeException {
        ForbiddenException(String message) {
            super(message);
        }
    }

    @Data
    /**
     * 创建用户请求体。
     */
    public static class CreateUserRequest {
        private String username;
        private String password;
        private String name;
    }

    @Data
    /**
     * 分配数据集权限请求体。
     */
    public static class GrantDatasetPermissionRequest {
        private Long targetUserId;
        private String datasetId;
        private String action;
    }

    @Data
    /**
     * 分配技能权限请求体。
     */
    public static class GrantSkillPermissionRequest {
        private Long targetUserId;
        private String skillCode;
        private String action;
    }
}
