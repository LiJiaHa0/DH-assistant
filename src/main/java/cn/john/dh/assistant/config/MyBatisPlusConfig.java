package cn.john.dh.assistant.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器配置
 *
 * <p>注册以下两个内部拦截器：
 * <ul>
 *     <li>{@link OptimisticLockerInnerInterceptor} —— 乐观锁拦截器，
 *     负责 Base{@code @}Version 注解标记的 {@code lockVersion} 字段的版本校验，
 *     自动填充 SQL 中的 {@code MP_OPTLOCK_VERSION_ORIGINAL} 参数。</li>
 *     <li>{@link PaginationInnerInterceptor} —— 分页拦截器，
 *     支持 Mapper 层的分页查询。</li>
 * </ul>
 *
 * @Author John
 * @Date 2026-08-04
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 拦截器链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁拦截器（必须注册，否则 @Version 注解会导致 SQL 中 MP_OPTLOCK_VERSION_ORIGINAL 参数找不到）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页拦截器
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
