package cn.john.dh.assistant.utils;

import cn.john.dh.assistant.common.BusinessException;

/**
 * @Author John
 * @Date 2026-08-04 16:43
 */
public class BusinessExceptionUtils {
    public static void throwBusinessException(String message) {
        throw new BusinessException(message);
    }
    public static void throwBusinessException(boolean condition, String message) {
        if (condition) {
            throw new BusinessException(message);
        }
    }
}
