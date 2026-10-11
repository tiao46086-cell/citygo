package com.hmdp;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.UserHolder;
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
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false"
})
public class BlogLikeFlowTest {

    private static final long OWNER = 1013L;
    private static final long OTHER = 1014L;

    @Resource
    private IBlogService blogService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private Long blogId;

    @AfterEach
    public void cleanup() {
        UserHolder.removeUser();
        if (blogId != null) {
            stringRedisTemplate.delete(BLOG_LIKED_KEY + blogId);
            jdbcTemplate.update("DELETE FROM tb_blog WHERE id = ?", blogId);
        }
    }

    @Test
    public void secondClickCancelsAndOtherUserStays() {
        blogId = insertBlog();
        saveUser(OWNER);
        Assertions.assertEquals("笔记不存在", blogService.likeBlog(blogId + 900000).getErrorMsg());

        Assertions.assertEquals(Boolean.TRUE, blogService.likeBlog(blogId).getSuccess());
        Assertions.assertEquals(Integer.valueOf(1), blogService.getById(blogId).getLiked());
        Assertions.assertEquals(Boolean.TRUE, loaded().getIsLike());
        Assertions.assertEquals(1, likers().size());

        Assertions.assertEquals(Boolean.TRUE, blogService.likeBlog(blogId).getSuccess());
        Assertions.assertEquals(Integer.valueOf(0), blogService.getById(blogId).getLiked());
        Assertions.assertEquals(Boolean.FALSE, loaded().getIsLike());
        Assertions.assertEquals(0, likers().size());

        blogService.likeBlog(blogId);
        saveUser(OTHER);
        Assertions.assertEquals(Boolean.TRUE, blogService.likeBlog(blogId).getSuccess());
        Assertions.assertEquals(Integer.valueOf(2), blogService.getById(blogId).getLiked());
        Assertions.assertEquals(Boolean.TRUE, loaded().getIsLike());

        Assertions.assertEquals(Boolean.TRUE, blogService.likeBlog(blogId).getSuccess());
        Assertions.assertEquals(Integer.valueOf(1), blogService.getById(blogId).getLiked());
        Assertions.assertEquals(Boolean.FALSE, loaded().getIsLike());

        saveUser(OWNER);
        Assertions.assertEquals(Boolean.TRUE, loaded().getIsLike());
        Assertions.assertEquals(OWNER, likers().get(0).getId());
    }

    private Blog loaded() {
        return (Blog) blogService.queryBlogById(blogId).getData();
    }

    @SuppressWarnings("unchecked")
    private List<UserDTO> likers() {
        return (List<UserDTO>) blogService.queryBlogLikes(blogId).getData();
    }

    private Long insertBlog() {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_blog (shop_id, user_id, title, images, content, liked, comments) VALUES (1, ?, '点赞测试', '/imgs/a.jpg', 'test', 0, 0)",
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
