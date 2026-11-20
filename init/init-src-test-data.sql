-- 源端库（jm_cloud）测试数据
CREATE SCHEMA IF NOT EXISTS spic_base;
CREATE SCHEMA IF NOT EXISTS dws_dsjsj;

DROP TABLE IF EXISTS spic_base.proj_biz_dm_stg_m;
CREATE TABLE spic_base.proj_biz_dm_stg_m (
    id          BIGSERIAL    PRIMARY KEY,
    relevant_id VARCHAR(128),
    file_id     VARCHAR(128)
);

DROP TABLE IF EXISTS dws_dsjsj.con_file_upload;
CREATE TABLE dws_dsjsj.con_file_upload (
    id       VARCHAR(128) PRIMARY KEY,
    file_url TEXT,
    bucket   VARCHAR(255)
);

-- 文件信息：3000 条
INSERT INTO dws_dsjsj.con_file_upload (id, file_url, bucket)
SELECT '208672431162639' || LPAD(i::text, 4, '0'),
       'https://oss-cn-beijing.aliyuncs.com/spic-bucket/2026/11/' || LPAD(i::text, 4, '0') || '_附件.pdf',
       'spic-bucket'
FROM generate_series(1, 3000) i;

-- 关联关系：relevant_id=A 有 1500 条，relevant_id=B 有 800 条，共 2300 条
INSERT INTO spic_base.proj_biz_dm_stg_m (relevant_id, file_id)
SELECT '2086724333076062209', '208672431162639' || LPAD(i::text, 4, '0')
FROM generate_series(1, 1500) i;

INSERT INTO spic_base.proj_biz_dm_stg_m (relevant_id, file_id)
SELECT '2086724333076062208', '208672431162639' || LPAD(i::text, 4, '0')
FROM generate_series(1501, 2300) i;

-- 脏数据：file_id 在 con_file_upload 中不存在（LEFT JOIN 应保留该行，file_url 为空）
INSERT INTO spic_base.proj_biz_dm_stg_m (relevant_id, file_id)
VALUES ('2086724333076062209', '9999999999999999999');

-- 脏数据：file_id 为空
INSERT INTO spic_base.proj_biz_dm_stg_m (relevant_id, file_id)
VALUES ('2086724333076062209', NULL);
