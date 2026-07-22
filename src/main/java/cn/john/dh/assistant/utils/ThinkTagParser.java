package cn.john.dh.assistant.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @Author John
 * @Date 2026-07-22 00:22
 */
public class ThinkTagParser {

    // 匹配think标签及其内容的正则表达式，启用点号匹配换行
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL);


    /**
     * 从文本中移除所有think标签及其内容
     * @param text
     * @return
     */
    public static String stripThinkTags(String text) {
        // 输入为空时直接返回null
        if (text == null) return null;
        // 使用正则替换所有think标签内容并去除首尾空白
        return THINK_TAG_PATTERN.matcher(text).replaceAll("").trim();
    }


    /**
     * 解析文本块，识别think标签内的内容为思考内容，否则为普通文本内容
     * @param chunk
     * @param inThink
     * @return
     */
    public static ParseResult parse(String chunk, boolean inThink) {
        // 检查输入文本块是否为空
        if (chunk == null || chunk.isEmpty()) {
            // 输入为空时返回空段落列表和当前思考状态
            return new ParseResult(new ArrayList<>(), inThink);
        }
        // 创建段落列表存储解析结果
        List<Segment> segments = new ArrayList<>();
        // 创建字符串构建器累积当前段落内容
        StringBuilder current = new StringBuilder();
        // 初始化当前是否处于think标签内的状态
        boolean currentlyInThink = inThink;
        // 逐字符遍历文本块进行解析
        for (int i = 0; i < chunk.length(); i++) {
            // 当前不在think标签内且剩余字符足够时检查开始标签，检查当前位置是否为think开始标签
            if (!currentlyInThink && i + 6 < chunk.length() &&
                    chunk.substring(i, i + 7).equals("<think>")) {
                // 如果有累积的非思考文本
                if (!current.isEmpty()) {
                    // 将其作为普通文本段落添加
                    segments.add(new Segment(current.toString(), false));
                    // 重置字符串构建器
                    current = new StringBuilder();
                }
                // 设置状态为在think标签内
                currentlyInThink = true;
                i += 6; // skip past <think>
                // 跳过think开始标签，继续下一个字符
                continue;
            }
            // 当前在think标签内且剩余字符足够时检查结束标签，检查当前位置是否为think结束标签
            if (currentlyInThink && i + 7 < chunk.length() && // 当前在think标签内且剩余字符足够时检查结束标签
                    chunk.substring(i, i + 8).equals("</think>")) { // 检查当前位置是否为think结束标签
                // 如果有累积的思考文本，如果有累积的思考文本
                if (!current.isEmpty()) {
                    // 将其作为思考文本段落添加
                    segments.add(new Segment(current.toString(), true));
                    // 重置字符串构建器
                    current = new StringBuilder();
                }
                // 设置状态为不在think标签内
                currentlyInThink = false;
                i += 7; // skip past </think>
                // 跳过think结束标签，继续下一个字符
                continue;
            }
            // 将当前字符追加到累积内容中
            current.append(chunk.charAt(i));
        }
        // 如果遍历结束后还有剩余内容未添加
        if (!current.isEmpty()) {
            // 将剩余内容作为段落添加到列表
            segments.add(new Segment(current.toString(), currentlyInThink));
        }
        // 返回包含所有段落和最终思考状态的解析结果
        return new ParseResult(segments, currentlyInThink);
    }


    /**
     * 文本段落记录类，包含内容和是否为思考内容的标记
     * @param content
     * @param thinking
     */
    public record Segment(String content, boolean thinking) {}

    /**
     * 解析结果记录类，包含段落列表和是否在think标签内的状态
     * @param segments
     * @param inThink
     */
    public record ParseResult(List<Segment> segments, boolean inThink) {

    }
}
