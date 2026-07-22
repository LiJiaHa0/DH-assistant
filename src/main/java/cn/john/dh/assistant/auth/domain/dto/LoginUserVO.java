package cn.john.dh.assistant.auth.domain.dto;

import lombok.Data;

/**
 * 登录用户信息视图对象（VO）
 * <p>
 * 用于封装登录成功后返回给前端的用户信息。该对象不包含密码等敏感字段，
 * 确保安全性。同时适用于客户和员工两种用户类型，通过userType字段进行区分。
 * </p>
 * <p>
 * 客户登录时userType为"user"，员工登录时userType为"staff"。
 * 该对象在AuthServiceImpl中通过BeanUtils.copyProperties()或手动赋值的方式从实体类转换而来。
 * </p>
 *
 * @Author John
 * @Date 2026-06-30 23:12
 */
@Data
public class LoginUserVO {

    /**
     * 主键id
     * <p>用户或员工在数据库中的主键标识</p>
     */
    private Long id;

    /**
     * 手机号
     * <p>客户的登录手机号，员工登录时该字段可能为空</p>
     */
    private String phone;

    /**
     * 姓名
     * <p>客户真实姓名或员工姓名</p>
     */
    private String name;

    /**
     * 昵称
     * <p>客户昵称，员工登录时该字段可能为空</p>
     */
    private String nickname;

    /**
     * 头像地址
     * <p>用户头像的URL地址，客户来自avatar字段，员工来自picUrl字段</p>
     */
    private String avatar;

    /**
     * 状态
     * <p>
     * 客户状态值：ACTIVE-正常、FROZEN-冻结；
     * 员工状态值：ON_JOB-在职、OFF_JOB-离职
     * </p>
     */
    private String status;

    /**
     * 用户类型
     * <p>标识当前登录用户的类型：staff-员工，user-客户。
     * 前端可根据该字段展示不同的界面和功能</p>
     */
    private String userType;
}
