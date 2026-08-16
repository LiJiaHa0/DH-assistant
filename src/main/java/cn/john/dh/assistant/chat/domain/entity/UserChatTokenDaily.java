package cn.john.dh.assistant.chat.domain.entity;

import cn.john.dh.assistant.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_chat_token_daily")
public class UserChatTokenDaily extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("usage_date")
    private LocalDate usageDate;

    @TableField("used_tokens")
    private Long usedTokens;
}
