package cn.john.dh.assistant.entity;

/**
 * 评审评估结果记录，用于Plan-Execute代理中的质量评估
 * 包含评审是否通过及对应的反馈意见
 * @Author John
 * @Date 2026-07-28 11:12
 */
public record CritiqueResult(
        boolean passed,    // 评审是否通过
        String feedback    // 评审反馈意见
) {
}
