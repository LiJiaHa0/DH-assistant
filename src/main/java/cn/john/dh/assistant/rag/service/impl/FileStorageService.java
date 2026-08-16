package cn.john.dh.assistant.rag.service.impl;

import io.minio.*;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 文件上传服务
 * @Author John
 * @Date 2026-07-31 15:38
 */
@Service
@Slf4j
public class FileStorageService {

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    // 确保 bucket 存在
    private void createBucketIfNotExists(boolean publicRead) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());

            // 设置 bucket 策略为公共读
            if (publicRead) {
                String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
                minioClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder()
                                .bucket(bucketName)
                                .config(policy)
                                .build()
                );
            }
        }
    }

    // 上传文件
    public String uploadFile(MultipartFile file, String objectName) throws Exception {
        // 私有 bucket：用户文档不应公开可读，外部访问一律走预签名 URL（toPublicUrl）
        createBucketIfNotExists(false);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());
        return String.format("%s/%s/%s", endpoint, bucketName, objectName);

    }

    /**
     * 上传文件
     */
    public String uploadFile(String objectName, byte[] content, String contentType) throws Exception {
        createBucketIfNotExists(false);
        try (InputStream stream = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(stream, content.length, -1)
                            .contentType(contentType)
                            .build()
            );

            return String.format("%s/%s/%s", endpoint, bucketName, objectName);
        }
    }

    /**
     * 将私有 URL 转换为带签名的临时公开 URL（默认 7 天有效）。
     * 用于前端展示图片、参考链接等场景；转换失败时原样返回 URL。
     *
     * @param url MinIO 私有 URL（{endpoint}/{bucket}/{objectName}）
     * @return 预签名公开 URL
     */
    public String toPublicUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            String objectName = extractObjectNameFromUrl(url);
            if (objectName == null) {
                return url;
            }
            return getPresignedUrl(objectName);
        } catch (Exception e) {
            log.warn("生成预签名URL失败，返回原URL: {}", url);
            return url;
        }
    }

    // 下载文件（返回 InputStream）
    public InputStream downloadFile(String objectName) throws Exception {
        GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build());
        return response;
    }

    // 删除文件
    public void deleteFile(String objectName) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .build());
    }

    // 生成临时下载链接（带签名，有效期 7 天）
    public String getPresignedUrl(String objectName) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectName)
                        .expiry(7, TimeUnit.DAYS)
                        .build());
    }

    /**
     * 从MinIO URL中提取对象名称
     */
    public String extractObjectNameFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        // URL格式: http://endpoint/bucketName/objectName
        int lastSlashIndex = url.lastIndexOf(bucketName) + bucketName.length();
        if (lastSlashIndex == -1 || lastSlashIndex == url.length() - 1) {
            return null;
        }
        return url.substring(lastSlashIndex + 1);
    }
}
