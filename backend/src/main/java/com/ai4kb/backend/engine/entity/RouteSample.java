package com.ai4kb.backend.engine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_route_sample")
/**
 * 路由样本实体。
 * 记录一次路由决策在本地规则与 Planner 两侧的关键信息，用于审计与训练。
 */
public class RouteSample {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    private Long userId;

    private String source;

    private String chosenRoute;

    private Double chosenConfidence;

    private String chosenTool;

    private String localRoute;

    private Double localConfidence;

    private String plannerRoute;

    private Double plannerConfidence;

    private String queryText;

    private LocalDateTime createdAt;
}
