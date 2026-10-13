package com.citygo.controller;


import com.citygo.dto.LoginFormDTO;
import com.citygo.dto.Result;
import com.citygo.entity.UserInfo;
import com.citygo.service.IUserInfoService;
import com.citygo.service.IUserService;
import com.citygo.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        return userService.login(loginForm, session);
    }

    /**
     * 体验登录。不需要手机号，也不发短信。
     */
    @PostMapping("/demo")
    public Result demo(HttpServletRequest request) {
        return userService.loginDemo(clientIp(request));
    }

    /**
     * 删掉当前请求这个 token。个人页退出登录会再清掉浏览器里的 token。
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        return userService.logout(request.getHeader("authorization"));
    }

    @GetMapping("/me")
    public Result me(){
        return Result.ok(UserHolder.getUser());
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /**
     * 取 Nginx 追加在最后的地址。前面的段可能是调用方自己带的。
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
