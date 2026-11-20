package com.hd.sync;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SPIC 附件元数据同步服务。
 *
 * <p>源端：jm_cloud（spic_base.proj_biz_dm_stg_m + con_file_upload）<br>
 * 目标端：szsjzzkk（ods_dsjsj.ods_dsjsj_file_info + ods_dsjsj.ods_dsjsj_file_sync_record）</p>
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.hd.sync.mapper")
public class DataSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataSyncApplication.class, args);
    }
}
