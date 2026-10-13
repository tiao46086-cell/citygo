package com.hmdp.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 仅初始化 SQL 演示券 10。不是 Redis 丢失后的生产库存重建工具。 */
@Component
@ConditionalOnProperty(name = "citygo.demo-seckill-init", havingValue = "true")
public class DemoSeckillInitializer implements ApplicationRunner {
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.stock, DATE_FORMAT(s.begin_time, '%Y-%m-%dT%H:%i:%s') AS begin_time, "
                        + "DATE_FORMAT(s.end_time, '%Y-%m-%dT%H:%i:%s') AS end_time "
                        + "FROM tb_seckill_voucher s JOIN tb_voucher v ON v.id=s.voucher_id "
                        + "WHERE s.voucher_id=10 AND v.type=1 AND v.status=1 AND s.stock=20 "
                        + "AND NOT EXISTS (SELECT 1 FROM tb_voucher_order WHERE voucher_id=10)");
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> row = rows.get(0);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        String begin = String.valueOf(LocalDateTime.parse(row.get("begin_time").toString()).atZone(zone).toEpochSecond());
        String end = String.valueOf(LocalDateTime.parse(row.get("end_time").toString()).atZone(zone).toEpochSecond());
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill_demo_init.lua"));
        script.setResultType(Long.class);
        stringRedisTemplate.execute(script, Arrays.asList("seckill:stock:10", "seckill:voucher:10",
                "seckill:hold:10", "seckill:order:10"), row.get("stock").toString(), begin, end);
    }
}
