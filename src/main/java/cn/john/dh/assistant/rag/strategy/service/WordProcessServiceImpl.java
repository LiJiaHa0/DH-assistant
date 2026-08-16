package cn.john.dh.assistant.rag.strategy.service;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.domain.enums.FileType;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import cn.john.dh.assistant.rag.service.impl.FileStorageService;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.utils.BusinessExceptionUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Word 文档处理服务实现类
 * <p>
 * Word（doc/docx）不能直接交给 MinerU（仅支持 PDF），也不能按文本读取（docx 本质是 ZIP 二进制，
 * 直接 new String(bytes, UTF-8) 会得到乱码）。
 * 该类的职责：
 * 1. 使用 Apache POI 解析 Word 文档（docx 走 XWPF，doc 走 HWPF）
 * 2. 按段落样式识别标题层级（Heading1-6 / 大纲级别），转换为 Markdown 标题，保证后续"按标题切分"可用
 * 3. 表格转换为 Markdown 表格
 * 4. 将转换后的 Markdown 上传到 MinIO，并更新文档状态为 CONVERTED
 *
 * @Author John
 * @Date 2026-08-08
 */
@Service
@Slf4j
public class WordProcessServiceImpl extends MinerUProcessBaseServiceImpl {

    private static final String CONVERTED_FILE_DIR = "converted/";

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    @Lazy
    private KnowledgeDocumentService knowledgeDocumentService;

    @Override
    public String processDocument(KnowledgeDocument document, InputStream inputStream) {
        log.info("开始处理 Word 文档，documentId: {}", document.getDocTitle());
        // 更新状态为转换中
        knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTING);
        try {
            // 1. 解析 Word 为 Markdown（按原始文件名后缀区分 docx/doc 格式）
            String markdown = parseToMarkdown(document.getDocTitle(), new BufferedInputStream(inputStream));
            BusinessExceptionUtils.throwBusinessException(markdown.isBlank(), "Word 文档内容为空，无法转换");
            // 2. 上传转换后的 Markdown 到 MinIO（对象名带 docId 前缀，避免多用户同名文档互相覆盖）
            String docTitle = document.getDocTitle();
            String baseName = docTitle.contains(".") ? docTitle.substring(0, docTitle.lastIndexOf(".")) : docTitle;
            String convertedObjectName = CONVERTED_FILE_DIR + document.getDocId() + "/" + baseName + ".md";
            String convertedUrl = fileStorageService.uploadFile(
                    convertedObjectName,
                    markdown.getBytes(StandardCharsets.UTF_8),
                    ContentType.TEXT_MARKDOWN.getMimeType() + ";charset=UTF-8");
            // 3. 更新文档状态为已转换
            knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTED);
            log.info("Word 文档处理完成，documentId: {}, convertedUrl: {}", document.getDocTitle(), convertedUrl);
            return convertedUrl;
        } catch (Exception e) {
            log.error("Word 文档处理失败，documentId: {}", document.getDocTitle(), e);
            // 处理失败，状态回滚为 UPLOADED（文档与版本状态同步回滚，避免版本卡在 CONVERTING）
            document.setStatus(DocumentStatus.UPLOADED);
            boolean result = knowledgeDocumentService.updateById(document);
            BusinessExceptionUtils.throwBusinessException(!result, "文件UPLOADED状态更新失败");
            rollbackVersionStatus(document);
            throw new RuntimeException("Word 文档处理失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
        }
    }

    /**
     * 将 Word 文档解析为 Markdown
     *
     * @param fileName    原始文件名（用于区分 docx/doc 格式）
     * @param inputStream 文档输入流
     * @return Markdown 文本
     */
    private String parseToMarkdown(String fileName, InputStream inputStream) throws Exception {
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        if (lowerName.endsWith(".doc") && !lowerName.endsWith(".docx")) {
            // 旧版 .doc 二进制格式，使用 HWPF 解析
            return parseDocToMarkdown(inputStream);
        }
        // 默认按 docx（OOXML）解析
        return parseDocxToMarkdown(inputStream);
    }

    /**
     * 解析 docx（OOXML）为 Markdown
     * 按文档体顺序遍历段落与表格，保留原始结构
     */
    private String parseDocxToMarkdown(InputStream inputStream) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(inputStream)) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(sb, paragraph.getStyle(), paragraph.getStyleID(), paragraph.getNumIlvl() != null, paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    appendTable(sb, table);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 解析旧版 .doc（HWPF）为 Markdown
     * 仅处理段落级文本，标题通过样式名/大纲级别识别
     */
    private String parseDocToMarkdown(InputStream inputStream) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (HWPFDocument doc = new HWPFDocument(inputStream)) {
            Range range = doc.getRange();
            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph paragraph = range.getParagraph(i);
                // 过滤表格单元格内部的重复段落（HWPF 中单元格内容也会以段落形式出现）
                if (paragraph.isInTable()) {
                    continue;
                }
                String text = paragraph.text();
                if (text == null) {
                    continue;
                }
                // 清理段落末尾的控制字符（\r、\u0007 等）
                text = text.replaceAll("[\\r\\u0007\\u0014\\u0013\\u0015]+$", "").trim();
                if (text.isEmpty()) {
                    continue;
                }
                int headingLevel = resolveDocHeadingLevel(doc, paragraph);
                if (headingLevel > 0) {
                    sb.append("#".repeat(headingLevel)).append(" ").append(text).append("\n\n");
                } else {
                    sb.append(text).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 识别 .doc 段落的标题级别：优先样式名（heading1/标题1），回退大纲级别
     *
     * @return 标题级别 1-6，非标题返回 0
     */
    private int resolveDocHeadingLevel(HWPFDocument doc, Paragraph paragraph) {
        try {
            String styleName = doc.getStyleSheet().getStyleDescription(paragraph.getStyleIndex()).getName();
            if (styleName != null) {
                int level = headingLevelFromStyleName(styleName);
                if (level > 0) {
                    return level;
                }
            }
        } catch (Exception ignored) {
            // 样式解析失败时回退大纲级别
        }
        // 大纲级别 0-8 对应正文/标题1-9，9 表示正文
        int outlineLvl = paragraph.getLvl();
        return outlineLvl >= 0 && outlineLvl <= 5 ? outlineLvl + 1 : 0;
    }

    /**
     * 追加 docx 段落到 Markdown
     *
     * @param sb        Markdown 构建器
     * @param style     样式描述（如 "Heading1"、"标题 1"）
     * @param styleId   样式ID（如 "1"、"a3"）
     * @param isNumbered 是否为列表项
     * @param text      段落文本
     */
    private void appendParagraph(StringBuilder sb, String style, String styleId, boolean isNumbered, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        text = text.trim();
        // 标题识别：优先样式名，其次样式ID（中文模板中样式ID常为纯数字标题级别）
        int headingLevel = headingLevelFromStyleName(style);
        if (headingLevel == 0) {
            headingLevel = headingLevelFromStyleName(styleId);
        }
        if (headingLevel > 0) {
            sb.append("#".repeat(headingLevel)).append(" ").append(text).append("\n\n");
        } else if (isNumbered) {
            // 列表项统一转为无序列表
            sb.append("- ").append(text).append("\n");
        } else {
            sb.append(text).append("\n\n");
        }
    }

    /**
     * 追加 docx 表格到 Markdown
     */
    private void appendTable(StringBuilder sb, XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            sb.append("|");
            for (XWPFTableCell cell : row.getTableCells()) {
                // 单元格内多段落合并为空格分隔，避免破坏表格结构
                String cellText = cell.getText().replaceAll("[\\r\\n]+", " ").trim();
                sb.append(" ").append(cellText).append(" |");
            }
            sb.append("\n");
            // 首行后追加表头分隔行
            if (i == 0) {
                sb.append("|");
                for (int c = 0; c < row.getTableCells().size(); c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    /**
     * 从样式名解析标题级别，兼容英文（Heading1/heading 2/Title）与中文（标题 1）
     *
     * @param styleName 样式名或样式ID
     * @return 标题级别 1-6，非标题返回 0
     */
    private int headingLevelFromStyleName(String styleName) {
        if (styleName == null || styleName.isBlank()) {
            return 0;
        }
        String lower = styleName.toLowerCase().trim();
        // 英文样式：heading1、heading 2、title（视为一级标题）
        if (lower.equals("title")) {
            return 1;
        }
        if (lower.startsWith("heading") || lower.startsWith("标题")) {
            String rest = lower.replace("heading", "").replace("标题", "").trim();
            if (rest.isEmpty()) {
                return 0;
            }
            char first = rest.charAt(0);
            if (first >= '1' && first <= '6') {
                return first - '0';
            }
        }
        // 样式ID为纯数字 1-6 的中文模板场景
        if (lower.matches("[1-6]")) {
            return Integer.parseInt(lower);
        }
        return 0;
    }

    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        return fileType == FileType.DOC;
    }
}
