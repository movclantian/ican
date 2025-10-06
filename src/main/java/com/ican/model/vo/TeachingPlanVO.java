package com.ican.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 教学设计 VO
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeachingPlanVO {
    
    /**
     * 课题名称
     */
    private String title;
    
    /**
     * 学段
     */
    private String grade;
    
    /**
     * 学科
     */
    private String subject;
    
    /**
     * 课时
     */
    private String duration;
    
    /**
     * 教学目标
     */
    private TeachingObjectives objectives;
    
    /**
     * 教学重点
     */
    private List<String> keyPoints;
    
    /**
     * 教学难点
     */
    private List<String> difficulties;
    
    /**
     * 教学步骤
     */
    private List<TeachingStep> teachingSteps;
    
    /**
     * 评价方案
     */
    private EvaluationPlan evaluation;
    
    /**
     * 作业建议
     */
    private List<String> assignments;
    
    /**
     * 教学资源
     */
    private List<String> resources;
    
    /**
     * 教学目标
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeachingObjectives {
        /**
         * 知识目标
         */
        private List<String> knowledge;
        
        /**
         * 能力目标
         */
        private List<String> skills;
        
        /**
         * 情感态度价值观目标
         */
        private List<String> values;
    }
    
    /**
     * 教学步骤
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeachingStep {
        /**
         * 步骤序号
         */
        private Integer step;
        
        /**
         * 步骤名称
         */
        private String name;
        
        /**
         * 时长
         */
        private String duration;
        
        /**
         * 具体内容
         */
        private String content;
        
        /**
         * 教学活动
         */
        private List<String> activities;
    }
    
    /**
     * 评价方案
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvaluationPlan {
        /**
         * 评价方法
         */
        private List<String> methods;
        
        /**
         * 评价标准
         */
        private List<String> criteria;
    }
}

