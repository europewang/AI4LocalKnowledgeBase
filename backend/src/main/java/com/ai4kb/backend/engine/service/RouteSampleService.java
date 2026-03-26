package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.entity.RouteSample;
import com.ai4kb.backend.engine.mapper.RouteSampleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 路由样本服务。
 * 提供样本持久化与管理员查询能力，用于后续路由效果审计与训练数据沉淀。
 */
public class RouteSampleService {

    private final RouteSampleMapper routeSampleMapper;

    /**
     * 保存单条路由样本，失败仅记录日志不影响主链路。
     */
    public void saveSample(RouteSample sample) {
        if (sample == null) {
            return;
        }
        try {
            routeSampleMapper.insert(sample);
        } catch (Exception e) {
            log.warn("route_sample_persist_failed: conversation_id={} source={}", sample.getConversationId(), sample.getSource(), e);
        }
    }

    /**
     * 按条件查询样本列表，limit 自动约束在 1~500。
     */
    public List<RouteSample> listSamples(int limit, Long userId, String source) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        LambdaQueryWrapper<RouteSample> wrapper = new LambdaQueryWrapper<RouteSample>()
                .orderByDesc(RouteSample::getCreatedAt)
                .orderByDesc(RouteSample::getId)
                .last("limit " + safeLimit);
        if (userId != null) {
            wrapper.eq(RouteSample::getUserId, userId);
        }
        if (source != null && !source.isBlank()) {
            wrapper.eq(RouteSample::getSource, source.trim());
        }
        return routeSampleMapper.selectList(wrapper);
    }

    /**
     * 查询已存在样本来源枚举，统一转大写并去重。
     */
    public List<String> listSources() {
        QueryWrapper<RouteSample> wrapper = new QueryWrapper<RouteSample>()
                .select("distinct source")
                .isNotNull("source")
                .ne("source", "")
                .orderByAsc("source");
        List<Object> objects = routeSampleMapper.selectObjs(wrapper);
        List<String> result = new ArrayList<>();
        for (Object object : objects) {
            if (object == null) {
                continue;
            }
            String value = object.toString().trim();
            if (value.isEmpty()) {
                continue;
            }
            result.add(value.toUpperCase(Locale.ROOT));
        }
        return result.stream().filter(Objects::nonNull).distinct().toList();
    }
}
