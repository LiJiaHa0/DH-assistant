package cn.john.dh.assistant.rag.strategy.service;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.domain.enums.FileType;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.impl.FileStorageService;
import cn.john.dh.assistant.rag.strategy.FileProcessService;
import cn.john.dh.assistant.utils.BusinessExceptionUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档处理服务实现类
 * <p>
 * 与 MinerU 流程不同，此处的输入流本身就是一个 Markdown 文件，
 * 文件中的图片地址通常已经是公网可访问的 URL（如 MinerU 输出的 cdn 地址）。
 * 该类的职责：
 * 1. 直接读取 Markdown 内容
 * 2. 解析其中的图片标签 ![alt](url)
 * 3. 调用视觉大模型为每张图片生成描述，替换 alt 文本
 * 4. 将处理后的 Markdown 上传到 MinIO，并更新文档状态为 CONVERTED
 *
 * @Author John
 * @Date 2026-08-04 22:27
 */
@Service
@Slf4j
public class MarkdownProcessServiceImpl extends MinerUProcessBaseServiceImpl {

    private static final String CONVERTED_FILE_DIR = "converted/";

    /**
     * 匹配 Markdown 图片标签：![alt](url)
     */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[(.*?)\\]\\(([^)]+)\\)");

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;


    @Override
    public String processDocument(KnowledgeDocument document, InputStream inputStream) {
        log.info("开始处理 Markdown 文档图片描述生成，documentId: {}", document.getDocTitle());
        // 更新状态为转换中
        knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTING);
        try {
            // 1. 读取 Markdown 文件内容
            String mdContent = readInputStreamAsString(inputStream);
            //将md内容替换图片地址alt文本
            String processedMdContent = enrichImageDescriptions(mdContent);
            // 3. 上传处理后的 Markdown 到 MinIO（对象名带 docId 前缀，避免多用户同名文档互相覆盖）
            String docTitle = document.getDocTitle();
            String baseName = docTitle.contains(".") ? docTitle.substring(0, docTitle.lastIndexOf(".")) : docTitle;
            String convertedObjectName = CONVERTED_FILE_DIR + document.getDocId() + "/" + baseName + ".md";
            String convertedUrl = fileStorageService.uploadFile(
                    convertedObjectName,
                    processedMdContent.getBytes(StandardCharsets.UTF_8),
                    ContentType.TEXT_MARKDOWN.getMimeType());
            // 4. 更新文档状态为已转换
            knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTED);
            log.info("Markdown 文档图片描述生成完成，documentId: {}, convertedUrl: {}", document.getDocTitle(), convertedUrl);
            log.info("Markdown 文档处理完成，documentId: {}, convertedUrl: {}", document.getDocTitle(), convertedUrl);
            return convertedUrl;
        } catch (Exception e) {
            log.error("Markdown 文档处理失败，documentId: {}", document.getDocTitle(), e);
            // 处理失败，状态回滚为 UPLOADED（文档与版本状态同步回滚，避免版本卡在 CONVERTING）
            document.setStatus(DocumentStatus.UPLOADED);
            boolean result = knowledgeDocumentService.updateById(document);
            BusinessExceptionUtils.throwBusinessException(!result, "文件UPLOADED状态更新失败");
            rollbackVersionStatus(document);
            throw new RuntimeException("Markdown 文档处理失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
        }
    }

    /**
     * 为 Markdown 内容中的图片标签生成描述，替换原有的 alt 文本
     *
     * @param mdContent
     * @return
     */
    private String enrichImageDescriptions(String mdContent) {
        // 匹配图片标签的正则表达式: ![alt](url)
        Matcher matcher = IMAGE_PATTERN.matcher(mdContent);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String originAlt = matcher.group(1);
            String imageUrl = matcher.group(2);
            String description;
            try {
                // 调用基类提供的视觉模型生成描述
                description = generateImageDescription(imageUrl);
                if (description == null || description.isBlank()) {
                    log.warn("图片描述为空，保留原 alt 文本，url: {}", imageUrl);
                    description = originAlt;
                } else {
                    // 去除可能存在的换行，保证 Markdown 标签单行
                    description = description.replaceAll("[\\r\\n]+", " ").trim();
                }
                log.info("图片描述已生成: {} -> {}", imageUrl, description);
            } catch (Exception e) {
                log.warn("生成图片描述失败，保留原 alt 文本，url: {}", imageUrl, e);
                description = originAlt;
            }
            // 替换图片标签
            String newImageTag = "![" + description + "](" + imageUrl + ")";
            matcher.appendReplacement(result, Matcher.quoteReplacement(newImageTag));
        }
        // 替换所有匹配的图片标签
        matcher.appendTail(result);
        // 返回处理后的 Markdown 内容
        return result.toString();

    }

    /**
     * 读取输入流为字符串
     *
     * @param inputStream
     * @return
     * @throws IOException
     */
    private String readInputStreamAsString(InputStream inputStream) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        return fileType == FileType.MARKDOWN;
    }
}
