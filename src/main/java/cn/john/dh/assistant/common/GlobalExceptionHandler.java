package cn.john.dh.assistant.common;

import cn.dev33.satoken.exception.NotLoginException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器
 * <p>
 * 捕获 Controller 抛出的异常，统一转换为 {@link R} 响应返回给前端，
 * 使前端能拿到结构化的错误信息（code + msg），而非 Spring 默认的错误页或堆栈。
 * 所有异常均以 HTTP 200 返回，前端通过响应体 code 字段区分成功/失败。
 * </p>
 *
 * @Author John
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 未登录/登录过期（Sa-Token NotLoginException）：返回 401，前端据此跳转登录页
     */
    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLogin(NotLoginException e) {
        log.warn("未登录或登录已过期: {}", e.getMessage());
        return R.fail(401, "未登录或登录已过期");
    }

    /**
     * 业务异常：直接返回业务提示信息，前端可展示 msg
     */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数非法（Assert.isTrue / 枚举 valueOf 等抛出）：作为业务异常的安全网，展示其 message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数非法: {}", e.getMessage());
        return R.fail(e.getMessage());
    }

    /**
     * 上传文件大小超过限制
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件大小超限: {}", e.getMessage());
        return R.fail("文件大小超过上传限制");
    }

    /**
     * 兜底：未预期的异常，不向前端暴露内部细节
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.fail("系统异常，请稍后重试");
    }
}
