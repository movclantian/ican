package com.ican.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 验证码业务配置
 * 
 * @author ICan
 * @since 2024-10-07
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "captcha")
public class CaptchaProperties {
    
    /**
     * 验证码在 Redis 中的 key 前缀
     */
    private String redisKeyPrefix = "captcha:";
    
    /**
     * 验证码过期时间(分钟)
     */
    private Integer expireMinutes = 5;
}
