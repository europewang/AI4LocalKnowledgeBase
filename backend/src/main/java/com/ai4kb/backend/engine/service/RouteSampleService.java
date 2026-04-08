package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.entity.RouteSample;
import com.ai4kb.backend.engine.mapper.RouteSampleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    public RouteSamplePageResult pageSamples(RouteSampleQuery query) {
        RouteSampleQuery safeQuery = query == null ? new RouteSampleQuery() : query;
        int safePage = Math.max(1, safeQuery.page());
        int safePageSize = Math.max(1, Math.min(safeQuery.pageSize(), 100));
        int offset = (safePage - 1) * safePageSize;
        LambdaQueryWrapper<RouteSample> wrapper = new LambdaQueryWrapper<RouteSample>()
                .orderByDesc(RouteSample::getCreatedAt)
                .orderByDesc(RouteSample::getId);
        if (safeQuery.userId() != null) {
            wrapper.eq(RouteSample::getUserId, safeQuery.userId());
        }
        if (safeQuery.source() != null && !safeQuery.source().isBlank()) {
            wrapper.eq(RouteSample::getSource, safeQuery.source().trim());
        }
        if (safeQuery.chosenRoute() != null && !safeQuery.chosenRoute().isBlank()) {
            wrapper.eq(RouteSample::getChosenRoute, safeQuery.chosenRoute().trim().toUpperCase(Locale.ROOT));
        }
        if (safeQuery.startTime() != null) {
            wrapper.ge(RouteSample::getCreatedAt, safeQuery.startTime());
        }
        if (safeQuery.endTime() != null) {
            wrapper.le(RouteSample::getCreatedAt, safeQuery.endTime());
        }
        if (safeQuery.queryKeyword() != null && !safeQuery.queryKeyword().isBlank()) {
            wrapper.like(RouteSample::getQueryText, safeQuery.queryKeyword().trim());
        }
        long total = routeSampleMapper.selectCount(wrapper);
        // 与会话分页保持一致，避免插件失效时返回全量数据。
        wrapper.last("limit " + safePageSize + " offset " + offset);
        List<RouteSample> items = routeSampleMapper.selectList(wrapper);
        return new RouteSamplePageResult(
                items == null ? List.of() : items,
                total,
                safePage,
                safePageSize,
                safePage * safePageSize < total
        );
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

    public List<String> listRoutes() {
        QueryWrapper<RouteSample> wrapper = new QueryWrapper<RouteSample>()
                .select("distinct chosen_route")
                .isNotNull("chosen_route")
                .ne("chosen_route", "")
                .orderByAsc("chosen_route");
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

    /**
     * 删除指定时间点之前的样本，用于审计数据定期清理。
     */
    public int deleteBefore(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        return routeSampleMapper.delete(new LambdaQueryWrapper<RouteSample>()
                .lt(RouteSample::getCreatedAt, cutoff));
    }

    public record RouteSampleQuery(
            int page,
            int pageSize,
            Long userId,
            String source,
            String chosenRoute,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String queryKeyword
    ) {
        public RouteSampleQuery() {
            this(1, 20, null, null, null, null, null, null);
        }
    }

    public record RouteSamplePageResult(
            List<RouteSample> items,
            long total,
            int page,
            int pageSize,
            boolean hasMore
    ) {
    }
}
