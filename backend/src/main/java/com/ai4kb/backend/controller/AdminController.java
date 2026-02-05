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
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

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

    @PostMapping("/user")
    public User createUser(@RequestBody User user) {
        userMapper.insert(user);
        return user;
    }

    @PostMapping("/permission/grant")
    public String grantPermission(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String resourceType = body.get("resource_type");
        String resourceId = body.get("resource_id");

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new RuntimeException("User not found");
        }

        Permission permission = new Permission();
        permission.setUserId(user.getId());
        permission.setResourceType(resourceType);
        permission.setResourceId(resourceId);
        
        try {
            permissionMapper.insert(permission);
            return "ok";
        } catch (Exception e) {
            return "fail: " + e.getMessage();
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
