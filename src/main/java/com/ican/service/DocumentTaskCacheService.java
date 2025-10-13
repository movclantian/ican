package com.ican.service;

/**
 * 文档任务缓存服务接口
 * 使用Redis存储实时任务状态，减轻MySQL压力
 * 
 * @author 席崇援
 */
public interface DocumentTaskCacheService {
    
    /**
     * 更新任务进度（仅Redis）
     * 
     * @param taskId 任务ID
     * @param progress 进度(0-100)
     */
    void updateProgress(Long taskId, Integer progress);
    
    /**
     * 获取任务进度
     * 
     * @param taskId 任务ID
     * @return 进度值，不存在返回null
     */
    Integer getProgress(Long taskId);
    
    /**
     * 更新任务状态（Redis + MySQL）
     * 
     * @param taskId 任务ID
     * @param status 状态
     * @param progress 进度
     * @param errorMessage 错误信息
     */
    void updateTaskStatus(Long taskId, String status, Integer progress, String errorMessage);
    
    /**
     * 删除任务缓存
     * 
     * @param taskId 任务ID
     */
    void removeTaskCache(Long taskId);
}
