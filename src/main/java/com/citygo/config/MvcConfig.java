package com.citygo.config;

import com.citygo.utils.LoginInterceptor;
import com.citygo.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 先刷新 token、放用户进 ThreadLocal，order=0 对所有请求生效
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
        // 再校验登录，order=1 只拦截需要登录的接口
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/user/demo",
                        "/blog/hot",
                        // /blog/* 只放行笔记详情。点赞 /blog/like/*、我的笔记 /blog/of/me、
                        // 修改和删除 /blog/me/* 仍要登录。
                        "/blog/*",
                        "/blog/likes/*",
                        // 评论可以看。发表和删除不在这条路径里。
                        "/blog-comments/of/**",
                        "/shop-type/**",
                        "/shop/*",
                        "/shop/of/*",
                        "/voucher/list/*"
                ).order(1);
    }
}
