package com.linblog.module.media.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 存储驱动接口（§7.5）：本地盘实现先行，S3 驱动留媒体轮次（§5.7 备注）。
 */
public interface StorageDriver {

    /** 保存二进制对象，返回实际存储键。 */
    String save(String key, byte[] data) throws IOException;

    /** 读取对象；不存在返回 null。 */
    byte[] load(String key) throws IOException;

    /** 对象是否存在。 */
    boolean exists(String key) throws IOException;

    /** 删除对象（清理回收站用）；不存在时静默成功。 */
    void remove(String key) throws IOException;

    /** 本地驱动：返回对象在磁盘上的路径（零拷贝流式输出用）。 */
    Path resolve(String key);
}
