package com.citygo.utils;

import cn.hutool.core.bean.BeanUtil;
import com.citygo.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.citygo.utils.RedisConstants.DEMO_SESSION_MINUTES;
import static com.citygo.utils.RedisConstants.DEMO_TOKEN_MARK;
import static com.citygo.utils.RedisConstants.LOGIN_USER_KEY;
import static com.citygo.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 所有请求都先走这里，从 Authorization 头拿 token
        String token = request.getHeader("authorization");
        if (token == null || token.isEmpty()) {
            return true;
        }
        // 2. Redis 取用户 Hash
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (userMap.isEmpty()) {
            return true;
        }
        // 3. 有用户则转回 UserDTO，存 ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        // 4. 普通登录续 25 天。体验账号只续 2 小时，停用后名额才能回收
        long minutes = "1".equals(String.valueOf(userMap.get(DEMO_TOKEN_MARK)))
                ? DEMO_SESSION_MINUTES : LOGIN_USER_TTL;
        stringRedisTemplate.expire(key, minutes, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}
