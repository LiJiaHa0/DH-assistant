package cn.john.dh.assistant.auth.service;

import cn.john.dh.assistant.auth.domain.entity.UserInfo;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 客户信息 Service 接口
 *
 * @Author John
 * @Date 2026-07-18
 */
public interface UserInfoService extends IService<UserInfo> {

    /**
     * 根据手机号查询用户
     *
     * @param phone 手机号
     * @return 用户信息，不存在则返回 null
     */
    UserInfo getByPhone(String phone);

}
