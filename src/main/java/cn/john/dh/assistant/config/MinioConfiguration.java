package cn.john.dh.assistant.config;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Minio 配置类
 * @Author John
 * @Date 2026-07-31 15:41
 */
@Configuration
public class MinioConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(MinioConfiguration.class);

    /**
     * 本地 MinIO 地址，用于实际上传/下载操作（局域网直连，速度快）
     */
    @Value("${minio.url}")
    private String url;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    @Lazy
    public MinioClient minioClient() {
        try {
            return MinioClient.builder()
                    .endpoint(url)
                    .credentials(accessKey, secretKey)
                    .build();
        } catch (Exception e) {
            logger.warn("Failed to create MinIO client: {}. MinIO functionality will be unavailable.", e.getMessage());
            return null;
        }
    }

}
