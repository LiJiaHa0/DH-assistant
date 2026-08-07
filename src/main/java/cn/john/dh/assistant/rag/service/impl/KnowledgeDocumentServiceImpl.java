package cn.john.dh.assistant.rag.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.common.BusinessException;
import cn.john.dh.assistant.constant.MetadataKeyConstant;
import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.domain.dto.KnowledgeDocumentUpdateDTO;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocumentVersion;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeSegment;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.domain.enums.FileType;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import cn.john.dh.assistant.rag.domain.enums.SegmentStatus;
import cn.john.dh.assistant.rag.domain.record.DocumentSplitParam;
import cn.john.dh.assistant.rag.domain.record.KnowledgeUploadParam;
import cn.john.dh.assistant.rag.event.DocumentChunkedEvent;
import cn.john.dh.assistant.rag.mapper.KnowledgeDocumentMapper;
import cn.john.dh.assistant.rag.service.DocumentIngestionService;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentVersionService;
import cn.john.dh.assistant.rag.service.KnowledgeSegmentService;
import cn.john.dh.assistant.rag.spiltter.DocumentSplitterFactory;
import cn.john.dh.assistant.rag.spiltter.ExcelSplitter;
import cn.john.dh.assistant.rag.strategy.FileProcessService;
import cn.john.dh.assistant.rag.strategy.FileProcessServiceFactory;
import cn.john.dh.assistant.utils.BusinessExceptionUtils;
import cn.john.dh.assistant.utils.FileTypeUtil;
import cn.john.dh.assistant.utils.SnowflakeIdGenerator;
import cn.john.dh.assistant.utils.VersionUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.base.Stopwatch;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.utils.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 知识文档 Service 实现类
 *
 * @Author John
 * @Date 2026-07-30
 */
@Service
@Slf4j
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument> implements KnowledgeDocumentService {


    @Autowired
    private KnowledgeDocumentVersionService knowledgeDocumentVersionService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private FileProcessServiceFactory fileProcessServiceFactory;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private DocumentIngestionService documentIngestionService;


    /**
     * 保存知识文档
     *
     * @param param
     * @return
     */
    @Override
    public KnowledgeDocument uploadDocument(KnowledgeUploadParam param) throws IOException {
        //判断文件内容是否已经上传过了
        String contentHash = calculateContentHash(param.file());
        // 检查是否已存在相同内容的版本（跨文档跨版本去重）
        if (knowledgeDocumentVersionService.existsByContentHash(contentHash)) {
            throw new BusinessException("文档内容已存在，请勿重复上传");
        }
        // 创建初始版本记录
        String userId = StpUtil.getLoginIdAsString();
        //创建知识文档
        KnowledgeDocument knowledgeDocument = new KnowledgeDocument().create(param);
        knowledgeDocument.setUserId(userId);
        boolean result = save(knowledgeDocument);
        if (!result) {
            throw new BusinessException("文件上传失败");
        }
        String fileName = param.file().getOriginalFilename();
        log.info("开始上传文件{}....", fileName);
        // 用minio上传
        String fileUrl = null;
        try {
            fileUrl = fileStorageService.uploadFile(param.file(), fileName);
        } catch (Exception e) {
            removeDocumentWithSegments(knowledgeDocument.getDocId());
            log.error("文件上传失败，文档已删除", e);
            throw new BusinessException("文件上传失败，请稍后重试");
        }
        KnowledgeDocumentVersion versionRecord = createVersionRecord(
                knowledgeDocument.getDocId(), userId, param.version(), fileUrl, null,
                userId, contentHash, DocumentStatus.UPLOADED, null);
        //设置当前版本
        knowledgeDocument.setCurrentVersionId(versionRecord.getId());
        // 处理文档（转换/存储），获取转换后的文档URL
        String convertedDocUrl = processFile(fileName, param.file(), knowledgeDocument, fileUrl);
        // 更新版本记录的转换后URL
        versionRecord = knowledgeDocumentVersionService.getById(versionRecord.getId());
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        result = knowledgeDocumentVersionService.updateById(versionRecord);
        if (!result) {
            throw new BusinessException("版本记录更新失败");
        }
        // 更新文档的当前版本
        KnowledgeDocument documentInDb = getById(knowledgeDocument.getDocId());
        documentInDb.setCurrentVersionId(versionRecord.getId());
        result = updateById(documentInDb);
        if (!result) {
            throw new BusinessException("文档当前版本更新失败");
        }
        // 将最新数据同步回原对象，避免使用旧内存状态覆盖数据库
        knowledgeDocument.setStatus(documentInDb.getStatus());
        knowledgeDocument.setCurrentVersionId(documentInDb.getCurrentVersionId());
        return knowledgeDocument;
    }

    /**
     * 上传新版本
     * @param docId     文档ID（knowledge_document.doc_id）
     * @param version   新版本号（语义化版本，如 "2.0.0"，必须大于现有最大版本号）
     * @param file      新版本文件
     * @param changelog 版本变更说明（可选）
     * @return
     * @throws IOException
     */
    @Override
    public KnowledgeDocument uploadNewVersion(Long docId, String version, MultipartFile file, String changelog) throws IOException {
        // 查询文档
        KnowledgeDocument document = getById(docId);
        Assert.notNull(document, "文档不存在");

        // 校验版本号必须大于已有最大版本号
        String latestVersion = knowledgeDocumentVersionService.getLatestVersion(docId);
        if (latestVersion != null && VersionUtil.compareVersions(version, latestVersion) <= 0) {
            throw new IllegalArgumentException("版本号 " + version + " 不大于现有最新版本号 " + latestVersion + "，请使用更大的版本号");
        }
        // 计算文件内容hash，用于去重
        String contentHash = calculateContentHash(file);
        // 检查是否已存在相同内容的版本（跨文档跨版本去重）
        if (knowledgeDocumentVersionService.existsByContentHash(contentHash)) {
            throw new IllegalArgumentException("文档内容已存在，请勿重复上传");
        }
        KnowledgeDocumentVersion versionRecord = null;
        log.info("start to upload version {} for doc {} ....", version, docId);

        // 1. 上传新版本文件到MinIO（不清理旧版本数据，保证处理期间旧版本仍可查询）
        String fileName = file.getOriginalFilename();
        String fileUrl = null;
        try {
            fileUrl = fileStorageService.uploadFile(file, fileName);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new BusinessException("文件上传失败，请稍后重试");
        }
        // 2. 先创建新版本记录，使 processDocument 内部可以推进版本状态
        String userId = StpUtil.getLoginIdAsString();
        versionRecord = createVersionRecord(
                document.getDocId(),userId, version, fileUrl, null,
                userId, contentHash, DocumentStatus.UPLOADED, changelog);
        // 这一步先不更新数据库，只是为了让后续的操作能从document中取出version，避免npm和流程走不下去
        // document的更新会在最后执行，确保前置流程都完成后实现版本的切换。
        document.setCurrentVersionId(versionRecord.getId());
        // 3. 处理文档（转换/存储），获取转换后的文档URL
        String convertedDocUrl = processFile(fileName, file, document, fileUrl);
        // 4. 更新版本记录的转换后URL
        versionRecord = knowledgeDocumentVersionService.getById(versionRecord.getId());
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        boolean result = knowledgeDocumentVersionService.updateById(versionRecord);
        BusinessExceptionUtils.throwBusinessException(!result, "版本记录更新失败");
        result = updateById(document);
        BusinessExceptionUtils.throwBusinessException(!result, "文档当前版本更新失败");
        log.info("文档 {} 新版本 {} 上传完成，旧版本数据保留中，待新版本向量化完成后自动清理", docId, version);
        return document;
    }

    /**
     * 文档分段
     * <p>必须开启事务：末尾发布的 DocumentChunkedEvent 由 @TransactionalEventListener(AFTER_COMMIT)
     * 监听，无活动事务时事件会被静默丢弃；同时保证分段保存与状态推进的原子性。</p>
     *
     * @param documentId
     * @param documentSplitParam
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int split(Long documentId, DocumentSplitParam documentSplitParam) {
        KnowledgeDocument knowledgeDocument = getById(documentId);
        BusinessExceptionUtils.throwBusinessException(Objects.isNull(knowledgeDocument),"文档不存在");
        //从版本表中找到当前版本的文件地址
        KnowledgeDocumentVersion versionRecord = knowledgeDocumentVersionService.getById(knowledgeDocument.getCurrentVersionId());
        BusinessExceptionUtils.throwBusinessException(Objects.isNull(versionRecord),"文档版本不存在");
        BusinessExceptionUtils.throwBusinessException(StringUtils.isEmpty(versionRecord.getConvertedDocUrl()),"文档未转换完成");
        if (versionRecord.getStatus() == DocumentStatus.CHUNKED) {
            // 返回已切分的分段数量（仅统计当前版本的分段，排除旧版本残留）
            Long chunkedCount = knowledgeSegmentService.count(new QueryWrapper<KnowledgeSegment>()
                    .eq("document_id", knowledgeDocument.getDocId())
                    .eq("document_version", knowledgeDocument.getCurrentVersionId())
                    .eq("skipEmbedding", 0));
            return chunkedCount.intValue();
        }
        BusinessExceptionUtils.throwBusinessException(versionRecord.getStatus() != DocumentStatus.CONVERTED,"文档状态不为已转换，无法切分");
        String fileName = fileStorageService.extractObjectNameFromUrl(versionRecord.getConvertedDocUrl());
        BusinessExceptionUtils.throwBusinessException(StringUtils.isEmpty(fileName),"无法读取文件名，解析文档失败");
        List<Document> documents = new ArrayList<>();
        // 下载文件，根据文档类型进行处理，excel单独处理，其他文档统一处理
        try (InputStream inputStream = fileStorageService.downloadFile(fileName)) {
            //EXCEL单独处理，因为他不是Document类型
            if (FileType.EXCEL == FileTypeUtil.getFileType(versionRecord.getConvertedDocUrl()) || FileType.CSV == FileTypeUtil.getFileType(versionRecord.getConvertedDocUrl())) {
                ExcelSplitter splitter = new ExcelSplitter(documentSplitParam.chunkSize(), false);
                documents = splitter.split(inputStream.readAllBytes());
            } else {
                TextSplitter textSplitter = DocumentSplitterFactory.getInstance(documentSplitParam);
                Document doc = new Document(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                documents = textSplitter.split(doc);
            }
        } catch (Exception e) {
            throw new RuntimeException("下载文档失败: " + e.getMessage(), e);
        }
        List<KnowledgeSegment> knowledgeSegments = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            KnowledgeSegment knowledgeSegment = new KnowledgeSegment();
            knowledgeSegment.setText(document.getText());
            // 如果切分器未设置 chunkId（如按长度切分、按正则切分），则自动生成唯一ID
            Object chunkIdObj = document.getMetadata().get(MetadataKeyConstant.CHUNK_ID);
            String chunkId = chunkIdObj != null ? chunkIdObj.toString() : SnowflakeIdGenerator.getInstance().nextIdStr();
            if (chunkIdObj == null) {
                document.getMetadata().put(MetadataKeyConstant.CHUNK_ID, chunkId);
            }
            knowledgeSegment.setChunkId(chunkId);
            Map<String, Object> metadata = document.getMetadata();
            knowledgeSegment.setMetadata(enrichMetadata(knowledgeDocument, versionRecord, metadata));
            knowledgeSegment.setDocumentId(knowledgeDocument.getDocId());
            knowledgeSegment.setDocumentVersion(knowledgeDocument.getCurrentVersionId());
            knowledgeSegment.setChunkOrder(i);
            // 检查是否需要跳过嵌入
            Integer skipEmbedding = (Integer) metadata.get(MetadataKeyConstant.SKIP_EMBEDDING);
            if (skipEmbedding != null && skipEmbedding == 1) {
                knowledgeSegment.setSkipEmbedding(1);
                knowledgeSegment.setStatus(SegmentStatus.STORED);
            } else {
                knowledgeSegment.setSkipEmbedding(0);
                knowledgeSegment.setStatus(SegmentStatus.STORED);
            }
            knowledgeSegments.add(knowledgeSegment);
        }
        // 批量保存片段
        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean saveResult = knowledgeSegmentService.saveBatch(knowledgeSegments);
        Assert.isTrue(saveResult, "保存知识片段失败");
        log.info("保存知识片段耗时: {}", stopwatch.elapsed().toMillis());
        // 获取分段数量
        int segmentCount = knowledgeSegments.size();
        //更新文档状态为 CHUNKED，并保存分段参数
        boolean advanceResult = advanceDocumentAndVersionStatus(knowledgeDocument.getDocId(), knowledgeDocument.getCurrentVersionId(), DocumentStatus.CHUNKED);
        Assert.isTrue(advanceResult, "更新文档版本状态失败");
        // 发送文档已分段事件
        publishChunkedEvent(knowledgeDocument, segmentCount);
        return segmentCount;
    }

    /**
     * 发送文档已分段事件
     */
    private void publishChunkedEvent(KnowledgeDocument document, int segmentCount) {
        log.info("发送文档CHUNKED事件，documentId: {}, segmentCount: {}", document.getDocId(), segmentCount);
        DocumentChunkedEvent event = new DocumentChunkedEvent(this, document.getDocId(), document.getCurrentVersionId(), segmentCount);
        eventPublisher.publishEvent(event);
    }


    /**
     * 填充元数据
     *
     * @param document         文档信息
     * @param metadata         元数据
     * @return
     */
    private static String enrichMetadata(KnowledgeDocument document, KnowledgeDocumentVersion versionRecord, Map<String, Object> metadata) {
        metadata.put(MetadataKeyConstant.DOC_ID, document.getDocId());
        metadata.put(MetadataKeyConstant.FILE_NAME, document.getDocTitle());
        metadata.put(MetadataKeyConstant.URL, versionRecord.getDocUrl());
        if (document.getCurrentVersionId() != null) {
            metadata.put(MetadataKeyConstant.VERSION, document.getCurrentVersionId());
            metadata.put(MetadataKeyConstant.VERSION_ID, document.getCurrentVersionId());
        }
        Map<String, Object> metadataMap = new HashMap<>(metadata);
        metadataMap.put(MetadataKeyConstant.ACCESSIBLE_BY, document.getAccessibleBy());

        return JSON.toJSONString(metadataMap);
    }

    /**
     * 删除文档及其所有关联数据（版本、分段）
     * @param docId 文档ID
     * @return
     */
    @Override
    public boolean removeDocumentWithSegments(Long docId) {
        KnowledgeDocument document = getById(docId);
        if (document == null) {
            return false;
        }
        // 1. 获取所有版本记录，提取MinIO文件URL用于清理
        List<KnowledgeDocumentVersion> versions = knowledgeDocumentVersionService.listByDocId(docId);
        // 2. 尝试删除MinIO文件（best effort，失败不影响删除流程）
        for (KnowledgeDocumentVersion version : versions) {
            deleteMinioFileSafe(version.getDocUrl());
            if (version.getConvertedDocUrl() != null && !version.getConvertedDocUrl().equals(version.getDocUrl())) {
                deleteMinioFileSafe(version.getConvertedDocUrl());
            }
        }
        // 3. 逻辑删除所有分段
        knowledgeSegmentService.remove(new QueryWrapper<KnowledgeSegment>()
                .eq("document_id", docId));
        // 4. 逻辑删除所有版本记录
        knowledgeDocumentVersionService.remove(new QueryWrapper<KnowledgeDocumentVersion>()
                .eq("doc_id", docId));
        // 5. 逻辑删除文档本身
        return removeById(docId);
    }

    /**
     * 编辑文档基础信息（标题、描述）
     *
     * @param dto 编辑请求参数
     * @return 更新后的文档记录
     */
    @Override
    public KnowledgeDocument updateDocument(KnowledgeDocumentUpdateDTO dto) {
        Assert.notNull(dto.getDocId(), "文档ID不能为空");
        KnowledgeDocument document = getById(dto.getDocId());
        Assert.notNull(document, "文档不存在: docId=" + dto.getDocId());
        // 仅更新标题和描述，其他字段保持不变
        if (dto.getDocTitle() != null && !dto.getDocTitle().isBlank()) {
            document.setDocTitle(dto.getDocTitle().trim());
        }
        document.setDescription(dto.getDescription());
        boolean result = updateById(document);
        BusinessExceptionUtils.throwBusinessException(!result, "文档更新失败");
        return getById(dto.getDocId());
    }

    /**
     * 批量删除文档，并级联逻辑删除关联的版本和分段
     *
     * @param docIds 文档ID列表
     * @return 成功删除的数量
     */
    @Override
    public int batchRemoveDocumentsWithSegments(List<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Long docId : docIds) {
            try {
                if (removeDocumentWithSegments(docId)) {
                    count++;
                }
            } catch (Exception e) {
                log.error("删除文档失败: docId={}", docId, e);
            }
        }
        return count;
    }

    /**
     * 激活指定版本，使其成为当前版本
     *   1. 校验版本状态必须为 CHUNKED
     *   2. 对该版本下所有 STORED 且未向量化的分段分批 embed 并写入 ES
     *   3. 更新分段状态为 VECTOR_STORED
     *   4. 将版本记录状态从 CHUNKED 升为 VECTOR_STORED
     * @param versionId 版本ID（knowledge_document_version.version_id）
     * @param knowledgeDocument 版本ID（knowledge_document_version.version_id）
     */
    @Override
    public void activateVersion(Long versionId, KnowledgeDocument knowledgeDocument) {
        KnowledgeDocumentVersion version = knowledgeDocumentVersionService.getById(versionId);
        BusinessExceptionUtils.throwBusinessException(Objects.isNull(version),"版本不存在: versionId=" + versionId);
        if (version.getStatus() == DocumentStatus.VECTOR_STORED) {
            return;
        }
        BusinessExceptionUtils.throwBusinessException(DocumentStatus.CHUNKED != version.getStatus(),
                "版本状态不是 CHUNKED，无法执行生效操作，当前状态: " + version.getStatus());
        Long docId = version.getDocId();
        log.info("开始让版本生效（重新向量化）, docId={}, versionId={}", docId, versionId);
        // 分页扫描 STORED 且未向量化的分段（skipEmbedding=0）
        LambdaQueryWrapper<KnowledgeSegment> queryWrapper = Wrappers.<KnowledgeSegment>lambdaQuery()
                .eq(KnowledgeSegment::getDocumentId, docId)
                .eq(KnowledgeSegment::getDocumentVersion, versionId)
                .eq(KnowledgeSegment::getStatus, SegmentStatus.STORED)
                .eq(KnowledgeSegment::getSkipEmbedding, 0)
                .isNull(KnowledgeSegment::getEmbeddingId);
        // 分页查询
        Page<KnowledgeSegment> page = knowledgeSegmentService.page(new Page<>(1, 100), queryWrapper);
        while (!page.getRecords().isEmpty()) {
            List<KnowledgeSegment> batch = page.getRecords();
            documentIngestionService.ingest(batch, knowledgeDocument.getDocType());
            page = knowledgeSegmentService.page(new Page<>(page.getCurrent(), 100), queryWrapper);
        }
    }

    /**
     * 让指定版本失效，使其不再提供服务
     * @param versionId 版本ID（knowledge_document_version.version_id）
     * @param knowledgeBase 知识库信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deactivateVersion(Long versionId, KnowledgeBase knowledgeBase) {
        KnowledgeDocumentVersion version = knowledgeDocumentVersionService.getById(versionId);
        Assert.notNull(version, "版本记录不存在: versionId=" + versionId);
        if (version.getStatus() == DocumentStatus.CHUNKED) {
            return;
        }
        BusinessExceptionUtils.throwBusinessException(version.getStatus() != DocumentStatus.VECTOR_STORED,
                "版本状态不是 VECTOR_STORED，无法执行失效操作，当前状态: " + version.getStatus());
        Long docId = version.getDocId();
        log.info("开始让版本失效, docId={}, versionId={}", docId, versionId);
        documentIngestionService.deleteByDocIdAndVersionId(docId, versionId, knowledgeBase);
        LambdaUpdateWrapper<KnowledgeSegment> segUpdate = Wrappers.<KnowledgeSegment>lambdaUpdate()
                .set(KnowledgeSegment::getStatus, SegmentStatus.STORED)
                .set(KnowledgeSegment::getEmbeddingId, null)
                .eq(KnowledgeSegment::getDocumentId, docId)
                .eq(KnowledgeSegment::getDocumentVersion, versionId)
                .eq(KnowledgeSegment::getStatus, SegmentStatus.VECTOR_STORED);
        boolean affected = knowledgeSegmentService.update(null, segUpdate);
        log.info("降级分段状态完成, versionId={}, affected={}", versionId, affected);
        // 3. 将版本记录状态从 VECTOR_STORED 降为 CHUNKED
        version.setStatus(DocumentStatus.CHUNKED);
        boolean versionUpdateResult = knowledgeDocumentVersionService.updateById(version);
        Assert.isTrue(versionUpdateResult, "文档版本状态更新失败");
        log.info("版本失效完成, versionId={}", versionId);
    }

    /**
     * 安全删除MinIO文件（best effort，失败仅记录日志不抛出异常）
     *
     * @param fileUrl MinIO文件URL
     */
    private void deleteMinioFileSafe(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        try {
            String objectName = extractObjectName(fileUrl);
            fileStorageService.deleteFile(objectName);
        } catch (Exception e) {
            log.warn("删除MinIO文件失败，跳过继续: url={}, error={}", fileUrl, e.getMessage());
        }
    }

    /**
     * 从MinIO完整URL中提取对象名称
     * URL格式: {endpoint}/{bucketName}/{objectName}
     * 例如: http://localhost:9000/dh-assistant/fileName.ext → fileName.ext
     *
     * @param url MinIO文件URL
     * @return 对象名称
     */
    private String extractObjectName(String url) {
        String[] parts = url.split("/", 5);
        if (parts.length >= 5) {
            return parts[4];
        }
        return url;
    }

    /**
     * 推进文档和版本状态
     *
     * @param docId        文档ID
     * @param versionId    版本ID（knowledge_document_version.version_id）
     * @param targetStatus 目标状态，如 CONVERTING、CONVERTED、CHUNKED、VECTOR_STORED、STORED
     * @return
     */
    @Override
    public boolean advanceDocumentAndVersionStatus(Long docId, Long versionId, DocumentStatus targetStatus) {
        Assert.notNull(docId, "文档ID不能为空");
        Assert.notNull(versionId, "版本ID不能为空");
        Assert.notNull(targetStatus, "目标状态不能为空");

        KnowledgeDocument document = this.getById(docId);
        Assert.notNull(document, "文档不存在: docId=" + docId);

        KnowledgeDocumentVersion version = knowledgeDocumentVersionService.getById(versionId);
        Assert.notNull(version, "版本记录不存在: versionId=" + versionId);
        Assert.isTrue(docId.equals(version.getDocId()), "版本不属于该文档");
        boolean updated = false;
        if (shouldAdvanceStatus(document.getStatus(), targetStatus)) {
            document.setStatus(targetStatus);
            boolean docResult = this.updateById(document);
            Assert.isTrue(docResult, "文档状态更新失败: docId=" + docId);
            updated = true;
            log.info("文档状态已推进, docId={}, status={}", docId, targetStatus);
        } else {
            log.info("文档状态无需推进, docId={}, currentStatus={}, targetStatus={}",
                    docId, document.getStatus(), targetStatus);
        }
        // 推进版本状态
        if (shouldAdvanceStatus(version.getStatus(), targetStatus)) {
            version.setStatus(targetStatus);
            boolean versionResult = knowledgeDocumentVersionService.updateById(version);
            Assert.isTrue(versionResult, "版本状态更新失败: versionId=" + versionId);
            updated = true;
            log.info("版本状态已推进, versionId={}, status={}", versionId, targetStatus);
        } else {
            log.info("版本状态无需推进, versionId={}, currentStatus={}, targetStatus={}",
                    versionId, version.getStatus(), targetStatus);
        }
        return updated;
    }

    /**
     * 判断状态是否需要推进。
     * 当前状态为空或按枚举声明顺序早于目标状态时，才允许推进。
     */
    private boolean shouldAdvanceStatus(DocumentStatus current, DocumentStatus target) {
        if (current == null) {
            return true;
        }
        return current.ordinal() < target.ordinal();
    }

    /**
     * 创建版本记录
     *
     * @param docId           文档ID
     * @param version         版本号（语义化版本，如 "1.0.0"）
     * @param docUrl          原始文档URL（MinIO）
     * @param convertedDocUrl 转换后的文档URL
     * @param uploadUser      上传用户
     * @param contentHash     内容哈希
     * @param status          文档状态
     * @param changelog       变更说明
     * @return 保存后的版本记录
     */
    private KnowledgeDocumentVersion createVersionRecord(Long docId, String userId, String version, String docUrl,
                                                         String convertedDocUrl, String uploadUser,
                                                         String contentHash, DocumentStatus status, String changelog) {
        KnowledgeDocumentVersion versionRecord = new KnowledgeDocumentVersion();
        versionRecord.setDocId(docId);
        versionRecord.setUserId(userId);
        versionRecord.setVersion(version);
        versionRecord.setDocUrl(docUrl);
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        versionRecord.setContentHash(contentHash);
        versionRecord.setStatus(status);
        versionRecord.setUploadUser(uploadUser);
        versionRecord.setChangelog(changelog);
        knowledgeDocumentVersionService.save(versionRecord);
        log.info("创建版本记录成功, docId: {}, version: {}, versionId: {}",
                docId, version, versionRecord.getId());
        return versionRecord;
    }


    /**
     * 计算文件内容的SHA-256哈希值
     *
     * @param file 上传的文件
     * @return SHA-256哈希的十六进制字符串
     */
    private String calculateContentHash(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256算法不可用", e);
        }
    }

    /**
     * 处理文档（转换/存储）
     */
    private String processFile(String fileName, MultipartFile documentUploadParam, KnowledgeDocument document, String fileUrl) throws IOException {
        String convertedDocUrl;
        // 获取文件处理服务
        FileProcessService fileProcessService = fileProcessServiceFactory.get(FileTypeUtil.getFileType(fileName, documentUploadParam), document.getKnowledgeBaseType());
        // 如果文件处理服务存在，则调用处理方法
        if (fileProcessService != null) {
            convertedDocUrl = fileProcessService.processDocument(document, documentUploadParam.getInputStream());
        } else {
            // 如果文件处理服务不存在，则根据文档类型设置目标状态并更新文档状态
            DocumentStatus targetStatus = document.getKnowledgeBaseType() == KnowledgeBaseType.DOCUMENT_SEARCH
                    ? DocumentStatus.CONVERTED : DocumentStatus.STORED;
            advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), targetStatus);
            document.setStatus(targetStatus);
            convertedDocUrl = fileUrl;
        }
        return convertedDocUrl;
    }
}
