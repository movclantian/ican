package com.ican.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 论文总结 VO
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaperSummaryVO {
    
    /**
     * 论文标题
     */
    private String title;
    
    /**
     * 作者列表
     */
    private List<String> authors;
    
    /**
     * 发表年份
     */
    private Integer publicationYear;
    
    /**
     * 总结内容
     */
    private SummaryContent summary;
    
    /**
     * 关键词
     */
    private List<String> keywords;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryContent {
        /**
         * 研究背景
         */
        private String background;
        
        /**
         * 核心方法
         */
        private String methodology;
        
        /**
         * 实验结果
         */
        private String results;
        
        /**
         * 创新点列表
         */
        private List<String> innovations;
        
        /**
         * 局限性
         */
        private String limitations;
    }
}

