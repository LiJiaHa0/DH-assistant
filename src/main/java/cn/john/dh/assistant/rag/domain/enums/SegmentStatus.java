package cn.john.dh.assistant.rag.domain.enums;

/**
 * @Author John
 * @Date 2026-08-06 12:53
 */
public enum SegmentStatus {

    /**
     * 关系型数据库存储完成
     */
    STORED,
    /**
     * 向量数据库存储完成（非必须）
     */
    VECTOR_STORED;
}
