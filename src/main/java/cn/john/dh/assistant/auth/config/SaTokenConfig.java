package cn.john.dh.assistant.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 安全框架配置类
 * <p>
 * 注册全局登录拦截器，对除登录接口（/auth/**）、Druid监控（/druid/**）以外的
 * 所有 HTTP 请求进行登录校验（Sa-Token checkLogin，未登录抛 NotLoginException，
 * 由 GlobalExceptionHandler 统一转换为 401 响应）。
 * </p>
 *
 * @Author John
 * @Date 2026-07-18 15:26
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/**",   // 登录/注册接口
                        "/druid/**",  // Druid 监控（自身有账号密码保护）
                        "/error"      // Spring 错误页
                );
    }
}
