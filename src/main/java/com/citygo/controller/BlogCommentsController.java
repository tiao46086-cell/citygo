package com.citygo.controller;

import com.citygo.dto.Result;
import com.citygo.entity.BlogComments;
import com.citygo.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    @PostMapping
    public Result add(@RequestBody BlogComments comment) {
        Long blogId = comment == null ? null : comment.getBlogId();
        String content = comment == null ? null : comment.getContent();
        return blogCommentsService.addComment(blogId, content);
    }

    @GetMapping("/of/blog/{blogId}")
    public Result page(@PathVariable("blogId") Long blogId,
                        @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.pageByBlog(blogId, current);
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable("id") Long id) {
        return blogCommentsService.deleteOwn(id);
    }
}
