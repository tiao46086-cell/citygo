package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 从 session 中获取用户
        Object user = request.getSession().getAttribute("user");
        // 2. 没有登录，返回 401，前端收到 401 会跳转到登录页
        if (user == null) {
            response.setStatus(401);
            return false;
        }
        // 3. 已登录，保存到 ThreadLocal，放行
        UserHolder.saveUser((UserDTO) user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Tomcat 线程会复用，请求结束必须清除，否则下一个请求可能拿到上一个用户
        UserHolder.removeUser();
    }
}
