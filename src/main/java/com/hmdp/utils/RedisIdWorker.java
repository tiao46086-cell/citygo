package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

import static com.hmdp.utils.RedisConstants.ICR_ID_KEY;

/**
 * 用 Redis 自增生成全局唯一 long 型 id。
 * tb_voucher_order.id 是调用方传入，数据库不会自增。
 */
@Component
public class RedisIdWorker {

    /**
     * 2022-01-01 00:00:00 UTC。时间戳只保留「从这天起过了多少秒」，数字更小。
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号占低 32 位，一天内同一个前缀最多约 42 亿个。
     */
    private static final int COUNT_BITS = 32;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private static final DefaultRedisScript<String> MONOTONIC_SCRIPT =
            new DefaultRedisScript<>();
    static {
        MONOTONIC_SCRIPT.setLocation(new ClassPathResource("id_monotonic.lua"));
        MONOTONIC_SCRIPT.setResultType(String.class);
    }

    public long nextId(String keyPrefix) {
        Instant now = Instant.now();
        long timestamp = now.getEpochSecond() - BEGIN_TIMESTAMP;
        // 低位：按天一个 key，INCR 从 1 开始，同一天内递增
        String date = DATE_FORMATTER.withZone(ZoneOffset.UTC).format(now);
        long count = stringRedisTemplate.opsForValue().increment(ICR_ID_KEY + keyPrefix + ":" + date);
        if (count > 0xffffffffL) {
            throw new IllegalStateException("当天订单号序列已耗尽");
        }
        long candidate = timestamp << COUNT_BITS | count;
        // 兼容旧版把北京时间当 UTC 的 ID，以及体验账号的历史分界。
        // 每次读取 SQL 下界，Redis 恢复或切换实例后也不退号；不修改旧订单。
        Long floor = jdbcTemplate.queryForObject(
                "SELECT GREATEST(COALESCE((SELECT MAX(id) FROM tb_voucher_order), 0), "
                        + "COALESCE((SELECT MAX(demo_order_after) FROM tb_user), 0))", Long.class);
        String id = stringRedisTemplate.execute(MONOTONIC_SCRIPT,
                Collections.singletonList(ICR_ID_KEY + keyPrefix + ":last"),
                String.valueOf(candidate), String.valueOf(floor == null ? 0L : floor));
        if (id == null) {
            throw new IllegalStateException("订单号生成失败");
        }
        return Long.parseLong(id);
    }
}
