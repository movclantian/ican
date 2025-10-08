package com.ican.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.ican.model.vo.TeachingPlanListVO;
import com.ican.model.vo.TeachingPlanVO;
import com.ican.service.RAGService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 教学设计控制器
 *
 * @author ICan
 * @since 2024-10-07
 */
@Tag(name = "教学设计管理", description = "教学设计的增删改查功能")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/teaching-plans")
@SaCheckLogin
public class TeachingPlanController {
    
    private final RAGService ragService;
    
    /**
     * 保存教学设计
     * 
     * @param teachingPlanVO 教学设计内容
     * @return 教案ID
     */
    @Operation(summary = "保存教学设计", description = "保存AI生成的教学设计")
    @PostMapping
    public Long saveTeachingPlan(
            @Parameter(description = "教学设计内容") @RequestBody @Validated TeachingPlanVO teachingPlanVO) {
        return ragService.saveTeachingPlan(teachingPlanVO);
    }
    
    /**
     * 获取教学设计列表
     * 
     * @return 教学设计列表
     */
    @Operation(summary = "获取教学设计列表", description = "获取当前用户的所有教学设计")
    @GetMapping
    public List<TeachingPlanListVO> getUserTeachingPlans() {
        Long userId = StpUtil.getLoginIdAsLong();
        return ragService.getUserTeachingPlans(userId);
    }
    
    /**
     * 获取教学设计详情
     * 
     * @param planId 教案ID
     * @return 教学设计详情
     */
    @Operation(summary = "获取教学设计详情", description = "查看教学设计的完整内容")
    @GetMapping("/{planId}")
    public TeachingPlanVO getTeachingPlan(
            @Parameter(description = "教案ID") @PathVariable Long planId) {
        return ragService.getTeachingPlan(planId);
    }
    
    /**
     * 删除教学设计
     * 
     * @param planId 教案ID
     */
    @Operation(summary = "删除教学设计", description = "删除指定的教学设计")
    @DeleteMapping("/{planId}")
    public void deleteTeachingPlan(
            @Parameter(description = "教案ID") @PathVariable Long planId) {
        ragService.deleteTeachingPlan(planId);
    }
}
