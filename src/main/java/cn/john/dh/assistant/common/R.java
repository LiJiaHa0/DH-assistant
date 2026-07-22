package cn.john.dh.assistant.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 响应结果封装类
 * <p>
 * 所有 Controller 接口的返回值统一使用该泛型类进行包装，确保接口响应格式的一致性。
 * 包含三个核心字段：状态码（code）、提示信息（msg）和业务数据（data）。
 * 提供了多个静态工厂方法，方便快速构建成功或失败的响应对象。
 * </p>
 *
 * @param <T> 业务数据的泛型类型
 * @Author John
 * @Date 2026-06-30 23:12
 */
@Data
public class R<T> implements Serializable {

    /**
     * 响应状态码，200 表示成功，其他值表示失败
     */
    private int code;

    /**
     * 响应提示信息，描述请求处理结果
     */
    private String msg;

    /**
     * 响应业务数据，承载具体的返回内容
     */
    private T data;

    /**
     * 构建无数据的成功响应
     *
     * @param <T> 业务数据类型
     * @return R<T> 状态码为 200、消息为 "success" 的成功响应对象
     */
    public static <T> R<T> ok() {
        return ok(null); // 不携带业务数据，仅返回成功状态
    }

    /**
     * 构建携带业务数据的成功响应
     *
     * @param <T>  业务数据类型
     * @param data 需要返回的业务数据
     * @return R<T> 状态码为 200、消息为 "success"、包含业务数据的成功响应对象
     */
    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();           // 创建响应对象实例
        r.setCode(200);               // 设置成功状态码
        r.setMsg("success");          // 设置成功提示信息
        r.setData(data);              // 设置业务数据
        return r;
    }

    /**
     * 构建携带自定义消息和业务数据的成功响应
     *
     * @param <T>  业务数据类型
     * @param data 需要返回的业务数据
     * @param msg  自定义的成功提示信息
     * @return R<T> 状态码为 200、包含自定义消息和业务数据的成功响应对象
     */
    public static <T> R<T> ok(T data, String msg) {
        R<T> r = new R<>();           // 创建响应对象实例
        r.setCode(200);               // 设置成功状态码
        r.setMsg(msg);                // 设置自定义提示信息
        r.setData(data);              // 设置业务数据
        return r;
    }

    /**
     * 构建默认的失败响应（状态码 500）
     *
     * @param <T> 业务数据类型
     * @param msg 失败原因描述
     * @return R<T> 状态码为 500 的失败响应对象
     */
    public static <T> R<T> fail(String msg) {
        return fail(500, msg); // 默认使用 500 作为失败状态码
    }

    /**
     * 构建自定义状态码的失败响应
     *
     * @param <T>  业务数据类型
     * @param code 自定义的失败状态码
     * @param msg  失败原因描述
     * @return R<T> 包含自定义状态码和失败信息的响应对象
     */
    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();           // 创建响应对象实例
        r.setCode(code);              // 设置失败状态码
        r.setMsg(msg);                // 设置失败提示信息
        return r;                     // 失败响应不携带业务数据
    }
}
