package cn.john.dh.assistant.rag.event;

import org.checkerframework.checker.units.qual.A;
import org.springframework.context.ApplicationEvent;

import java.time.Clock;

/**
 * @Author John
 * @Date 2026-08-06 12:55
 */
public class DocumentChunkedEvent extends ApplicationEvent {


    /**
     * 文档ID
     */
    private final Long documentId;

    /**
     * 文档的版本号
     */
    private final Long documentVersionId;

    /**
     * 分段数量
     */
    private final int segmentCount;

    public DocumentChunkedEvent(Object source, Long documentId, Long documentVersionId, int segmentCount) {
        super(source);
        this.documentId = documentId;
        this.documentVersionId = documentVersionId;
        this.segmentCount = segmentCount;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public Long getDocumentVersionId() {
        return documentVersionId;
    }
    public int getSegmentCount() {
        return segmentCount;
    }

    @Override
    public String toString() {
        return "DocumentChunkedEvent{documentId=" + documentId + ", segmentCount=" + segmentCount + '}';
    }
}
