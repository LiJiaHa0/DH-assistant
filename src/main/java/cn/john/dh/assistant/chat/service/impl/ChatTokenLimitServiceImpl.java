package cn.john.dh.assistant.chat.service.impl;

import cn.john.dh.assistant.chat.domain.entity.UserChatTokenDaily;
import cn.john.dh.assistant.chat.mapper.UserChatTokenDailyMapper;
import cn.john.dh.assistant.chat.service.ChatTokenLimitService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
public class ChatTokenLimitServiceImpl implements ChatTokenLimitService {

    /**
     * 每日 Token 上限，默认 100000。
     * 按深度思考 10k–20k token/轮估算，约支持 5–10 轮深度思考。
     */
    @Value("${chat.daily-token-limit:100000}")
    private long dailyTokenLimit;

    private final UserChatTokenDailyMapper userChatTokenDailyMapper;

    public ChatTokenLimitServiceImpl(UserChatTokenDailyMapper userChatTokenDailyMapper) {
        this.userChatTokenDailyMapper = userChatTokenDailyMapper;
    }

    @Override
    public boolean isAvailable(String userId) {
        if (userId == null || userId.isBlank()) {
            return true;
        }
        try {
            return getTodayUsed(userId) < dailyTokenLimit;
        } catch (Exception e) {
            // 限额查询异常时放行（避免数据库抖动导致聊天接口整体不可用），仅记录日志
            log.warn("查询当日 token 用量失败，临时放行: userId={}", userId, e);
            return true;
        }
    }

    @Override
    public long getTodayUsed(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        UserChatTokenDaily record = userChatTokenDailyMapper.selectOne(
                new LambdaQueryWrapper<UserChatTokenDaily>()
                        .eq(UserChatTokenDaily::getUserId, userId)
                        .eq(UserChatTokenDaily::getUsageDate, LocalDate.now())
        );
        return record != null && record.getUsedTokens() != null ? record.getUsedTokens() : 0L;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consume(String userId, long tokens) {
        if (userId == null || userId.isBlank() || tokens <= 0) {
            return;
        }
        LocalDate today = LocalDate.now();

        // 先尝试 update 累加
        int rows = userChatTokenDailyMapper.update(null,
                new LambdaUpdateWrapper<UserChatTokenDaily>()
                        .eq(UserChatTokenDaily::getUserId, userId)
                        .eq(UserChatTokenDaily::getUsageDate, today)
                        .setSql("used_tokens = used_tokens + {0}", tokens)
                        .set(UserChatTokenDaily::getUpdatedAt, LocalDateTime.now())
        );

        if (rows > 0) {
            return;
        }

        // 无记录则 insert，并发时若唯一键冲突则再 update 一次
        UserChatTokenDaily record = new UserChatTokenDaily();
        record.setUserId(userId);
        record.setUsageDate(today);
        record.setUsedTokens(tokens);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        try {
            userChatTokenDailyMapper.insert(record);
        } catch (DuplicateKeyException e) {
            userChatTokenDailyMapper.update(null,
                    new LambdaUpdateWrapper<UserChatTokenDaily>()
                            .eq(UserChatTokenDaily::getUserId, userId)
                            .eq(UserChatTokenDaily::getUsageDate, today)
                            .setSql("used_tokens = used_tokens + {0}", tokens)
                            .set(UserChatTokenDaily::getUpdatedAt, LocalDateTime.now())
            );
        }
    }
}
