package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
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
public class BlogCommentFlowTest {

    private static final long OWNER = 1013L;
    private static final long OTHER = 1014L;

    @Resource
    private IBlogCommentsService blogCommentsService;
    @Resource
    private IBlogService blogService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private Long blogId;

    @AfterEach
    public void cleanup() {
        UserHolder.removeUser();
        if (blogId != null) {
            jdbcTemplate.update("DELETE FROM tb_blog_comments WHERE blog_id = ?", blogId);
            jdbcTemplate.update("DELETE FROM tb_blog WHERE id = ?", blogId);
        }
    }

    @Test
    public void onlyTheAuthorCanDelete() {
        blogId = insertBlog();
        saveUser(OWNER);
        Result added = blogCommentsService.addComment(blogId, "  第一条  ");
        Assertions.assertEquals(Boolean.TRUE, added.getSuccess());
        Long commentId = (Long) added.getData();

        saveUser(OTHER);
        Result denied = blogCommentsService.deleteOwn(commentId);
        Assertions.assertEquals("无权限", denied.getErrorMsg());

        saveUser(OWNER);
        Result page = blogCommentsService.pageByBlog(blogId, 1);
        @SuppressWarnings("unchecked")
        List<BlogComments> rows = (List<BlogComments>) page.getData();
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("第一条", rows.get(0).getContent());
        Assertions.assertEquals(1L, page.getTotal());
        Blog blog = blogService.getById(blogId);
        Assertions.assertEquals(Integer.valueOf(1), blog.getComments());

        Assertions.assertEquals(Boolean.TRUE, blogCommentsService.deleteOwn(commentId).getSuccess());
        Assertions.assertEquals("评论不存在", blogCommentsService.deleteOwn(commentId).getErrorMsg());
        Assertions.assertEquals(Integer.valueOf(0), blogService.getById(blogId).getComments());
        Assertions.assertEquals(0L, blogCommentsService.pageByBlog(blogId, 1).getTotal());
    }

    private Long insertBlog() {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_blog (shop_id, user_id, title, images, content, liked, comments) VALUES (1, ?, '评论测试', '/imgs/a.jpg', 'test', 0, 0)",
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
