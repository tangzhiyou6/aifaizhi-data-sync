package com.hd.sync.common;

import org.apache.commons.lang3.StringUtils;

/**
 * 文件名解析工具：从 OSS 的 file_url 中截取文件名。
 */
public final class FileNameUtils {

    private FileNameUtils() {
    }

    /**
     * 从 file_url 解析文件名，解析不到时回退为 fileId。
     */
    public static String resolveFileName(String fileUrl, String fileId) {
        if (StringUtils.isBlank(fileUrl)) {
            return fileId;
        }
        String path = fileUrl.trim();
        int queryIndex = path.indexOf('?');
        if (queryIndex > 0) {
            path = path.substring(0, queryIndex);
        }
        int slashIndex = path.lastIndexOf('/');
        String fileName = slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
        return StringUtils.isBlank(fileName) ? fileId : fileName;
    }
}
