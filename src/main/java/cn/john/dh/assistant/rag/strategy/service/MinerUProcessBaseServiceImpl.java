package cn.john.dh.assistant.rag.strategy.service;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.domain.enums.FileType;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.impl.FileStorageService;
import cn.john.dh.assistant.rag.strategy.FileProcessService;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 *
 * 基础文件处理服务
 * 利用MinerU进行文档转换成markdown
 *
 * @Author John
 * @Date 2026-07-31 18:14
 */
@Slf4j
@Service
public class MinerUProcessBaseServiceImpl implements FileProcessService {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    @Lazy
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    @Autowired
    private OllamaChatModel ollamaChatModel;

    @Value("${file.parse.api.url:http://localhost:8000}")
    private String fileParseApiUrl;

    @Value("${file.parse.api.connectTimeout:30000}")
    private int connectTimeout;

    @Value("${file.parse.api.responseTimeout:300000}")
    private int responseTimeout;

    private static final String CONVERTED_FILE_DIR = "converted/";


    @Override
    public String processDocument(KnowledgeDocument document, InputStream inputStream) {
        return processDocumentToMarkdownFromZip(document, inputStream);
    }

    /**
     * 处理文档转换为 ZIP
     * 1、把文档状态更新为转换中
     * 2、调用MinerU文档解析对PDF文件进行解析，包含markdown和图片，获取 ZIP 格式响应
     * 3、保存 ZIP 到本地临时目录，等待后续处理
     * 4、解压 ZIP 文件，获取 md 和图片文件
     * 5、上传解压后的 md 和图片到 MinIO，并处理 md 内容
     * 6、更新文档状态为已转换
     *
     * @param document
     * @param inputStream
     * @return
     */
    private String processDocumentToMarkdownFromZip(KnowledgeDocument document, InputStream inputStream) {
        log.info("开始处理文档转换为 ZIP，documentId: {}", document.getDocTitle());
        // 更新状态为转换中
        knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTING);
        // 压缩包路径
        String zipFilePath = null;
        // 解压目录
        String extractDir = null;
        try {
            // 生成一串数字，避免文件名的中文乱码
            String docTitle = document.getDocTitle() + document.getDocTitle().hashCode();
            // 1. 调用文档解析获取 ZIP 格式响应
            byte[] zipBytes = parseDocumentToZip(docTitle, inputStream);
            // 2. 保存 ZIP 到本地临时目录
            String tempDir = System.getProperty("java.io.tmpdir");
            String uniqueId = UUID.randomUUID().toString();
            zipFilePath = tempDir + File.separator + uniqueId + ".zip";
            extractDir = tempDir + File.separator + uniqueId + "_extracted";
            Files.write(Paths.get(zipFilePath), zipBytes);
            log.info("ZIP 文件已保存到本地: {}", zipFilePath);
            // 3. 解压 ZIP 文件
            extractZip(zipFilePath, extractDir);
            log.info("ZIP 文件已解压到: {}", extractDir);
            // 4. 上传解压后的 md 和图片到 MinIO，并处理 md 内容
            String mdMinioUrl = processExtractedFiles(document, extractDir);
            // 5. 更新文档状态为已转换
            knowledgeDocumentService.advanceDocumentAndVersionStatus(document.getDocId(), document.getCurrentVersionId(), DocumentStatus.CONVERTED);
            log.info("文档 ZIP 转换完成，documentId: {}, mdUrl: {}", document.getDocTitle(), mdMinioUrl);
            return mdMinioUrl;
        } catch (Exception e) {
            log.error("文档 ZIP 转换失败，documentId: {}", document.getDocTitle(), e);
            // 转换失败，状态回滚为 UPLOADED
            document.setStatus(DocumentStatus.UPLOADED);
            boolean result = knowledgeDocumentService.updateById(document);
            Assert.isTrue(result, "文件UPLOADED状态更新失败");
            throw new RuntimeException("文档 ZIP 转换失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
            // 异步清理临时文件
            cleanupTempFilesAsync(zipFilePath, extractDir);
        }
    }

    /**
     * 处理解压后的文件，上传到 MinIO 并返回 Markdown 文件的 URL
     *
     * @param document
     * @param extractDir
     * @return
     * @throws Exception
     */
    private String processExtractedFiles(KnowledgeDocument document, String extractDir) throws Exception {
        // 找到对应的目录
        Path extractPath = Paths.get(extractDir);
        // 查找所有的 md 文件和图片文件
        Path mdFile = null;
        java.util.List<Path> imageFiles = new java.util.ArrayList<>();
        try (Stream<Path> paths = Files.walk(extractPath)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) {
                    String fileName = path.getFileName().toString().toLowerCase();
                    if (fileName.endsWith(".md")) {
                        mdFile = path;
                    } else if (fileName.endsWith(".png") || fileName.endsWith(".jpg") ||
                            fileName.endsWith(".jpeg") || fileName.endsWith(".gif") ||
                            fileName.endsWith(".webp") || fileName.endsWith(".bmp")) {
                        imageFiles.add(path);
                    }
                }
            }
        }
        //如果没有找到markdown文件，提示出错
        if (mdFile == null) {
            throw new RuntimeException("解压后的文件夹中未找到 Markdown 文件");
        }
        log.info("找到 Markdown 文件: {}, 图片文件数量: {}", mdFile, imageFiles.size());
        // 创建一个映射，存储图片上传地址
        Map<String, String> imageMap = new HashMap<>();
        //指定MinIO对象存储路径
        String baseObjectName = CONVERTED_FILE_DIR + document.getDocTitle() + "/";
        for (Path imagePath : imageFiles) {
            String imageName = imagePath.getFileName().toString();
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String contentType = getImageContentType(imageName);
            String objectName = baseObjectName + "images/" + imageName;
            String imageUrl = fileStorageService.uploadFile(objectName, imageBytes, contentType);
            imageMap.put(imageName, imageUrl);
            log.info("图片已上传到 MinIO: {} -> {}", imageName, imageUrl);
        }
        // 读取 md 文件内容
        String mdContent = Files.readString(mdFile, StandardCharsets.UTF_8);
        // 替换 md 中的图片地址为 MinIO 地址，并生成图片描述
        String processedMdContent = processMarkdownImages(mdContent, imageMap);
        // 上传处理后的 md 文件到 MinIO
        String mdObjectName = baseObjectName + mdFile.getFileName().toString();
        String mdUrl = fileStorageService.uploadFile(mdObjectName, processedMdContent.getBytes(StandardCharsets.UTF_8)
                , ContentType.TEXT_MARKDOWN.getMimeType() + ";charset=UTF-8");
        log.info("Markdown 文件已上传到 MinIO: {}", mdUrl);
        return mdUrl;

    }

    /**
     * 根据markdown内容和图片映射，处理图片标签为 MinIO 地址，并生成图片描述
     * 根据图片描述替换原有的图片标签
     *
     * @param mdContent
     * @param imageMap
     * @return
     */
    private String processMarkdownImages(String mdContent, Map<String, String> imageMap) {
        // 匹配图片标签的正则表达式: ![alt](path)
        Pattern pattern = Pattern.compile("!\\[(.*?)\\]\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(mdContent);
        StringBuffer result = new StringBuffer();
        // 遍历所有匹配的图片标签
        while (matcher.find()) {
            String altText = matcher.group(1);
            String imagePath = matcher.group(2);
            // 提取图片文件名
            String imageName = Paths.get(imagePath).getFileName().toString();
            // 获取 MinIO 上的图片 URL
            String minioUrl = imageMap.get(imageName);
            if (minioUrl == null) {
                // 如果找不到对应的 MinIO URL，保持原样
                log.warn("未找到图片 {} 对应的 MinIO URL", imageName);
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            // 生成图片描述（mock 实现）
            String imageDescription = generateImageDescription(minioUrl);

            // 构建新的图片标签: ![描述](minio_url)
            String newImageTag = "![" + imageDescription + "](" + minioUrl + ")";
            matcher.appendReplacement(result, Matcher.quoteReplacement(newImageTag));

            log.info("图片标签已处理: {} -> {}", imagePath, minioUrl);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 生成图片描述（本地 Ollama qwen3-vl:4b）
     * <p>
     * Ollama 运行在 Docker 容器中，可能无法直接访问 MinIO 的内网 URL，
     * 因此先把图片下载为字节数组，再通过 Spring AI Media 以 base64 方式传入模型。
     *
     * @param minioUrl
     * @return
     */
    protected String generateImageDescription(String minioUrl) {
        byte[] imageBytes = downloadImage(minioUrl);
        MimeType mimeType = MimeTypeUtils.parseMimeType(getImageContentType(minioUrl));

        UserMessage userMessage = UserMessage.builder()
                .text("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明，不要增加任何特殊符号，特别是换行符")
                .media(new Media(mimeType, new ByteArrayResource(imageBytes)))
                .build();

        return ChatClient.builder(ollamaChatModel)
                .build()
                .prompt()
                .messages(userMessage)
                .call()
                .content();
    }

    /**
     * 生成图片描述（本地 Ollama qwen3-vl:4b）
     * <p>
     * Ollama 运行在 Docker 容器中，可能无法直接访问 MinIO 的内网 URL，
     * 因此先把图片下载为字节数组，再通过 Spring AI Media 以 base64 方式传入模型。
     *
     * @param minioUrl
     * @return
     */
    protected String generateImageDescriptionToBaiLian(String minioUrl) {
        byte[] imageBytes = downloadImage(minioUrl);
        MimeType mimeType = MimeTypeUtils.parseMimeType(getImageContentType(minioUrl));

        UserMessage userMessage = UserMessage.builder()
                .text("请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明，不要增加任何特殊符号，特别是换行符")
                .media(new Media(mimeType, new ByteArrayResource(imageBytes)))
                .build();

        return ChatClient.builder(ollamaChatModel)
                .build()
                .prompt()
                .messages(userMessage)
                .call()
                .content();
    }

    /**
     * 从 URL 下载图片字节数组
     *
     * @param imageUrl
     * @return
     */
    private byte[] downloadImage(String imageUrl) {
        try (InputStream in = URI.create(imageUrl).toURL().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("下载图片失败: " + imageUrl, e);
        }
    }

    /**
     * 调用MinerU文档解析接口，将文档转换为 ZIP 格式，包括markdown和图片
     *
     * @param fileName
     * @param inputStream
     * @return
     */
    private byte[] parseDocumentToZip(String fileName, InputStream inputStream) {
        //请求地址
        String url = fileParseApiUrl + "/file_parse";
        // 配置请求超时
        RequestConfig requestConfig = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout)).setResponseTimeout(Timeout.ofMilliseconds(responseTimeout)).build();
        //请求minerU进行文档转换为ZIP，zip包括images和markdown文件
        try (CloseableHttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build()) {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Accept", "application/json");
            // 构建 multipart 请求体，启用 ZIP 格式和返回图片
            HttpEntity multipartEntity = MultipartEntityBuilder.create()
                    .addBinaryBody("files", inputStream, org.apache.hc.core5.http.ContentType.APPLICATION_OCTET_STREAM, fileName)
                    .addTextBody("backend", "pipeline").addTextBody("response_format_zip", "true")
                    .addTextBody("return_images", "true").addTextBody("return_model_output", "false")
                    .addTextBody("return_middle_json", "false").build();
            httpPost.setEntity(multipartEntity);
            log.info("开始调用文件解析接口（ZIP 模式）: {}", url);
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getCode();
                log.info("文件解析接口响应状态码: {}", statusCode);
                HttpEntity responseEntity = response.getEntity();
                if (statusCode == 200 && responseEntity != null) {
                    // 读取响应体为字节数组（ZIP 文件）
                    byte[] zipBytes = EntityUtils.toByteArray(responseEntity);
                    log.info("文件解析接口调用成功，ZIP 文件大小: {} bytes", zipBytes.length);
                    return zipBytes;
                } else {
                    String responseBody = responseEntity != null ? EntityUtils.toString(responseEntity, "UTF-8") : "";
                    log.error("文件解析接口调用失败，状态码: {}, 响应: {}", statusCode, responseBody);
                    throw new RuntimeException("文件解析接口调用失败: HTTP " + statusCode + ", " + responseBody);
                }
            }
        } catch (Exception e) {
            log.error("调用文件解析接口异常", e);
            throw new RuntimeException("调用文件解析接口失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(inputStream);
        }
    }

    /**
     * 安静关闭输入流，忽略异常
     *
     * @param inputStream 输入流
     */
    protected void closeQuietly(InputStream inputStream) {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 解压 ZIP 文件到指定目录
     */
    private void extractZip(String zipFilePath, String extractDir) throws IOException {
        Path extractPath = Paths.get(extractDir);
        Files.createDirectories(extractPath);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = extractPath.resolve(entry.getName());

                // 安全检查：防止 ZIP 路径遍历攻击
                if (!entryPath.normalize().startsWith(extractPath.normalize())) {
                    log.warn("跳过不安全的 ZIP 条目: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 获取图片的 Content-Type
     */
    private String getImageContentType(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".png")) return "image/png";
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) return "image/jpeg";
        if (lowerName.endsWith(".gif")) return "image/gif";
        if (lowerName.endsWith(".webp")) return "image/webp";
        if (lowerName.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }

    /**
     * 异步清理临时文件
     */
    private void cleanupTempFilesAsync(String zipFilePath, String extractDir) {
        if (zipFilePath == null && extractDir == null) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                // 删除 ZIP 文件
                if (zipFilePath != null) {
                    Files.deleteIfExists(Paths.get(zipFilePath));
                    log.info("临时 ZIP 文件已删除: {}", zipFilePath);
                }
                // 删除解压目录
                if (extractDir != null) {
                    deleteDirectory(Paths.get(extractDir));
                    log.info("临时解压目录已删除: {}", extractDir);
                }
            } catch (Exception e) {
                log.warn("清理临时文件失败", e);
            }
        });
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted((a, b) -> -a.compareTo(b)) // 反向排序，先删除子文件/目录
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("删除文件失败: {}", path, e);
                        }
                    });
        }
    }


    /**
     * 是否支持该文件类型
     *
     * @param fileType
     * @param knowledgeBaseType
     * @return
     */
    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        return fileType == FileType.MARKDOWN;
    }
}
