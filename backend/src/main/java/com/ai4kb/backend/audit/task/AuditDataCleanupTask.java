package com.ai4kb.backend.audit.task;

import com.ai4kb.backend.engine.service.RouteSampleService;
import com.ai4kb.backend.skill.service.DynamicSkillAuditService;
import com.ai4kb.backend.user.service.UserConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 审计数据自动清理任务。
 * 按配置周期删除超过保留天数的路由样本与技能调用审计。
 */
public class AuditDataCleanupTask {

    private final RouteSampleService routeSampleService;
    private final DynamicSkillAuditService dynamicSkillAuditService;
    private final UserConversationService userConversationService;

    @Value("${ai4kb.audit-cleanup.enabled:true}")
    private boolean enabled;

    @Value("${ai4kb.audit-cleanup.keep-days:90}")
    private int keepDays;

    /**
     * 默认每 3 个月执行一次（可通过配置覆盖 cron）。
     */
    @Scheduled(cron = "${ai4kb.audit-cleanup.cron:0 30 3 1 */3 *}")
    public void cleanup() {
        if (!enabled) {
            return;
        }
        int safeKeepDays = Math.max(1, keepDays);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(safeKeepDays);
        int deletedRouteSamples = routeSampleService.deleteBefore(cutoff);
        int deletedSkillAudits = dynamicSkillAuditService.deleteBefore(cutoff);
        int deletedConversations = userConversationService.deleteBefore(cutoff);
        log.info("audit_cleanup_done: keep_days={} cutoff={} deleted_route_samples={} deleted_skill_audits={} deleted_conversations={}",
                safeKeepDays, cutoff, deletedRouteSamples, deletedSkillAudits, deletedConversations);
    }
}
