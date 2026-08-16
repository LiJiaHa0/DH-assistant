package cn.john.dh.assistant.chat.service;

public interface ChatTokenLimitService {

    /**
     * 判断用户当日是否还有 Token 额度
     */
    boolean isAvailable(String userId);

    /**
     * 获取用户当日已用 Token 数
     */
    long getTodayUsed(String userId);

    /**
     * 消耗 Token（正数累加）
     */
    void consume(String userId, long tokens);
}
