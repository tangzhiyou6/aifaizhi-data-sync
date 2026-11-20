package com.hd.sync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

/**
 * MyBatis-Plus 配置。
 *
 * <p>多数据源由 dynamic-datasource 接管，SqlSessionFactory 始终绑定路由数据源，
 * 具体打到哪个库由 {@code @DS} 注解在运行期决定。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件（源端接口用 offset 分页时也走这里，保证 SQL 方言统一）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
