package cn.john.dh.assistant.common;

/**
 * 业务异常
 * <p>
 * 用于表示需要提示给前端的业务错误（如"文档内容已存在，请勿重复上传"）。
 * 由 {@link GlobalExceptionHandler} 统一捕获后转换为 {@link R} 响应返回，
 * 前端可通过响应体中的 msg 字段直接展示错误提示。
 * </p>
 *
 * @Author John
 */
public class BusinessException extends RuntimeException {

    /**
     * 业务状态码，默认 500
     */
    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
