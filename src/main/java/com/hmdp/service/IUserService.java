package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 分配一个体验用户，返回 token。手机号不能发验证码。同一 IP 有次数限制。
     * 同时在线最多 100 个。空闲账号会清掉订单后再交给下一位，不会两个访客共用一个账号。
     * @param clientIp 调用方地址，用来限流
     */
    Result loginDemo(String clientIp);

    /**
     * 删掉当前 token 对应的登录态。别的设备上的 token 还在。
     * @param token 请求头 authorization 里的值
     */
    Result logout(String token);
}
