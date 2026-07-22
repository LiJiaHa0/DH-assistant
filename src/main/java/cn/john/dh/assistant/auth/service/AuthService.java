package cn.john.dh.assistant.auth.service;

import cn.john.dh.assistant.auth.domain.dto.LoginDTO;
import cn.john.dh.assistant.auth.domain.dto.LoginUserVO;
import cn.john.dh.assistant.auth.domain.dto.RegisterDTO;

/**
 * 认证服务接口
 *
 * @Author John
 * @Date 2026-07-18 16:01
 */
public interface AuthService {

    /**
     * 用户登录
     *
     * @param loginDTO 登录请求参数
     * @return 登录用户信息视图对象
     */
    LoginUserVO login(LoginDTO loginDTO);

    /**
     * 用户注册
     *
     * @param registerDTO 注册请求参数
     */
    void register(RegisterDTO registerDTO);

    /**
     * 获取当前登录用户信息
     *
     * @return 登录用户信息视图对象，未登录时返回 null
     */
    LoginUserVO getCurrentUser();
}
