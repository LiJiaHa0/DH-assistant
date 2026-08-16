package cn.john.dh.assistant.chat.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 使用统计工具。
 * 优先读取模型返回的 Usage 元数据；缺失时按输出字符数保守估算。
 */
public final class ChatTokenUsageUtil {

    private static final Logger log = LoggerFactory.getLogger(ChatTokenUsageUtil.class);

    private ChatTokenUsageUtil() {
    }

    /**
     * 从 ChatResponse 中记录 prompt / generation token。
     * 流式响应通常只在最后一个 chunk 携带 Usage，因此用 max 覆盖。
     * <p>兼容 Spring AI 1.1.0 中 Usage 接口方法命名差异（getGenerationTokens / getCompletionTokens）。
     */
    public static void recordUsage(ChatResponse response,
                                   AtomicLong promptTokens,
                                   AtomicLong generationTokens) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            // 无 Usage 元数据时，用输出文本长度兜底
            fallbackGenerationCount(response, generationTokens);
            return;
        }
        var usage = response.getMetadata().getUsage();
        Long prompt = toLong(usage.getPromptTokens());
        if (prompt != null) {
            promptTokens.set(Math.max(promptTokens.get(), prompt));
        }
        Long generation = getGenerationTokens(usage);
        if (generation != null) {
            generationTokens.set(Math.max(generationTokens.get(), generation));
        }
    }

    /**
     * 将 Number 类型安全转换为 Long（兼容 Integer / Long）。
     */
    private static Long toLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    /**
     * 兼容读取 generation token 数量。
     * Spring AI 1.1.0 部分实现使用 getGenerationTokens()，旧版本使用 getCompletionTokens()。
     */
    private static Long getGenerationTokens(org.springframework.ai.chat.metadata.Usage usage) {
        if (usage == null) {
            return null;
        }
        try {
            var method = usage.getClass().getMethod("getGenerationTokens");
            Object value = method.invoke(usage);
            return toLong(value);
        } catch (NoSuchMethodException e) {
            // 降级到 getCompletionTokens
        } catch (Exception e) {
            log.warn("读取 Usage generation token 失败: {}", e.getMessage());
            return null;
        }
        try {
            var method = usage.getClass().getMethod("getCompletionTokens");
            Object value = method.invoke(usage);
            return toLong(value);
        } catch (Exception e) {
            log.warn("读取 Usage completion token 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 同步调用无 Usage 时，按输出字符数估算 generation token。
     * 中文场景按 1 token ≈ 1 字符估算（偏保守，确保不会超支）。
     */
    public static void recordGenerationByChars(String text, AtomicLong generationTokens) {
        if (text == null || text.isEmpty()) {
            return;
        }
        generationTokens.addAndGet(Math.max(1, text.length()));
    }

    public static long getTotalTokens(AtomicLong promptTokens, AtomicLong generationTokens) {
        return promptTokens.get() + generationTokens.get();
    }

    /**
     * 无 Usage 元数据时，按当前 chunk 输出文本长度估算 generation token。
     * <p>注意：流式响应每个 chunk 都会走到这里，因此用 max 覆盖而非累加，
     * 否则一次长回复会按"chunk 数 × 平均长度"被严重高估（真实 Usage 到达时
     * max 覆盖无法抵消之前的累加值），导致每日限额被提前耗尽。</p>
     */
    private static void fallbackGenerationCount(ChatResponse response, AtomicLong generationTokens) {
        if (response == null || response.getResult() == null) {
            return;
        }
        Generation gen = response.getResult();
        if (gen.getOutput() == null || gen.getOutput().getText() == null) {
            return;
        }
        String text = gen.getOutput().getText();
        if (text != null && !text.isEmpty()) {
            // 取 max 而非累加：只保留最大单个 chunk 的估算值，避免流式场景指数级高估
            generationTokens.set(Math.max(generationTokens.get(), Math.max(1, text.length())));
        }
    }
}
