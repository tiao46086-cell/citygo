package com.hmdp;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.impl.BlogServiceImpl;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class BlogLikeFailureTest {
    private BlogServiceImpl service;
    private ZSetOperations<String, String> zset;
    private UpdateChainWrapper<Blog> update;
    private RLock lock;

    @BeforeEach
    void setup() {
        service = spy(new BlogServiceImpl());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        RedissonClient redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        ReflectionTestUtils.setField(service, "redissonClient", redisson);
        doReturn(new Blog()).when(service).getById(1L);
        update = mock(UpdateChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(service).update();
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void failedLikeRemovesRedisMemberAndUnlocks() {
        when(zset.score("blog:liked:1", "7")).thenReturn(null);
        when(zset.add(eq("blog:liked:1"), eq("7"), anyDouble())).thenReturn(true);
        when(update.update()).thenThrow(new IllegalStateException("SQL failure"));
        Assertions.assertThrows(IllegalStateException.class, () -> service.likeBlog(1L));
        verify(zset).remove("blog:liked:1", "7");
        verify(lock).unlock();
    }

    @Test
    void failedUnlikeRestoresOriginalScoreAndUnlocks() {
        when(zset.score("blog:liked:1", "7")).thenReturn(12345.0);
        when(zset.remove("blog:liked:1", "7")).thenReturn(1L);
        when(update.update()).thenThrow(new IllegalStateException("SQL failure"));
        Assertions.assertThrows(IllegalStateException.class, () -> service.likeBlog(1L));
        verify(zset).add("blog:liked:1", "7", 12345.0);
        verify(lock).unlock();
    }

    @Test
    void unlikeNoUpdatedRowRestoresMember() {
        when(zset.score("blog:liked:1", "7")).thenReturn(12345.0);
        when(zset.remove("blog:liked:1", "7")).thenReturn(1L);
        when(update.update()).thenReturn(false);
        Assertions.assertFalse(service.likeBlog(1L).getSuccess());
        verify(zset).add("blog:liked:1", "7", 12345.0);
    }
}
