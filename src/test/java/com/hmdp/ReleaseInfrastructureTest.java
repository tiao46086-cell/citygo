package com.hmdp;

import com.hmdp.service.impl.VoucherServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

import static org.mockito.Mockito.*;

class ReleaseInfrastructureTest {
    private final String prefix = "release-test:" + System.nanoTime();
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private String[] demoKeys;

    @BeforeEach
    void connect() {
        client = RedisClient.create("redis://127.0.0.1:6379");
        connection = client.connect();
        redis = connection.sync();
        demoKeys = new String[]{prefix + ":stock", prefix + ":time", prefix + ":hold", prefix + ":legacy"};
    }

    @AfterEach
    void cleanup() {
        redis.del(demoKeys);
        redis.del(prefix + ":id");
        connection.close();
        client.shutdown();
    }

    @Test
    void businessTimeDoesNotDependOnJvmTimezone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            VoucherServiceImpl service = new VoucherServiceImpl();
            LocalDateTime time = LocalDateTime.of(2026, 10, 12, 12, 0);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Long utc = ReflectionTestUtils.invokeMethod(service, "toEpochSecond", time);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Long shanghai = ReflectionTestUtils.invokeMethod(service, "toEpochSecond", time);
            Assertions.assertEquals(Instant.parse("2026-10-12T04:00:00Z").getEpochSecond(), utc.longValue());
            Assertions.assertEquals(utc, shanghai);
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void utcIdCandidateIsIndependentOfLocalTimezone() {
        TimeZone previous = TimeZone.getDefault();
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        RedisIdWorker worker = new RedisIdWorker();
        ReflectionTestUtils.setField(worker, "stringRedisTemplate", template);
        ReflectionTestUtils.setField(worker, "jdbcTemplate", jdbc);
        // Mockito 展开 varargs；返回真实 UTC 候选值，检查高位时间戳。
        when(template.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(2));
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            long before = Instant.now().getEpochSecond();
            long timestamp = (worker.nextId("order") >>> 32) + 1640995200L;
            Assertions.assertTrue(timestamp >= before && timestamp <= Instant.now().getEpochSecond());
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void idScriptPreservesLongPrecisionAndLegacyCutoff() throws Exception {
        String script = read("id_monotonic.lua");
        String floor = "650000000000000123";
        String next = redis.eval(script, ScriptOutputType.VALUE, new String[]{prefix + ":id"}, "640000000000000001", floor);
        Assertions.assertEquals("650000000000000124", next);
        Assertions.assertEquals("650000000000000125", redis.eval(script, ScriptOutputType.VALUE,
                new String[]{prefix + ":id"}, "640000000000000001", floor));
        redis.del(prefix + ":id");
        Assertions.assertEquals("650000000000000124", redis.eval(script, ScriptOutputType.VALUE,
                new String[]{prefix + ":id"}, "640000000000000001", floor));
    }

    @Test
    void concurrentIdRequestsAreUnique() throws Exception {
        String script = read("id_monotonic.lua");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Set<String> ids = new HashSet<>();
            java.util.List<Future<String>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                calls.add(pool.submit(() -> redis.eval(script, ScriptOutputType.VALUE,
                        new String[]{prefix + ":id"}, "650000000000000123", "0")));
            }
            for (Future<String> call : calls) {
                Assertions.assertTrue(ids.add(call.get(10, TimeUnit.SECONDS)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void demoInitializationIsAtomicAndDoesNotResetStockOrHolds() throws Exception {
        String script = read("seckill_demo_init.lua");
        Assertions.assertEquals(Long.valueOf(1), redis.eval(script, ScriptOutputType.INTEGER, demoKeys, "20", "1", "9999999999"));
        redis.set(demoKeys[0], "17");
        Assertions.assertEquals(Long.valueOf(0), redis.eval(script, ScriptOutputType.INTEGER, demoKeys, "20", "1", "9999999999"));
        Assertions.assertEquals("17", redis.get(demoKeys[0]));
        redis.del(demoKeys[0], demoKeys[1]);
        redis.hset(demoKeys[2], "7", "123");
        Assertions.assertEquals(Long.valueOf(0), redis.eval(script, ScriptOutputType.INTEGER, demoKeys, "20", "1", "9999999999"));
        Assertions.assertNull(redis.get(demoKeys[0]));
    }

    private String read(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources", name)), StandardCharsets.UTF_8);
    }
}
