package com.ican.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ican.model.vo.SearchResultVO;
import cn.dev33.satoken.stp.StpUtil;
import com.ican.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 搜索控制器
 * 
 * @author ican
 */
@Tag(name = "统一搜索")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@SaCheckLogin
public class SearchController {
    
    private final SearchService searchService;
    
    @Operation(summary = "统一搜索", description = "结合向量检索和（可选）类型过滤返回带高亮片段的搜索结果；按相似度降序排序")
    @GetMapping
    public List<SearchResultVO> unifiedSearch(
            @Parameter(description = "查询文本") @RequestParam String query,
            @Parameter(description = "文档类型") @RequestParam(required = false) String type,
            @Parameter(description = "返回数量") @RequestParam(defaultValue = "10") Integer topK) {
        Long userId = StpUtil.getLoginIdAsLong();
        return searchService.unifiedSearch(query, type, topK, userId);
    }
    
    @Operation(summary = "查询纠错", description = "自动纠正查询中的常见英文拼写错误（简单字典替换）")
    @GetMapping("/correct")
    public Map<String, Object> correctQuery(
            @Parameter(description = "原始查询") @RequestParam String query) {
        
        String corrected = searchService.correctQuery(query);
        
        return Map.of(
            "original", query,
            "corrected", corrected,
            "changed", !query.equals(corrected)
        );
    }
}
