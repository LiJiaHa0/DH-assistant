package cn.john.dh.assistant.auth.controller;

import cn.john.dh.assistant.auth.domain.dto.LoginDTO;
import cn.john.dh.assistant.auth.domain.dto.LoginUserVO;
import cn.john.dh.assistant.auth.domain.dto.RegisterDTO;
import cn.john.dh.assistant.auth.service.AuthService;
import cn.john.dh.assistant.common.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 认证控制器
 *
 * @Author John
 * @Date 2026-07-18 14:33
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * 用户登录接口
     *
     * @param loginDTO 登录请求参数
     * @return 登录结果
     */
    @PostMapping("/login")
    public R<LoginUserVO> login(@RequestBody LoginDTO loginDTO) {
        try {
            // 调用认证服务执行登录逻辑，校验通过后会生成Sa-Token会话并返回用户信息
            LoginUserVO user = authService.login(loginDTO);
            // 登录成功，返回用户信息和成功提示
            return R.ok(user, "登录成功");
        } catch (RuntimeException e) {
            // 登录失败，返回错误信息
            return R.fail(e.getMessage());
        }
    }

    /**
     * 用户注册接口
     *
     * @param registerDTO 注册请求参数
     * @return 注册结果
     */
    @PostMapping("/register")
    public R<Void> register(@RequestBody RegisterDTO registerDTO) {
        try {
            // 调用认证服务执行注册逻辑
            authService.register(registerDTO);
            // 注册成功
            return R.ok(null, "注册成功");
        } catch (RuntimeException e) {
            // 注册失败，返回错误信息
            return R.fail(e.getMessage());
        }
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前用户信息
     */
    @GetMapping("/info")
    public R<LoginUserVO> info() {
        LoginUserVO user = authService.getCurrentUser();
        if (user == null) {
            return R.fail(401, "未登录或登录已过期");
        }
        return R.ok(user);
    }
}
