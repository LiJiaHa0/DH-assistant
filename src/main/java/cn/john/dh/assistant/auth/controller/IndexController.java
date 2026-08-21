package cn.john.dh.assistant.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 根路径跳转控制器
 * <p>
 * 访问应用根路径（如 http://localhost:8080/ 或内网穿透地址 http://john-lee.cn/）时，
 * 根据登录状态跳转：未登录跳转登录页，已登录跳转主页。
 * 覆盖 Spring Boot 默认欢迎页（static/index.html），避免未登录用户
 * 直接看到 401 JSON 或空白页。
 * </p>
 *
 * @Author John
 * @Date 2026-08-19 11:30
 */
@Controller
public class IndexController {

    /**
     * 根路径：按登录状态跳转
     *
     * @return 未登录跳转登录页，已登录跳转主页
     */
    @GetMapping("/")
    public String redirectByLoginState() {
        return StpUtil.isLogin() ? "redirect:/index.html" : "redirect:/login.html";
    }
}
