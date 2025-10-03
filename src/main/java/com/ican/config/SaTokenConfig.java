package com.ican.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 配置类
 *
 * @author ICan
 * @since 2024-10-03
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 注册 Sa-Token 拦截器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器,打开注解式鉴权功能
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 指定需要拦截的路径
            SaRouter.match("/**")
                    // 排除不需要拦截的路径
                    .notMatch(
                            // 错误处理
                            "/error",
                            // 静态资源
                            "/*.html",
                            "/*/*.html",
                            "/*/*.css",
                            "/*/*.js",
                            "/websocket/**",
                            // 接口文档相关资源
                            "/favicon.ico",
                            "/doc.html",
                            "/webjars/**",
                            "/swagger-ui/**",
                            "/swagger-resources/**",
                            "/*/api-docs/**",
                            "/v3/api-docs/**",
                            // 认证相关接口(登录、注册等)
                            "/auth/login",
                            "/auth/register",
                            "/auth/check",
                            // 验证码接口
                            "/captcha/**"
                    )
                    // 执行登录校验
                    .check(r -> StpUtil.checkLogin());
        })).addPathPatterns("/**");
    }
}
