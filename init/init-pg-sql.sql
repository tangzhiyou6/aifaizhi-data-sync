-- =====================================================================
-- 目标库：szsjzzkk（currentSchema=xcapp，用户 xcapp）
-- 用途：SPIC 附件元数据同步的落地表 + 同步记录表（幂等控制）
-- 执行：psql -h 10.227.3.245 -U xcapp -d szsjzzkk -f init/init-pg-sql.sql
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS ods_dsjsj;
COMMENT ON SCHEMA ods_dsjsj IS 'SPIC 附件同步落地 schema';

-- ---------------------------------------------------------------------
-- 1. 业务落地表：源端 proj_biz_dm_stg_m + con_file_upload 的关联结果
--    以 file_id 为唯一键，同步走 ON CONFLICT DO UPDATE，重复同步幂等
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ods_dsjsj.ods_dsjsj_file_info (
    id            BIGSERIAL     PRIMARY KEY,
    relevant_id   VARCHAR(128),                       -- proj_biz_dm_stg_m.relevant_id
    file_id       VARCHAR(128)  NOT NULL,              -- proj_biz_dm_stg_m.file_id = con_file_upload.id
    file_url      TEXT,                                -- con_file_upload.file_url
    bucket        VARCHAR(255),                       -- con_file_upload.bucket
    file_name     VARCHAR(1024),                      -- 从 file_url 解析出的文件名
    sync_status   SMALLINT      NOT NULL DEFAULT 0,    -- 同步状态：1=成功，2=失败
    retry_count   INTEGER       DEFAULT 0,            -- 累计重试次数
    fail_reason   TEXT,                                -- 失败原因，成功时为空
    source_system VARCHAR(64),                        -- 来源系统
    biz_type      VARCHAR(128),                       -- 业务类型
    data_time     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ods_dsjsj_file_info_file_id
    ON ods_dsjsj.ods_dsjsj_file_info (file_id);
CREATE INDEX IF NOT EXISTS idx_ods_dsjsj_file_info_relevant_id
    ON ods_dsjsj.ods_dsjsj_file_info (relevant_id);
CREATE INDEX IF NOT EXISTS idx_ods_dsjsj_file_info_sync_status
    ON ods_dsjsj.ods_dsjsj_file_info (sync_status);

COMMENT ON TABLE ods_dsjsj.ods_dsjsj_file_info IS 'SPIC 附件元数据落地表（file_id 唯一，幂等 upsert）';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.id            IS '数据ID，自增主键';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.relevant_id   IS 'proj_biz_dm_stg_m.relevant_id';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.file_id       IS 'proj_biz_dm_stg_m.file_id / con_file_upload.id';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.file_url      IS 'con_file_upload.file_url';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.bucket        IS 'con_file_upload.bucket';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.file_name     IS '文件名称';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.sync_status   IS '同步状态：1=成功，2=失败';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.retry_count   IS '累计重试次数';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.fail_reason   IS '失败原因，成功时为空';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.source_system IS '来源系统';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.biz_type      IS '业务类型';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.data_time     IS '数据创建时间';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_info.update_time   IS '数据更新时间';

-- ---------------------------------------------------------------------
-- 2. 同步记录表：幂等控制
--    record_key 唯一：文件级记录取 file_id，接口页面级记录取 page:{relevantId}:{page}
--    sync_status = 1 已同步成功，下次任务跳过
--    sync_status = 2 同步失败，下次任务重新同步
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ods_dsjsj.ods_dsjsj_file_sync_record (
    id          BIGSERIAL     PRIMARY KEY,
    record_key  VARCHAR(255)  NOT NULL,               -- 幂等键
    record_type SMALLINT      NOT NULL DEFAULT 1,     -- 记录类型：1=文件，2=接口页面
    relevant_id VARCHAR(128),                          -- 查询条件 relevant_id
    file_id     VARCHAR(128),                          -- 文件ID（页面级记录为空）
    file_url    TEXT,                                  -- con_file_upload.file_url
    bucket      VARCHAR(255),                         -- con_file_upload.bucket
    sync_mode   VARCHAR(32),                          -- 同步方式：db / api
    sync_status SMALLINT      NOT NULL DEFAULT 0,     -- 同步状态：1=成功，2=失败
    retry_count INTEGER       DEFAULT 0,              -- 累计重试次数
    fail_reason TEXT,                                  -- 失败原因
    batch_no    INTEGER,                               -- 所属批次号
    task_id     VARCHAR(64),                           -- 任务号
    data_time   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ods_dsjsj_file_sync_record_key
    ON ods_dsjsj.ods_dsjsj_file_sync_record (record_key);
CREATE INDEX IF NOT EXISTS idx_ods_dsjsj_file_sync_record_file_id
    ON ods_dsjsj.ods_dsjsj_file_sync_record (file_id);
CREATE INDEX IF NOT EXISTS idx_ods_dsjsj_file_sync_record_relevant_id
    ON ods_dsjsj.ods_dsjsj_file_sync_record (relevant_id);
CREATE INDEX IF NOT EXISTS idx_ods_dsjsj_file_sync_record_status
    ON ods_dsjsj.ods_dsjsj_file_sync_record (sync_status);

COMMENT ON TABLE ods_dsjsj.ods_dsjsj_file_sync_record IS 'SPIC 附件同步记录表（幂等控制，失败记录下次重投）';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.id          IS '数据ID，自增主键';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.record_key  IS '幂等键：file_id 或 page:{relevantId}:{page}';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.record_type IS '记录类型：1=文件记录，2=接口页面记录';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.relevant_id IS '查询条件 relevant_id';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.file_id     IS '文件ID';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.file_url    IS 'con_file_upload.file_url';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.bucket      IS 'con_file_upload.bucket';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.sync_mode   IS '同步方式：db / api';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.sync_status IS '同步状态：1=成功，2=失败';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.retry_count IS '累计重试次数';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.fail_reason IS '失败原因';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.batch_no    IS '所属批次号';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.task_id     IS '任务号';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.data_time   IS '数据创建时间';
COMMENT ON COLUMN ods_dsjsj.ods_dsjsj_file_sync_record.update_time IS '数据更新时间';

-- ---------------------------------------------------------------------
-- 3. 授权（需要 DBA 执行；若 xcapp 已是 owner 可跳过）
-- ---------------------------------------------------------------------
-- ALTER SCHEMA ods_dsjsj OWNER TO xcapp;
-- GRANT USAGE ON SCHEMA ods_dsjsj TO xcapp;
-- GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA ods_dsjsj TO xcapp;
