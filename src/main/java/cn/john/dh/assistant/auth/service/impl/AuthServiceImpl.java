package cn.john.dh.assistant.auth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.auth.domain.dto.LoginDTO;
import cn.john.dh.assistant.auth.domain.dto.LoginUserVO;
import cn.john.dh.assistant.auth.domain.dto.RegisterDTO;
import cn.john.dh.assistant.auth.domain.entity.UserInfo;
import cn.john.dh.assistant.auth.service.AuthService;
import cn.john.dh.assistant.auth.service.UserInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务实现类
 *
 * @Author John
 * @Date 2026-07-18 16:02
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private UserInfoService userInfoService;

    @Override
    public LoginUserVO login(LoginDTO loginDTO) {
        // 1. 校验参数
        if (loginDTO.getPhone() == null || loginDTO.getPhone().isBlank()) {
            throw new RuntimeException("手机号不能为空");
        }
        if (loginDTO.getPassword() == null || loginDTO.getPassword().isBlank()) {
            throw new RuntimeException("密码不能为空");
        }

        // 2. 根据手机号查询用户
        UserInfo userInfo = userInfoService.getByPhone(loginDTO.getPhone());
        if (userInfo == null) {
            throw new RuntimeException("用户不存在，请先注册");
        }

        // 3. 校验密码
        if (!userInfo.getPassword().equals(loginDTO.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        // 4. 校验用户状态
        if ("FROZEN".equals(userInfo.getStatus())) {
            throw new RuntimeException("账号已被冻结，请联系客服");
        }

        // 5. Sa-Token 登录，使用 "user_" + id 作为 loginId
        StpUtil.login("user_" + userInfo.getId());

        // 6. 构建返回的用户信息 VO
        LoginUserVO vo = new LoginUserVO();
        vo.setId(userInfo.getId());
        vo.setPhone(userInfo.getPhone());
        vo.setName(userInfo.getName());
        vo.setNickname(userInfo.getNickname());
        vo.setAvatar(userInfo.getAvatar());
        vo.setStatus(userInfo.getStatus());
        vo.setUserType("user");

        log.info("用户登录成功: phone={}", loginDTO.getPhone());
        return vo;
    }

    @Override
    public void register(RegisterDTO registerDTO) {
        // 1. 校验参数
        if (registerDTO.getPhone() == null || registerDTO.getPhone().isBlank()) {
            throw new RuntimeException("手机号不能为空");
        }
        if (registerDTO.getPassword() == null || registerDTO.getPassword().isBlank()) {
            throw new RuntimeException("密码不能为空");
        }
        if (registerDTO.getName() == null || registerDTO.getName().isBlank()) {
            throw new RuntimeException("姓名不能为空");
        }

        // 2. 校验手机号格式（中国大陆11位手机号）
        if (!registerDTO.getPhone().matches("^1[3-9]\\d{9}$")) {
            throw new RuntimeException("手机号格式不正确");
        }

        // 3. 校验密码长度
        if (registerDTO.getPassword().length() < 6) {
            throw new RuntimeException("密码长度不能少于6个字符");
        }

        // 4. 校验手机号是否已注册
        UserInfo existUser = userInfoService.getByPhone(registerDTO.getPhone());
        if (existUser != null) {
            throw new RuntimeException("该手机号已注册，请直接登录");
        }

        // 5. 创建新用户
        UserInfo userInfo = new UserInfo();
        userInfo.setPhone(registerDTO.getPhone());
        userInfo.setPassword(registerDTO.getPassword());
        userInfo.setName(registerDTO.getName());
        userInfo.setNickname(registerDTO.getNickname() != null ? registerDTO.getNickname() : registerDTO.getName());
        userInfo.setStatus("ACTIVE");
        userInfo.setCreatedAt(LocalDateTime.now());
        userInfo.setUpdatedAt(LocalDateTime.now());

        // 6. 保存到数据库
        userInfoService.save(userInfo);
        log.info("用户注册成功: phone={}", registerDTO.getPhone());
    }

    @Override
    public LoginUserVO getCurrentUser() {
        // 1. 检查是否已登录
        if (!StpUtil.isLogin()) {
            return null;
        }

        // 2. 从 loginId 解析用户ID（格式为 "user_" + id）
        String loginIdStr = StpUtil.getLoginIdAsString();
        if (!loginIdStr.startsWith("user_")) {
            return null;
        }
        Long userId = Long.parseLong(loginIdStr.substring(5));

        // 3. 查询用户信息
        UserInfo userInfo = userInfoService.getById(userId);
        if (userInfo == null) {
            return null;
        }

        // 4. 构建返回的 VO
        LoginUserVO vo = new LoginUserVO();
        vo.setId(userInfo.getId());
        vo.setPhone(userInfo.getPhone());
        vo.setName(userInfo.getName());
        vo.setNickname(userInfo.getNickname());
        vo.setAvatar(userInfo.getAvatar());
        vo.setStatus(userInfo.getStatus());
        vo.setUserType("user");
        return vo;
    }
}
