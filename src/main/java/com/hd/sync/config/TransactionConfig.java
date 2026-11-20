package com.hd.sync.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 事务管理器。
 *
 * <p>绑定在 dynamic-datasource 的路由数据源上：进入 {@code @DS("szsjzzkk")} 方法时，
 * 路由上下文已切换到目标库，事务拿到的连接与 MyBatis 写入用的是同一个库，
 * 因此业务落地表与同步记录表能落在同一个事务里。</p>
 */
@Configuration
public class TransactionConfig {

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
