package com.citygo;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 对着本机 Redis 跑秒杀脚本。不启动 Spring，避免占用 8081。
 */
public class SeckillLuaTest {

    private static final String VOUCHER = "99001";
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private String seckillScript;
    private String restoreScript;

    @BeforeEach
    public void setUp() throws Exception {
        client = RedisClient.create("redis://127.0.0.1:6379");
        connection = client.connect();
        redis = connection.sync();
        seckillScript = read("src/main/resources/seckill.lua");
        restoreScript = read("src/main/resources/seckill_restore.lua");
        redis.del(stock(), legacy(), voucher(), hold(), pending("100"), pending("200"));
        redis.set(stock(), "1");
        redis.hset(voucher(), "beginTime", "1");
        redis.hset(voucher(), "endTime", "9999999999");
    }

    @AfterEach
    public void tearDown() {
        if (redis != null) {
            redis.del(stock(), legacy(), voucher(), hold(), pending("100"), pending("200"));
        }
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    @Test
    public void restoreIgnoresANewerHold() {
        Long first = eval(seckillScript, "7", "100");
        Assertions.assertEquals(Long.valueOf(0), first);
        Assertions.assertEquals("0", redis.get(stock()));
        Assertions.assertEquals("7", redis.hget(pending("100"), "userId"));
        Assertions.assertEquals("waiting", redis.hget(pending("100"), "state"));
        Assertions.assertEquals("99001", redis.hget(pending("100"), "voucherId"));

        Long wrong = evalRestore("7", "200");
        Assertions.assertEquals(Long.valueOf(0), wrong);
        Assertions.assertEquals("100", redis.hget(hold(), "7"));
        Assertions.assertEquals("0", redis.get(stock()));

        Long right = evalRestore("7", "100");
        Assertions.assertEquals(Long.valueOf(1), right);
        Assertions.assertNull(redis.hget(hold(), "7"));
        Assertions.assertEquals("1", redis.get(stock()));

        Long again = eval(seckillScript, "7", "200");
        Assertions.assertEquals(Long.valueOf(0), again);
        Assertions.assertEquals("200", redis.hget(hold(), "7"));
    }

    @Test
    public void secondRestoreDoesNothing() {
        Assertions.assertEquals(Long.valueOf(0), eval(seckillScript, "8", "100"));
        Assertions.assertEquals(Long.valueOf(1), evalRestore("8", "100"));
        Assertions.assertEquals(Long.valueOf(0), evalRestore("8", "100"));
        Assertions.assertEquals("1", redis.get(stock()));
    }

    private Long eval(String script, String userId, String orderId) {
        return redis.eval(script, ScriptOutputType.INTEGER,
                new String[]{stock(), legacy(), voucher(), hold(), pending(orderId)},
                userId, orderId, VOUCHER, "0");
    }

    private Long evalRestore(String userId, String orderId) {
        return redis.eval(restoreScript, ScriptOutputType.INTEGER,
                new String[]{stock(), hold(), pending(orderId)},
                userId, orderId);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String stock() {
        return "seckill:stock:" + VOUCHER;
    }

    private static String legacy() {
        return "seckill:order:" + VOUCHER;
    }

    private static String voucher() {
        return "seckill:voucher:" + VOUCHER;
    }

    private static String hold() {
        return "seckill:hold:" + VOUCHER;
    }

    private static String pending(String orderId) {
        return "seckill:pending:" + orderId;
    }
}
