package com.citygo;

import com.citygo.entity.Shop;
import com.citygo.service.IShopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.TestPropertySource;

import javax.annotation.Resource;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static com.citygo.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false"
})
public class ShopLocalCacheTest {

    private static final long MISSING_ID = 900000001L;

    @Resource
    private IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private Long shopId;

    @AfterEach
    public void cleanup() {
        if (shopId != null) {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
            jdbcTemplate.update("DELETE FROM tb_shop WHERE id = ?", shopId);
        }
        stringRedisTemplate.delete(CACHE_SHOP_KEY + MISSING_ID);
    }

    @Test
    public void localHitSkipsRedisAndNullIsNotCached() {
        shopId = insertShop("缓存前");
        Assertions.assertEquals("缓存前", shopService.queryById(shopId).getName());

        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        Shop again = shopService.queryById(shopId);
        Assertions.assertEquals("缓存前", again.getName());
        Assertions.assertNull(stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + shopId));

        Shop patch = new Shop();
        patch.setId(shopId);
        patch.setName("缓存后");
        Assertions.assertEquals(Boolean.TRUE, shopService.update(patch).getSuccess());
        Assertions.assertEquals("缓存后", shopService.queryById(shopId).getName());

        Assertions.assertNull(shopService.queryById(MISSING_ID));
        stringRedisTemplate.delete(CACHE_SHOP_KEY + MISSING_ID);
        Assertions.assertNull(shopService.queryById(MISSING_ID));
        Assertions.assertEquals("", stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + MISSING_ID));
    }

    private Long insertShop(String name) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_shop (name, type_id, images, address, x, y, sold, comments, score) VALUES (?, 1, '/imgs/a.jpg', '测试地址', 120, 30, 0, 0, 50)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }
}
