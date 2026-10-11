package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogFavoriteService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.TestPropertySource;

import javax.annotation.Resource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false"
})
public class BlogFavoriteFlowTest {

    private static final long OWNER = 1013L;
    private static final long OTHER = 1014L;

    @Resource
    private IBlogFavoriteService blogFavoriteService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private Long blogId;

    @AfterEach
    public void cleanup() {
        UserHolder.removeUser();
        if (blogId != null) {
            jdbcTemplate.update("DELETE FROM tb_blog_favorite WHERE blog_id = ?", blogId);
            jdbcTemplate.update("DELETE FROM tb_blog WHERE id = ?", blogId);
        }
    }

    @Test
    public void favoriteCancelAndListStayWithTheOwner() {
        blogId = insertBlog();
        saveUser(OWNER);
        Assertions.assertEquals("笔记不存在", blogFavoriteService.favorite(blogId + 900000).getErrorMsg());

        Result added = blogFavoriteService.favorite(blogId);
        Assertions.assertEquals(Boolean.TRUE, added.getSuccess());
        Assertions.assertEquals(Boolean.TRUE, blogFavoriteService.favorite(blogId).getSuccess());
        Assertions.assertEquals(Boolean.TRUE, blogFavoriteService.isFavorite(blogId).getData());
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_favorite WHERE user_id = ? AND blog_id = ?",
                Integer.class, OWNER, blogId).intValue());

        saveUser(OTHER);
        Assertions.assertEquals(Boolean.FALSE, blogFavoriteService.isFavorite(blogId).getData());
        Assertions.assertEquals(Boolean.TRUE, blogFavoriteService.cancel(blogId).getSuccess());
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_favorite WHERE user_id = ? AND blog_id = ?",
                Integer.class, OWNER, blogId).intValue());

        saveUser(OWNER);
        Result page = blogFavoriteService.pageMine(1);
        @SuppressWarnings("unchecked")
        List<Blog> rows = (List<Blog>) page.getData();
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("收藏测试", rows.get(0).getTitle());
        Assertions.assertEquals(1L, page.getTotal());

        Assertions.assertEquals(Boolean.TRUE, blogFavoriteService.cancel(blogId).getSuccess());
        Assertions.assertEquals(Boolean.FALSE, blogFavoriteService.isFavorite(blogId).getData());
        Assertions.assertEquals(0L, blogFavoriteService.pageMine(1).getTotal());
        Assertions.assertEquals(Boolean.TRUE, blogFavoriteService.cancel(blogId).getSuccess());
    }

    private Long insertBlog() {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_blog (shop_id, user_id, title, images, content, liked, comments) VALUES (1, ?, '收藏测试', '/imgs/a.jpg', 'test', 0, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, OWNER);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }

    private void saveUser(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }
}
