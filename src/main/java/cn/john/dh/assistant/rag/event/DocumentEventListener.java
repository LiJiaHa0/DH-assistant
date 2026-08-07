package cn.john.dh.assistant.rag.event;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentVersionService;
import cn.john.dh.assistant.utils.BusinessExceptionUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.Assert;

import java.util.Objects;

/**
 * @Author John
 * @Date 2026-08-06 13:55
 */
@Slf4j
@Component
public class DocumentEventListener {


    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeDocumentVersionService knowledgeDocumentVersionService;


    /**
     * 监听文档CHUNKED事件，触发向量嵌入流程
     *
     * <p>使用 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 确保监听器在
     * 发布方事务提交后才执行。若改为 {@code @EventListener}，split() 的 @Transactional
     * 事务尚未提交时监听器即被触发（@Async线程可见旧数据），导致
     * embedAndStore() 读到的 status 仍为 CONVERTED 而非 CHUNKED，进而提前返回 false。</p>
     *
     * <p>注意：@Async + @TransactionalEventListener 组合时，若发布方事务回滚，
     * 监听器不会被触发（AFTER_COMMIT 语义保证）。</p>
     *
     * @param event 文档已分段事件
     */
    @Async("eventListenerExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentChunked(DocumentChunkedEvent event) {
        Long documentId = event.getDocumentId();
        log.info("收到文档CHUNKED事件（事务已提交），开始执行向量嵌入，documentId: {}, segmentCount: {}",
                documentId, event.getSegmentCount());
        try {
            KnowledgeDocument document = knowledgeDocumentService.getById(documentId);
            BusinessExceptionUtils.throwBusinessException(Objects.isNull(document), "文档不存在");
            boolean result = knowledgeDocumentVersionService.embeddingVersion(event.getDocumentVersionId(),document);
            BusinessExceptionUtils.throwBusinessException(!result, "向量嵌入失败");
            if (event.getDocumentVersionId().equals(document.getCurrentVersionId())) {
                document.setStatus(DocumentStatus.VECTOR_STORED);
                boolean docResult = knowledgeDocumentService.updateById(document);
                Assert.isTrue(docResult, "文档状态更新失败: docId=" + documentId);
                log.info("文档状态已同步为 VECTOR_STORED, docId={}", documentId);
            }
        } catch (Exception e) {
            log.error("向量嵌入失败，documentId: {}", documentId, e);
        }
    }

}
