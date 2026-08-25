package com.company.paymentanalysis.permission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "permission.management")
public record PermissionManagementProperties(String password) {

    public PermissionManagementProperties {
        password = StringUtils.hasText(password) ? password : "helloworld";
    }
}
