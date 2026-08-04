package cn.john.dh.assistant.rag.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.common.BusinessException;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocumentVersion;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import cn.john.dh.assistant.rag.domain.record.KnowledgeUploadParam;
import cn.john.dh.assistant.rag.mapper.KnowledgeDocumentMapper;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentVersionService;
import cn.john.dh.assistant.rag.strategy.FileProcessService;
import cn.john.dh.assistant.rag.strategy.FileProcessServiceFactory;
import cn.john.dh.assistant.utils.BusinessExceptionUtils;
import cn.john.dh.assistant.utils.FileTypeUtil;
import cn.john.dh.assistant.utils.VersionUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
        knowledgeDocument.setCurrentVersionId(versionRecord.getVersionId());
        // 处理文档（转换/存储），获取转换后的文档URL
        String convertedDocUrl = processFile(fileName, param.file(), knowledgeDocument, fileUrl);
        // 更新版本记录的转换后URL
        versionRecord = knowledgeDocumentVersionService.getById(versionRecord.getVersionId());
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        result = knowledgeDocumentVersionService.updateById(versionRecord);
        if (!result) {
            throw new BusinessException("版本记录更新失败");
        }

        KnowledgeDocument documentInDb = getById(knowledgeDocument.getDocId());
        documentInDb.setCurrentVersionId(versionRecord.getVersionId());
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
        document.setCurrentVersionId(versionRecord.getVersionId());
        // 3. 处理文档（转换/存储），获取转换后的文档URL
        String convertedDocUrl = processFile(fileName, file, document, fileUrl);
        // 4. 更新版本记录的转换后URL
        versionRecord = knowledgeDocumentVersionService.getById(versionRecord.getVersionId());
        versionRecord.setConvertedDocUrl(convertedDocUrl);
        boolean result = knowledgeDocumentVersionService.updateById(versionRecord);
        BusinessExceptionUtils.throwBusinessException(!result, "版本记录更新失败");
        result = updateById(document);
        BusinessExceptionUtils.throwBusinessException(!result, "文档当前版本更新失败");
        log.info("文档 {} 新版本 {} 上传完成，旧版本数据保留中，待新版本向量化完成后自动清理", docId, version);
        return document;
    }

    @Override
    public boolean removeDocumentWithSegments(Long docId) {
//        // 按 metadata 中的 docId 删除该文档所有向量
//        deleteVectorsByDocId(docId);
//
//        // 物理删除该文档下的所有分段
//        knowledgeSegmentMapper.physicalDeleteByDocumentId(docId);
//
//        // 删除该文档对应的 DATA_QUERY 动态物理表
//        dropDataQueryTableIfExists(docId);
//
//        // 物理删除该文档的所有版本记录
//        knowledgeDocumentVersionMapper.physicalDeleteByDocId(docId);

        // 物理删除文档本身
//        return baseMapper.physicalDeleteByDocId(docId) > 0;
        return true;
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
                docId, version, versionRecord.getVersionId());
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
