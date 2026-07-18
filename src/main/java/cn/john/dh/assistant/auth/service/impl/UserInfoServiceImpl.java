package cn.john.dh.assistant.auth.service.impl;

import cn.john.dh.assistant.auth.domain.entity.UserInfo;
import cn.john.dh.assistant.auth.mapper.UserInfoMapper;
import cn.john.dh.assistant.auth.service.UserInfoService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 客户信息 Service 实现类
 *
 * @Author John
 * @Date 2026-07-18
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements UserInfoService {

    @Override
    public UserInfo getByPhone(String phone) {
        return getOne(new LambdaQueryWrapper<UserInfo>()
                .eq(UserInfo::getPhone, phone));
    }

}
