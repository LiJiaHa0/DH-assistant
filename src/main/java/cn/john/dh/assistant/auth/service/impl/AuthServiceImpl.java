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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

    // BCrypt 密码编码器（线程安全，可复用）
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

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
        // 统一失败提示，避免账号枚举（不区分"用户不存在"与"密码错误"）
        if (userInfo == null || !matchesPassword(loginDTO.getPassword(), userInfo.getPassword())) {
            throw new RuntimeException("手机号或密码错误");
        }

        // 3. 校验用户状态
        if ("FROZEN".equals(userInfo.getStatus())) {
            throw new RuntimeException("账号已被冻结，请联系客服");
        }

        // 4. 旧明文密码登录成功时，原地升级为 BCrypt 哈希（仅首次登录迁移一次）
        upgradeLegacyPlainPassword(userInfo, loginDTO.getPassword());

        // 5. Sa-Token 登录，直接使用用户ID作为 loginId
        StpUtil.login(userInfo.getId());

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

    /**
     * 密码校验：优先按 BCrypt 比对；兼容历史明文密码（直接等值比对）
     *
     * @param rawPassword     用户输入的明文密码
     * @param storedPassword  数据库中存储的密码（BCrypt 哈希或历史明文）
     * @return 是否匹配
     */
    private boolean matchesPassword(String rawPassword, String storedPassword) {
        if (storedPassword == null) {
            return false;
        }
        // BCrypt 哈希以 $2a$/$2b$/$2y$ 开头
        if (storedPassword.startsWith("$2")) {
            return PASSWORD_ENCODER.matches(rawPassword, storedPassword);
        }
        // 历史明文密码兼容比对
        return storedPassword.equals(rawPassword);
    }

    /**
     * 将历史明文密码原地升级为 BCrypt 哈希（登录成功后调用一次）
     *
     * @param userInfo    用户信息
     * @param rawPassword 本次登录输入的明文密码
     */
    private void upgradeLegacyPlainPassword(UserInfo userInfo, String rawPassword) {
        String stored = userInfo.getPassword();
        if (stored != null && !stored.startsWith("$2")) {
            userInfo.setPassword(PASSWORD_ENCODER.encode(rawPassword));
            userInfo.setUpdatedAt(LocalDateTime.now());
            userInfoService.updateById(userInfo);
            log.info("用户 {} 明文密码已升级为 BCrypt 哈希", userInfo.getPhone());
        }
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

        // 5. 创建新用户（密码使用 BCrypt 哈希存储）
        UserInfo userInfo = new UserInfo();
        userInfo.setPhone(registerDTO.getPhone());
        userInfo.setPassword(PASSWORD_ENCODER.encode(registerDTO.getPassword()));
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

        // 2. 从 loginId 解析用户ID（直接存储纯数字ID）
        String loginIdStr = StpUtil.getLoginIdAsString();
        Long userId = Long.parseLong(loginIdStr);

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
