package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IBlogFavoriteService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/blog-favorite")
public class BlogFavoriteController {

    @Resource
    private IBlogFavoriteService blogFavoriteService;

    @PutMapping("/{blogId}")
    public Result favorite(@PathVariable("blogId") Long blogId) {
        return blogFavoriteService.favorite(blogId);
    }

    @DeleteMapping("/{blogId}")
    public Result cancel(@PathVariable("blogId") Long blogId) {
        return blogFavoriteService.cancel(blogId);
    }

    @GetMapping("/or/not/{blogId}")
    public Result isFavorite(@PathVariable("blogId") Long blogId) {
        return blogFavoriteService.isFavorite(blogId);
    }

    @GetMapping("/of/me")
    public Result pageMine(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogFavoriteService.pageMine(current);
    }
}
