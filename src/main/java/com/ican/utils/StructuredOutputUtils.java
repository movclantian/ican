package com.ican.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import top.continew.starter.core.exception.BusinessException;

import java.util.List;
import java.util.Map;

/**
 * Spring AI 结构化输出工具类
 * 基于官方文档最佳实践实现
 * 
 * 参考文档：
 * - https://docs.spring.io/spring-ai/reference/api/prompt.html
 * - https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html
 * 
 * 核心特性：
 * - 使用 ChatClient.entity() 方法自动转换
 * - BeanOutputConverter 自动生成 JSON Schema
 * - 支持 ParameterizedTypeReference 处理泛型
 * - PromptTemplate.builder() 构建提示词
 * 
 * @author 席崇援
 */
@Slf4j
public class StructuredOutputUtils {

    /**
     * 使用结构化输出获取单个对象（推荐方式）
     * 基于官方文档最佳实践：使用 ChatClient.entity() 方法
     * 
     * @param chatClient     ChatClient 实例
     * @param userPrompt     用户提示词
     * @param outputClass    目标输出类
     * @param <T>            泛型类型
     * @return 解析后的对象
     */
    public static <T> T getStructuredOutput(
            ChatClient chatClient,
            String userPrompt,
            Class<T> outputClass) {
        
        try {
            log.debug("结构化输出请求: class={}, prompt length={}", 
                    outputClass.getSimpleName(), userPrompt.length());
            
            // 使用 ChatClient.entity() 方法自动处理转换
            T result = chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .entity(outputClass);
            
            if (result == null) {
                throw new BusinessException("结构化输出转换失败：返回 null");
            }
            
            log.info("结构化输出成功: class={}", outputClass.getSimpleName());
            return result;
            
        } catch (Exception e) {
            log.error("结构化输出失败: class={}", outputClass.getSimpleName(), e);
            throw new BusinessException("AI 响应解析失败: " + e.getMessage());
        }
    }

    /**
     * 使用结构化输出获取单个对象（带系统提示词）
     * 
     * @param chatClient     ChatClient 实例
     * @param systemPrompt   系统提示词
     * @param userPrompt     用户提示词
     * @param outputClass    目标输出类
     * @param <T>            泛型类型
     * @return 解析后的对象
     */
    public static <T> T getStructuredOutput(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt,
            Class<T> outputClass) {
        
        try {
            log.debug("结构化输出请求: class={}, system length={}, user length={}", 
                    outputClass.getSimpleName(), systemPrompt.length(), userPrompt.length());
            
            T result = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(outputClass);
            
            if (result == null) {
                throw new BusinessException("结构化输出转换失败：返回 null");
            }
            
            log.info("结构化输出成功: class={}", outputClass.getSimpleName());
            return result;
            
        } catch (Exception e) {
            log.error("结构化输出失败: class={}", outputClass.getSimpleName(), e);
            throw new BusinessException("AI 响应解析失败: " + e.getMessage());
        }
    }

    /**
     * 使用结构化输出获取单个对象（带参数模板）
     * 使用 PromptTemplate 处理占位符
     * 
     * @param chatClient     ChatClient 实例
     * @param userTemplate   用户提示词模板（包含 {key} 占位符）
     * @param variables      模板变量
     * @param outputClass    目标输出类
     * @param <T>            泛型类型
     * @return 解析后的对象
     */
    public static <T> T getStructuredOutputWithTemplate(
            ChatClient chatClient,
            String userTemplate,
            Map<String, Object> variables,
            Class<T> outputClass) {
        
        try {
            log.debug("结构化输出请求(模板): class={}, variables={}", 
                    outputClass.getSimpleName(), variables.keySet());
            
            // 使用 PromptTemplate.builder() 构建
            PromptTemplate promptTemplate = PromptTemplate.builder()
                    .template(userTemplate)
                    .variables(variables)
                    .build();
            
            String renderedPrompt = promptTemplate.render();
            
            T result = chatClient.prompt()
                    .user(renderedPrompt)
                    .call()
                    .entity(outputClass);
            
            if (result == null) {
                throw new BusinessException("结构化输出转换失败：返回 null");
            }
            
            log.info("结构化输出成功(模板): class={}", outputClass.getSimpleName());
            return result;
            
        } catch (Exception e) {
            log.error("结构化输出失败(模板): class={}", outputClass.getSimpleName(), e);
            throw new BusinessException("AI 响应解析失败: " + e.getMessage());
        }
    }

    /**
     * 使用结构化输出获取列表（推荐方式）
     * 使用 ParameterizedTypeReference 处理泛型
     * 
     * @param chatClient     ChatClient 实例
     * @param userPrompt     用户提示词
     * @param elementClass   列表元素类型
     * @param <T>            泛型类型
     * @return 解析后的列表
     */
    public static <T> List<T> getStructuredList(
            ChatClient chatClient,
            String userPrompt,
            Class<T> elementClass) {
        
        try {
            log.debug("结构化列表输出请求: elementClass={}", elementClass.getSimpleName());
            
            // 使用 ParameterizedTypeReference 处理 List<T> 泛型
            List<T> result = chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .entity(new ParameterizedTypeReference<List<T>>() {});
            
            if (result == null) {
                log.warn("结构化列表输出为空");
                return List.of();
            }
            
            log.info("结构化列表输出成功: elementClass={}, size={}", 
                    elementClass.getSimpleName(), result.size());
            return result;
            
        } catch (Exception e) {
            log.error("结构化列表输出失败: elementClass={}", elementClass.getSimpleName(), e);
            // 降级到 LLMJsonUtils
            log.warn("尝试使用 LLMJsonUtils 降级解析");
            try {
                String response = chatClient.prompt()
                        .user(userPrompt)
                        .call()
                        .content();
                return LLMJsonUtils.parseArray(response, elementClass);
            } catch (Exception fallbackEx) {
                log.error("降级解析也失败", fallbackEx);
                throw new BusinessException("AI 响应解析失败: " + e.getMessage());
            }
        }
    }

    /**
     * 使用结构化输出获取列表（带系统提示词）
     * 
     * @param chatClient     ChatClient 实例
     * @param systemPrompt   系统提示词
     * @param userPrompt     用户提示词
     * @param elementClass   列表元素类型
     * @param <T>            泛型类型
     * @return 解析后的列表
     */
    public static <T> List<T> getStructuredList(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt,
            Class<T> elementClass) {
        
        try {
            log.debug("结构化列表输出请求: elementClass={}", elementClass.getSimpleName());
            
            List<T> result = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(new ParameterizedTypeReference<List<T>>() {});
            
            if (result == null) {
                log.warn("结构化列表输出为空");
                return List.of();
            }
            
            log.info("结构化列表输出成功: elementClass={}, size={}", 
                    elementClass.getSimpleName(), result.size());
            return result;
            
        } catch (Exception e) {
            log.error("结构化列表输出失败: elementClass={}", elementClass.getSimpleName(), e);
            throw new BusinessException("AI 响应解析失败: " + e.getMessage());
        }
    }

    /**
     * 使用结构化输出获取 Map
     * 
     * @param chatClient     ChatClient 实例
     * @param systemPrompt   系统提示词
     * @param userPrompt     用户提示词
     * @return 解析后的 Map
     */
    public static Map<String, Object> getStructuredMap(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt) {
        
        try {
            // 创建 MapOutputConverter
            MapOutputConverter converter = new MapOutputConverter();
            
            // 获取格式化说明
            String format = converter.getFormat();
            
            // 构建完整的提示词
            String fullUserPrompt = userPrompt + "\n\n" + format;
            
            log.debug("结构化 Map 输出请求");
            
            // 调用模型
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(fullUserPrompt)
                    .call()
                    .content();
            
            // 转换为 Map
            Map<String, Object> result = converter.convert(response);
            
            if (result == null) {
                throw new BusinessException("结构化 Map 输出转换失败：返回 null");
            }
            
            log.info("结构化 Map 输出成功: keys={}", result.keySet());
            return result;
            
        } catch (Exception e) {
            log.error("结构化 Map 输出失败", e);
            throw new BusinessException("AI 响应解析失败: " + e.getMessage());
        }
    }

    /**
     * 获取简单列表（字符串列表）
     * 
     * @param chatClient     ChatClient 实例
     * @param userPrompt     用户提示词
     * @return 字符串列表
     */
    public static List<String> getStringList(
            ChatClient chatClient,
            String userPrompt) {
        
        return getStructuredList(chatClient, userPrompt, String.class);
    }

    /**
     * 获取简单列表（字符串列表，带系统提示词）
     * 
     * @param chatClient     ChatClient 实例
     * @param systemPrompt   系统提示词
     * @param userPrompt     用户提示词
     * @return 字符串列表
     */
    public static List<String> getStringList(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt) {
        
        return getStructuredList(chatClient, systemPrompt, userPrompt, String.class);
    }

    /**
     * 直接解析已有的响应字符串为对象
     * 适用于已经获取响应但需要解析的场景
     * 
     * @param response      LLM 响应字符串
     * @param outputClass   目标输出类
     * @param <T>           泛型类型
     * @return 解析后的对象
     */
    public static <T> T parseResponse(String response, Class<T> outputClass) {
        try {
            BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputClass);
            T result = converter.convert(response);
            
            if (result == null) {
                throw new BusinessException("响应解析失败：返回 null");
            }
            
            return result;
        } catch (Exception e) {
            log.error("响应解析失败: class={}", outputClass.getSimpleName(), e);
            throw new BusinessException("响应解析失败: " + e.getMessage());
        }
    }

    /**
     * 直接解析已有的响应字符串为列表
     * 
     * @param response      LLM 响应字符串
     * @param elementClass  列表元素类型
     * @param <T>           泛型类型
     * @return 解析后的列表
     */
    public static <T> List<T> parseResponseList(String response, Class<T> elementClass) {
        try {
            return LLMJsonUtils.parseArray(response, elementClass);
        } catch (Exception e) {
            log.error("响应列表解析失败: elementClass={}", elementClass.getSimpleName(), e);
            throw new BusinessException("响应列表解析失败: " + e.getMessage());
        }
    }
}
