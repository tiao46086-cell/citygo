package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

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

    public long nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();
        // 高位：从起始时间到现在的秒数。时区固定用 UTC，只要每天同一套算法即可
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        // 低位：按天一个 key，INCR 从 1 开始，同一天内递增
        String date = now.format(DATE_FORMATTER);
        long count = stringRedisTemplate.opsForValue().increment(ICR_ID_KEY + keyPrefix + ":" + date);
        // 时间戳左移 32 位，再把序列号填进低 32 位
        return timestamp << COUNT_BITS | count;
    }
}
