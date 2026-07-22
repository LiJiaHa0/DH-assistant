package cn.john.dh.assistant.auth.domain.dto;

import lombok.Data;

/**
 * 客户登录请求数据传输对象（DTO）
 * <p>
 * 用于封装网页端客户登录时提交的请求参数。客户通过手机号和密码进行身份认证，
 * 登录成功后系统将创建Sa-Token会话并返回用户信息。
 * 该对象通过@RequestBody注解从HTTP请求体中自动反序列化。
 * </p>
 *
 * @Author John
 * @Date 2026-06-30 23:12
 */
@Data
public class LoginDTO {

    /**
     * 手机号（登录账号）
     * <p>客户使用手机号作为登录凭证，对应数据库中user_info表的phone字段</p>
     */
    private String phone;

    /**
     * 密码
     * <p>客户登录密码，当前版本为明文比对，生产环境建议使用BCrypt等加密算法</p>
     */
    private String password;
}
