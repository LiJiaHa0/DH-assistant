package cn.john.dh.assistant.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * @Author John
 * @Date 2026-07-22 01:12
 */
public class AgentResponse {

    /** 纯文本类型常量 */
    public static final String TYPE_TEXT = "text";
    /** 思考过程类型常量 */
    public static final String TYPE_THINKING = "thinking";
    /** 引用资料类型常量 */
    public static final String TYPE_REFERENCE = "reference";
    /** 错误信息类型常量 */
    public static final String TYPE_ERROR = "error";
    /** 推荐问题类型常量 */
    public static final String TYPE_RECOMMEND = "recommend";
    /** 流式响应结束类型常量 */
    public static final String TYPE_COMPLETE = "complete";

    // 响应类型（text/thinking/reference/error/recommend）
    private String type;
    // 响应内容
    private String content;
    // 数据条数（用于reference和recommend类型）
    private Integer count;
    // 附加数据
    private Object data;

    /**
     * 无参构造函数
     */
    public AgentResponse() {}

    /**
     * 带类型和内容的构造函数
     *
     * @param type    响应类型
     * @param content 响应内容
     */
    public AgentResponse(String type, String content) {
        // 设置响应类型
        this.type = type;
        // 设置响应内容
        this.content = content;
    }

    /**
     * 带类型、内容和条数的构造函数
     *
     * @param type    响应类型
     * @param content 响应内容
     * @param count   数据条数
     */
    public AgentResponse(String type, String content, Integer count) {
        // 设置响应类型
        this.type = type;
        // 设置响应内容
        this.content = content;
        // 设置数据条数
        this.count = count;
    }

    /**
     * 创建text类型响应
     *
     * @param content 文本内容
     * @return JSON格式的响应字符串
     */
    public static String text(String content) {
        // 构造文本响应并转为JSON字符串
        return new AgentResponse(TYPE_TEXT, content).toJson();
    }

    /**
     * 创建thinking类型响应
     *
     * @param content 思考过程内容
     * @return JSON格式的响应字符串
     */
    public static String thinking(String content) {
        // 构造思考响应并转为JSON字符串
        return new AgentResponse(TYPE_THINKING, content).toJson();
    }

    /**
     * 创建reference类型响应（指定count）
     *
     * @param content 引用内容（通常为JSON数组字符串）
     * @param count   引用资料条数
     * @return JSON格式的响应字符串
     */
    public static String reference(String content, Integer count) {
        // 构造引用响应并转为JSON字符串
        return new AgentResponse(TYPE_REFERENCE, content, count).toJson();
    }

    /**
     * 创建reference类型响应（无count，自动解析JSON数组计算count）
     *
     * @param content 引用内容（尝试解析为JSON数组以自动计算条数）
     * @return JSON格式的响应字符串
     */
    public static String reference(String content) {
        try {
            // 尝试将内容解析为JSON数组
            var jsonArray = JSON.parseArray(content);
            // 解析成功且不为空
            if (jsonArray != null) {
                // 使用数组长度作为count
                return reference(content, jsonArray.size());
            }
        } catch (Exception e) {
            // 解析失败，count为null
        }
        // 解析失败时使用null作为count
        return reference(content, null);
    }



    /**
     * 将当前对象转换为JSON字符串
     * 对reference和recommend类型的content字段做特殊处理（尝试解析为JSON对象）
     *
     * @return JSON格式字符串
     */
    public String toJson() {
        // 创建JSON对象
        JSONObject obj = new JSONObject();
        // 写入响应类型字段
        obj.put("type", type);
        // 如果内容不为空，写入原始内容
        if (content != null) {
            obj.put("content", content);
        }
        // 如果条数不为空，写入条数字段
        if (count != null) {
            obj.put("count", count);
        }
        // 如果是引用或推荐类型且内容非空，尝试将content解析为JSON对象
        if ((TYPE_REFERENCE.equals(type) || TYPE_RECOMMEND.equals(type)) && content != null) {
            try {
                // 尝试将内容解析为JSON对象后写入（覆盖原始字符串）
                obj.put("content", JSON.parse(content));
            } catch (Exception e) {
                // 解析失败时保持原始字符串内容
                obj.put("content", content);
            }
        }
        // 如果附加数据不为空且非引用/推荐类型，写入附加数据字段
        if (data != null && !TYPE_REFERENCE.equals(type) && !TYPE_RECOMMEND.equals(type)) {
            obj.put("data", data);
        }
        // 将JSON对象转为字符串返回
        return obj.toJSONString();
    }

    /**
     * 创建自定义类型的JSON响应
     * 当类型为reference且内容为String时，自动解析JSON数组并计算count
     *
     * @param type    自定义响应类型
     * @param content 响应内容（可以是任意对象）
     * @return JSON格式的响应字符串
     */
    public static String json(String type, Object content) {
        // 如果是引用或推荐类型且内容为字符串，尝试解析JSON数组
        if ((TYPE_REFERENCE.equals(type) || TYPE_RECOMMEND.equals(type)) && content instanceof String jsonStr) {
            try {
                // 解析字符串为JSON数组
                var jsonArray = JSON.parseArray(jsonStr);
                // 数组非空时自动设置count
                if (jsonArray != null && !jsonArray.isEmpty()) {
                    return new AgentResponse(type, jsonStr, jsonArray.size()).toJson();
                }
            } catch (Exception e) {
                // 解析失败，使用普通json响应
            }
        }
        // 构造自定义类型响应并转为JSON
        return new AgentResponse(type, content == null ? null : content.toString()).toJson();
    }

    /**
     * 创建流式响应结束标记
     *
     * @return JSON格式的结束响应字符串
     */
    public static String complete() {
        // 构造结束响应并转为JSON字符串
        return new AgentResponse(TYPE_COMPLETE, "").toJson();
    }
}
