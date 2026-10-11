package com.hmdp.utils;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 新增、修改商户和发券没有店主字段，用配置里的用户 id 当管理员。
 * 名单为空时这些写接口一律拒绝。
 */
@Component
public class AdminGuard {

    private final Set<Long> adminUserIds;

    public AdminGuard(@Value("${citygo.admin-user-ids:}") String raw) {
        this.adminUserIds = parse(raw);
    }

    public Result requireAdmin() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null || !adminUserIds.contains(user.getId())) {
            return Result.fail("无权限");
        }
        return null;
    }

    static Set<Long> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        for (String part : raw.split(",")) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(text));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("citygo.admin-user-ids 含有无法解析的用户 id: " + text);
            }
        }
        return ids;
    }
}
