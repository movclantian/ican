package com.ican.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提示词模板配置类
 * 集中管理所有的提示词模板，便于维护和优化
 * 
 * @author ICan
 * @since 2024-10-08
 */
@Configuration
public class PromptTemplateConfig {
    
    /**
     * RAG 问答提示词模板
     */
    @Bean("ragQAPromptTemplate")
    public String ragQAPromptTemplate() {
        return """
            请基于提供的参考资料回答以下问题。
            
            问题: {question}
            
            回答要求：
            1. 准确性：回答必须基于参考资料，不要编造信息
            2. 完整性：回答要全面，覆盖问题的各个方面
            3. 清晰性：用简洁明了的语言表达
            4. 引用性：如果可能，标注信息来源
            5. 诚实性：如果参考资料中没有相关信息，请如实说明
            
            格式要求：
            - 如果参考资料充分，给出详细回答
            - 如果参考资料不足，说明"根据现有资料，我只能提供部分信息..."
            - 如果参考资料完全无关，说明"抱歉，提供的资料中没有找到相关信息"
            
            请回答：
            """;
    }
    
    /**
     * 文档问答提示词模板
     */
    @Bean("documentQAPromptTemplate")
    public String documentQAPromptTemplate() {
        return """
            请基于提供的文档内容回答以下问题。
            
            问题: {question}
            
            回答要求：
            1. 准确性：回答必须基于文档内容，不要编造信息
            2. 完整性：回答要全面，覆盖问题的各个方面
            3. 清晰性：用简洁明了的语言表达
            4. 引用性：如果可能，标注信息来源
            5. 诚实性：如果文档中没有相关信息，请如实说明
            
            格式要求：
            - 如果文档内容充分，给出详细回答
            - 如果文档内容不足，说明"根据现有文档，我只能提供部分信息..."
            - 如果文档内容完全无关，说明"抱歉，文档中没有找到相关信息"
            
            请回答：
            """;
    }
    
    /**
     * 论文总结提示词模板
     */
    @Bean("paperSummaryPromptTemplate")
    public String paperSummaryPromptTemplate() {
        return """
            请对以下论文进行结构化总结，生成符合学术规范的摘要。
            
            要求以 JSON 格式输出，包含以下字段：
            {
              "title": "论文标题",
              "researchBackground": "研究背景（3-5句话，说明研究领域和问题背景）",
              "methodology": "研究方法（3-5句话，说明使用的主要方法和技术）",
              "keyFindings": "主要发现（5-8句话，列出核心研究结果）",
              "innovations": "创新点（3-5条，每条简洁明确）",
              "conclusions": "结论（3-5句话，总结研究意义和未来方向）",
              "keywords": ["关键词1", "关键词2", "关键词3", ...]
            }
            
            注意：
            1. 内容必须来自论文原文，不要编造
            2. 如果某部分信息缺失，该字段设为 null 或空字符串
            3. 保持学术严谨性，使用专业术语
            4. 输出纯 JSON 格式，不要有其他文字
            """;
    }
    
    /**
     * 教学设计生成提示词模板
     */
    @Bean("teachingPlanPromptTemplate")
    public String teachingPlanPromptTemplate() {
        return """
            请基于以下信息，生成一份完整的教学设计方案。
            
            课题: {topic}
            学段: {grade}
            学科: {subject}
            
            要求以 JSON 格式输出，包含以下结构：
            {
              "title": "教学设计标题",
              "objectives": {
                "knowledge": ["知识目标1", "知识目标2", ...],
                "skills": ["能力目标1", "能力目标2", ...],
                "values": ["情感态度价值观目标1", "情感态度价值观目标2", ...]
              },
              "teachingProcess": [
                {
                  "stage": "阶段名称（如：导入、新授、练习、总结）",
                  "duration": "时长（分钟）",
                  "activities": ["活动1", "活动2", ...],
                  "methods": ["教学方法1", "教学方法2", ...],
                  "purpose": "设计意图"
                }
              ],
              "assessment": {
                "formative": ["形成性评价方法1", "形成性评价方法2", ...],
                "summative": ["终结性评价方法1", "终结性评价方法2", ...]
              },
              "homework": ["作业1", "作业2", ...],
              "resources": ["教学资源1", "教学资源2", ...]
            }
            
            注意：
            1. 符合{grade}学段和{subject}学科的教学特点
            2. 教学目标要具体、可测量
            3. 教学过程要有逻辑性和完整性
            4. 评价方式要多元化
            5. 输出纯 JSON 格式，不要有其他文字
            
            如果提供了参考文档，请结合文档内容设计教学方案。
            """;
    }
    
    /**
     * 会话标题生成提示词模板
     */
    @Bean("sessionTitlePromptTemplate")
    public String sessionTitlePromptTemplate() {
        return """
            请根据以下用户的第一条消息，提取关键内容生成一个简洁的会话标题。
            
            用户消息: {message}
            
            要求：
            1. 标题不超过15个字
            2. 不要有任何标点符号
            3. 直接输出标题内容，不要有任何多余的文字
            4. 提取核心关键词，简洁明了
            
            示例：
            - 用户消息："帮我分析一下这篇论文的研究方法" → 论文研究方法分析
            - 用户消息："介绍一下人工智能的发展历史" → 人工智能发展历史
            - 用户消息："如何学习Spring Boot框架？" → Spring Boot学习
            
            请生成标题：
            """;
    }
}
