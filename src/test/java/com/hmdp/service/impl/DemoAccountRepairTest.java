package com.hmdp.service.impl;

import com.hmdp.entity.User;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

import javax.annotation.Resource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.DEMO_SESSION_MINUTES;
import static com.hmdp.utils.RedisConstants.DEMO_TOKEN_MARK;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false"
})
public class DemoAccountRepairTest {

    private static final long LIKE_USER = 99021991L;
    private static final long OTHER_LIKER = 99021992L;
    private static final String PHONE = "12000000901";

    @Resource
    private UserServiceImpl userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private final List<String> tokens = new ArrayList<>();
    private Long blogId;
    private Long userId;

    @AfterEach
    public void cleanup() {
        UserHolder.removeUser();
        for (String token : tokens) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        if (blogId != null) {
            stringRedisTemplate.delete(BLOG_LIKED_KEY + blogId);
            jdbcTemplate.update("DELETE FROM tb_blog WHERE id = ?", blogId);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", userId);
        }
    }

    @Test
    public void onlyOneCallerBindsTheSameIdleAccount() throws Exception {
        userId = insertUser();
        User user = userService.getById(userId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> {
                start.await();
                return userService.claimDemoSession(user);
            });
            Future<String> second = pool.submit(() -> {
                start.await();
                return userService.claimDemoSession(user);
            });
            start.countDown();
            String tokenA = first.get(5, TimeUnit.SECONDS);
            String tokenB = second.get(5, TimeUnit.SECONDS);
            int bound = (tokenA == null ? 0 : 1) + (tokenB == null ? 0 : 1);
            Assertions.assertEquals(1, bound);
            String token = tokenA == null ? tokenB : tokenA;
            tokens.add(token);
            Assertions.assertEquals("1", stringRedisTemplate.opsForHash().get(LOGIN_USER_KEY + token, DEMO_TOKEN_MARK));
            Assertions.assertNull(userService.claimDemoSession(user));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void demoTokenRefreshStaysAtTwoHours() {
        String token = "demottlcheck";
        tokens.add(token);
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().put(key, "id", "1");
        stringRedisTemplate.opsForHash().put(key, "nickName", "体验");
        stringRedisTemplate.opsForHash().put(key, DEMO_TOKEN_MARK, "1");
        stringRedisTemplate.expire(key, 1, TimeUnit.MINUTES);
        refresh(token);
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        Assertions.assertNotNull(ttl);
        Assertions.assertTrue(ttl <= DEMO_SESSION_MINUTES * 60);
        Assertions.assertTrue(ttl > DEMO_SESSION_MINUTES * 60 - 30);
    }

    @Test
    public void phoneLoginRefreshStaysLong() {
        String token = "phonettlcheck";
        tokens.add(token);
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().put(key, "id", "2");
        stringRedisTemplate.opsForHash().put(key, "nickName", "手机");
        stringRedisTemplate.expire(key, 1, TimeUnit.MINUTES);
        refresh(token);
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        Assertions.assertNotNull(ttl);
        Assertions.assertTrue(ttl > DEMO_SESSION_MINUTES * 60);
        Assertions.assertTrue(ttl <= LOGIN_USER_TTL * 60);
    }

    @Test
    public void removingALikeDecrementsOnlyWhenTheMemberExisted() {
        blogId = insertBlog(2);
        stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + blogId, String.valueOf(LIKE_USER), 1);
        stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + blogId, String.valueOf(OTHER_LIKER), 2);
        userService.clearDemoLikes(LIKE_USER);
        Assertions.assertEquals(Integer.valueOf(1), liked());
        Assertions.assertNull(stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blogId, String.valueOf(LIKE_USER)));
        Assertions.assertNotNull(stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blogId, String.valueOf(OTHER_LIKER)));
        userService.clearDemoLikes(LIKE_USER);
        Assertions.assertEquals(Integer.valueOf(1), liked());
    }

    private void refresh(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("authorization", token);
        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(stringRedisTemplate);
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
    }

    private Long insertUser() {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_user (phone, nick_name) VALUES (?, 'claim-test')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, PHONE);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }

    private Long insertBlog(int liked) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_blog (shop_id, user_id, title, images, content, liked, comments) VALUES (1, 2, 'like-repair', '/imgs/a.jpg', 'test', ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, liked);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }

    private Integer liked() {
        return jdbcTemplate.queryForObject("SELECT liked FROM tb_blog WHERE id = ?", Integer.class, blogId);
    }
}
