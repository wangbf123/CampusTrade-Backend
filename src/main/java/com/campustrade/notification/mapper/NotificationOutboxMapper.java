package com.campustrade.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.notification.model.NotificationOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NotificationOutboxMapper extends BaseMapper<NotificationOutboxEvent> {

    @Select("""
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP(6))
            ORDER BY next_retry_at, created_at, id LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<NotificationOutboxEvent> selectPendingClaimCandidates(@Param("limit") int limit);

    @Select("""
            SELECT * FROM notification_outbox
            WHERE status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP(6)
            ORDER BY lease_until, id LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<NotificationOutboxEvent> selectExpiredClaimCandidates(@Param("limit") int limit);

    @Update("""
            UPDATE notification_outbox
            SET status = 'PROCESSING', claim_token = #{token},
                lease_until = TIMESTAMPADD(SECOND, #{seconds}, CURRENT_TIMESTAMP(6)), updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = #{id}
            """)
    int claim(@Param("id") Long id, @Param("token") String token, @Param("seconds") long seconds);

    @Update("""
            UPDATE notification_outbox
            SET lease_until = TIMESTAMPADD(SECOND, #{seconds}, CURRENT_TIMESTAMP(6)), updated_at = CURRENT_TIMESTAMP(6)
            WHERE event_id = #{eventId} AND status = 'PROCESSING' AND claim_token = #{token}
              AND lease_until > CURRENT_TIMESTAMP(6)
            """)
    int renewLease(@Param("eventId") String eventId, @Param("token") String token, @Param("seconds") long seconds);

    @Update("""
            UPDATE notification_outbox
            SET status = 'PUBLISHED', claim_token = NULL, lease_until = NULL, last_error = NULL,
                next_retry_at = NULL, updated_at = CURRENT_TIMESTAMP(6)
            WHERE event_id = #{eventId} AND status = 'PROCESSING' AND claim_token = #{token}
              AND lease_until > CURRENT_TIMESTAMP(6)
            """)
    int markPublished(@Param("eventId") String eventId, @Param("token") String token);

    // MySQL single-table UPDATE evaluates assignments left-to-right: compute status before incrementing retry_count.
    @Update("""
            UPDATE notification_outbox
            SET status = CASE WHEN retry_count + 1 >= #{maxRetry} THEN 'FAILED' ELSE 'PENDING' END,
                retry_count = retry_count + 1, last_error = #{reason},
                next_retry_at = TIMESTAMPADD(SECOND, #{delay}, CURRENT_TIMESTAMP(6)),
                claim_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP(6)
            WHERE event_id = #{eventId} AND status = 'PROCESSING' AND claim_token = #{token}
              AND lease_until > CURRENT_TIMESTAMP(6)
            """)
    int markFailed(@Param("eventId") String eventId, @Param("token") String token,
                   @Param("reason") String reason, @Param("delay") long delay, @Param("maxRetry") int maxRetry);

    @Select("SELECT COUNT(*) FROM notification_outbox WHERE status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP(6))")
    long countPendingDue();

    @Select("SELECT COUNT(*) FROM notification_outbox WHERE status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP(6)")
    long countExpiredLeases();

    @Select("""
            SELECT COALESCE(GREATEST(0, TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP(6))), 0)
            FROM notification_outbox WHERE status IN ('PENDING', 'PROCESSING')
            """)
    long oldestUnpublishedAgeSeconds();
}
