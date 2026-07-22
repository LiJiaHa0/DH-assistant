package cn.john.dh.assistant.auth.domain.dto;

import lombok.Data;

/**
 * 客户注册请求数据传输对象（DTO）
 * <p>
 * 用于封装网页端客户注册时提交的请求参数。客户通过手机号、密码和姓名进行注册，
 * 注册成功后系统将自动创建用户账号。
 * </p>
 *
 * @Author John
 * @Date 2026-07-18
 */
@Data
public class RegisterDTO {

    /**
     * 手机号（注册账号）
     * <p>客户使用手机号作为注册凭证，对应数据库中user_info表的phone字段</p>
     */
    private String phone;

    /**
     * 密码
     * <p>客户登录密码，当前版本为明文存储，生产环境建议使用BCrypt等加密算法</p>
     */
    private String password;

    /**
     * 姓名
     * <p>客户真实姓名</p>
     */
    private String name;

    /**
     * 昵称
     * <p>客户昵称，选填</p>
     */
    private String nickname;
}
