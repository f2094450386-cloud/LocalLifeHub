package com.hmdp.security;

import cn.hutool.core.util.StrUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AdminAccessPolicy {

    private final Set<Long> adminUserIds;

    public AdminAccessPolicy(@Value("${app.security.admin-user-ids:}") String configuredIds) {
        if (StrUtil.isBlank(configuredIds)) {
            this.adminUserIds = Collections.emptySet();
            return;
        }
        this.adminUserIds = Arrays.stream(configuredIds.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .map(Long::valueOf)
                .collect(Collectors.toSet());
    }

    public boolean isAdmin(Long userId) {
        return userId != null && adminUserIds.contains(userId);
    }
}
