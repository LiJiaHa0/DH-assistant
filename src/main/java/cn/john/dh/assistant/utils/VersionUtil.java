package cn.john.dh.assistant.utils;

/**
 * 版本工具类
 * @Author John
 * @Date 2026-08-04 16:30
 */
public class VersionUtil {

    /**
     * 语义化版本号比较（如 "1.0.0" vs "2.1.3"）
     * <p>对非纯数字的版本段（如 "1.0.0-beta" 的 "0-beta"）容错：按首个数字段比较，无法解析的段按 0 处理，
     * 避免 NumberFormatException 导致上传流程崩溃。</p>
     *
     * @return 负数表示 v1 < v2，0 表示相等，正数表示 v1 > v2
     */
    public static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLength = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLength; i++) {
            int p1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }

    /**
     * 解析单个版本段：取开头连续数字，非数字开头或不可解析时返回 0
     */
    private static int parseVersionPart(String part) {
        if (part == null || part.isEmpty()) {
            return 0;
        }
        // 取开头连续数字部分（如 "0-beta" → "0"）
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
