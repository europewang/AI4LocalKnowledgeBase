package com.ai4kb.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_permission")
public class Permission {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String resourceType; // DATASET, SKILL
    private String resourceId;
    private LocalDateTime createTime;
}
