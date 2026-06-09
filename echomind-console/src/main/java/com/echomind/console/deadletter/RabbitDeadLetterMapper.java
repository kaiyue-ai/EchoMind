package com.echomind.console.deadletter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface RabbitDeadLetterMapper extends MybatisPlusMapper<RabbitDeadLetterEntity> {

    @Insert("""
        INSERT IGNORE INTO echomind_rabbitmq_dead_letters
          (message_hash, dlq_name, message_type, business_key, trace_id, payload_json, error_headers_json,
           status, replay_count, archived_at)
        VALUES
          (#{messageHash}, #{dlqName}, #{messageType}, #{businessKey}, #{traceId}, #{payloadJson}, #{errorHeadersJson},
           #{status}, #{replayCount}, #{archivedAt})
        """)
    int insertIgnore(RabbitDeadLetterEntity entity);

    @Select("""
        SELECT *
        FROM echomind_rabbitmq_dead_letters
        WHERE message_hash = #{messageHash}
        """)
    RabbitDeadLetterEntity selectByMessageHash(@Param("messageHash") String messageHash);

    @Update("""
        UPDATE echomind_rabbitmq_dead_letters
        SET status = #{status},
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE id = #{id}
        """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("""
        UPDATE echomind_rabbitmq_dead_letters
        SET status = #{status},
            replay_count = replay_count + 1,
            replayed_at = #{replayedAt},
            last_replay_error = NULL,
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE id = #{id}
        """)
    int markReplaySuccess(@Param("id") Long id,
                          @Param("status") String status,
                          @Param("replayedAt") Instant replayedAt);

    @Update("""
        UPDATE echomind_rabbitmq_dead_letters
        SET status = #{status},
            replay_count = replay_count + 1,
            last_replay_error = #{error},
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE id = #{id}
        """)
    int markReplayFailure(@Param("id") Long id, @Param("status") String status, @Param("error") String error);

    default List<RabbitDeadLetterEntity> selectLatest(String status, int limit) {
        var query = Wrappers.lambdaQuery(RabbitDeadLetterEntity.class);
        if (status != null && !status.isBlank()) {
            query.eq(RabbitDeadLetterEntity::getStatus, status);
        }
        return selectList(query
            .orderByDesc(RabbitDeadLetterEntity::getArchivedAt)
            .last("limit " + Math.max(1, limit)));
    }
}
