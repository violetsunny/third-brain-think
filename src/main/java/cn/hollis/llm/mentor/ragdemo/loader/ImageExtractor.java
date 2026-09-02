package cn.hollis.llm.mentor.ragdemo.loader;

import java.io.File;
import java.util.List;

/**
 * 图片提取器接口
 * 从文档中提取图片及其位置信息
 */
public interface ImageExtractor {
    
    /**
     * 从文件中提取所有图片
     * 
     * @param file 文档文件
     * @return 图片占位符列表（按在文档中的出现顺序）
     */
    List<ImagePlaceholder> extractImages(File file);
    
    /**
     * 判断是否支持该文件类型
     * 
     * @param fileName 文件名
     * @return 是否支持
     */
    boolean supports(String fileName);
}
