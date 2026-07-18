package cn.john.dh.assistant.auth.mapper;

import cn.john.dh.assistant.auth.domain.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户信息 Mapper 接口
 *
 * @Author John
 * @Date 2026-07-18
 */
@Mapper
public interface UserInfoMapper extends BaseMapper<UserInfo> {

}
