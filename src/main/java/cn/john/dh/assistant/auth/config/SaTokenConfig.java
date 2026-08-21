package cn.john.dh.assistant.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 安全框架配置类
 * <p>
 * 注册全局登录拦截器，对除以下路径外的所有 HTTP 请求进行登录校验
 * （Sa-Token checkLogin，未登录抛 NotLoginException，由 GlobalExceptionHandler 统一转换为 401 响应）：
 * <ul>
 *   <li>/auth/**：登录/注册接口</li>
 *   <li>/druid/**：Druid 监控（自身有账号密码保护）</li>
 *   <li>/error：Spring 错误页</li>
 *   <li>/：根路径（由 IndexController 按登录态跳转到登录页或主页）</li>
 *   <li>/login.html、/register.html、/index.html：前端页面（由前端 JS 负责登录态检测与跳转）</li>
 *   <li>/favicon.ico、/favicon.png：站点图标</li>
 * </ul>
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
                        "/auth/**",         // 登录/注册接口
                        "/druid/**",        // Druid 监控（自身有账号密码保护）
                        "/error",           // Spring 错误页
                        "/",                 // 根路径（IndexController 按登录态跳转）
                        "/login.html",      // 登录页（公开）
                        "/register.html",   // 注册页（公开）
                        "/index.html",      // 主页（前端 JS 负责登录态检测与跳转）
                        "/favicon.ico",     // 站点图标
                        "/favicon.png"       // 站点图标（高清版）
                );
    }
}
