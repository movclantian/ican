package com.ican.config;

import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.EnumSet;

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
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
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

    /**
     * 注册 SaToken 上下文 Filter,支持异步请求
     */
    @Bean
    public FilterRegistrationBean<SaTokenContextFilterForJakartaServlet> saTokenContextFilterForJakartaServlet() {
        FilterRegistrationBean<SaTokenContextFilterForJakartaServlet> bean = 
            new FilterRegistrationBean<>(new SaTokenContextFilterForJakartaServlet());
        // 配置 Filter 拦截的 URL 模式
        bean.addUrlPatterns("/*");
        // 设置 Filter 的执行顺序,数值越小越先执行
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        // 支持异步请求
        bean.setAsyncSupported(true);
        // 配置 Dispatcher 类型,支持异步和普通请求
        bean.setDispatcherTypes(EnumSet.of(DispatcherType.ASYNC, DispatcherType.REQUEST));
        return bean;
    }
}
