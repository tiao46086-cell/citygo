package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    private static final int CONTENT_LIMIT = 255;
    private static final long TOP_LEVEL = 0L;

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Override
    @Transactional
    public Result addComment(Long blogId, String content) {
        if (blogId == null || blogService.getById(blogId) == null) {
            return Result.fail("笔记不存在");
        }
        if (content == null || content.trim().isEmpty()) {
            return Result.fail("评论不能为空");
        }
        String text = content.trim();
        if (text.length() > CONTENT_LIMIT) {
            return Result.fail("评论太长");
        }
        BlogComments comment = new BlogComments();
        comment.setUserId(UserHolder.getUser().getId());
        comment.setBlogId(blogId);
        comment.setParentId(TOP_LEVEL);
        comment.setAnswerId(TOP_LEVEL);
        comment.setContent(text);
        comment.setLiked(0);
        comment.setStatus(0);
        save(comment);
        blogService.update()
                .setSql("comments = IFNULL(comments, 0) + 1")
                .eq("id", blogId)
                .update();
        return Result.ok(comment.getId());
    }

    @Override
    public Result pageByBlog(Long blogId, Integer current) {
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .eq("parent_id", TOP_LEVEL)
                .eq("status", 0)
                .orderByDesc("create_time")
                .page(new Page<>(pageNo, SystemConstants.DEFAULT_PAGE_SIZE));
        fillAuthors(page.getRecords());
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional
    public Result deleteOwn(Long id) {
        BlogComments comment = getById(id);
        if (comment == null || comment.getParentId() == null || comment.getParentId() != TOP_LEVEL) {
            return Result.fail("评论不存在");
        }
        Long userId = UserHolder.getUser().getId();
        if (!userId.equals(comment.getUserId())) {
            return Result.fail("无权限");
        }
        removeById(id);
        blogService.update()
                .setSql("comments = comments - 1")
                .eq("id", comment.getBlogId())
                .gt("comments", 0)
                .update();
        return Result.ok();
    }

    private void fillAuthors(List<BlogComments> comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }
        List<Long> userIds = comments.stream()
                .map(BlogComments::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, User> users = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        for (BlogComments comment : comments) {
            User user = users.get(comment.getUserId());
            if (user == null) {
                continue;
            }
            comment.setName(user.getNickName());
            comment.setIcon(user.getIcon());
        }
    }
}
