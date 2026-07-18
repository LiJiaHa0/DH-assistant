package cn.john.dh.assistant.auth.config;

import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 安全框架配置类
 * <p>
 * 该类是系统认证与授权的核心配置，负责注册全局拦截器并对所有HTTP请求进行登录校验。
 * 主要功能包括：
 * <ul>
 *     <li>对除登录接口（/auth/**）、钉钉回调接口（/dingtalk/**）、Druid监控（/druid/**）以外的所有接口进行登录校验</li>
 *     <li>自动跳过静态资源（html、js、css、图片、字体等）的登录校验</li>
 *     <li>对文档管理相关路由（/api/document/**、/api/segment/**）进行员工身份校验，
 *         通过判断loginId是否以"staff_"前缀开头来识别员工身份</li>
 * </ul>
 * </p>
 * @Author John
 * @Date 2026-07-18 15:26
 */
public class SaTokenConfig implements WebMvcConfigurer {
}
