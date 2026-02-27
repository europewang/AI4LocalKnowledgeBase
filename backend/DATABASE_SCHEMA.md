# 数据库结构文档 (Database Schema)

本文档记录了 `AI4LocalKnowledgeBase` 后端数据库的表结构。

## 1. 用户表 (t_user)

**表名:** `t_user`
**实体类:** `com.ai4kb.backend.entity.User`

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `BIGINT` (PK) | 主键，自增 |
| `username` | `VARCHAR` | 用户名，唯一 |
| `role` | `VARCHAR` | 用户角色 (例如: `admin`, `user`) |
| `create_time` | `DATETIME` | 创建时间 |

## 2. 权限表 (t_permission)

**表名:** `t_permission`
**实体类:** `com.ai4kb.backend.entity.Permission`

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `BIGINT` (PK) | 主键，自增 |
| `user_id` | `BIGINT` | 关联用户 ID (`t_user.id`) |
| `resource_type` | `VARCHAR` | 资源类型 (例如: `dataset`, `skill`) |
| `resource_id` | `VARCHAR` | 资源 ID (例如: 数据集 UUID) |
| `create_time` | `DATETIME` | 创建时间 |

## 3. 关系说明

- **用户与权限**: 一对多关系 (`User` 1 : N `Permission`)
- **权限与资源**: 多对一关系 (`Permission` N : 1 Resource)
  - `resource_id` 对应外部资源 ID (如 RAGFlow 中的 dataset_id)

## 4. 初始化数据

数据库初始化脚本通常位于 `deploy/init.sql` 或通过代码自动创建。

---

**注意**: 本项目使用了 MyBatis-Plus 框架，表名与实体类名的映射遵循驼峰转下划线规则，或通过 `@TableName` 注解指定。
