package com.ican.service.impl;

import com.ican.mapper.DocumentTaskMapper;
import com.ican.model.entity.DocumentTaskDO;
import com.ican.service.DocumentTaskCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 文档任务缓存服务实现
 * 
 * @author ican
 */
@Slf4j
@Service
@ConditionalOnBean(RedisTemplate.class)
@RequiredArgsConstructor
public class DocumentTaskCacheServiceImpl implements DocumentTaskCacheService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final DocumentTaskMapper documentTaskMapper;
    
    private static final String TASK_PROGRESS_PREFIX = "task:progress:";
    private static final String TASK_STATUS_PREFIX = "task:status:";
    private static final long CACHE_EXPIRE_HOURS = 24;
    
    @Override
    public void updateProgress(Long taskId, Integer progress) {
        String key = TASK_PROGRESS_PREFIX + taskId;
        redisTemplate.opsForValue().set(key, progress.toString(), CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        // 同步更新MySQL
        DocumentTaskDO task = new DocumentTaskDO();
        task.setId(taskId);
        task.setProgress(progress);
        task.setUpdateTime(LocalDateTime.now());
        documentTaskMapper.updateById(task);
        log.debug("更新任务进度到MySQL: taskId={}, progress={}", taskId, progress);
        log.debug("更新任务进度到Redis: taskId={}, progress={}", taskId, progress);
    }
    
    @Override
    public Integer getProgress(Long taskId) {
        String key = TASK_PROGRESS_PREFIX + taskId;
        Object value = redisTemplate.opsForValue().get(key);
        
        if (value != null) {
            return Integer.valueOf(value.toString());
        }
        
        // Redis未命中，从MySQL读取
        DocumentTaskDO task = documentTaskMapper.selectById(taskId);
        if (task != null) {
            Integer progress = task.getProgress();
            // 回填Redis
            redisTemplate.opsForValue().set(key, String.valueOf(progress), CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
            return progress;
        }
        
        return null;
    }
    
    @Override
    public void updateTaskStatus(Long taskId, String status, Integer progress, String errorMessage) {
        // 1. 更新Redis缓存
        updateProgress(taskId, progress);
        
        String statusKey = TASK_STATUS_PREFIX + taskId;
        redisTemplate.opsForValue().set(statusKey, status, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        
        // 2. 更新MySQL（仅关键状态变更）
        DocumentTaskDO task = new DocumentTaskDO();
        task.setId(taskId);
        task.setStatus(status);
        task.setProgress(progress);
        task.setErrorMessage(errorMessage);
        task.setUpdateTime(LocalDateTime.now());
        
        // 完成或失败时更新endTime
        if ("completed".equals(status) || "failed".equals(status)) {
            task.setEndTime(LocalDateTime.now());
        }
        
        documentTaskMapper.updateById(task);
        
        log.info("更新任务状态: taskId={}, status={}, progress={}%", taskId, status, progress);
    }
    
    @Override
    public void removeTaskCache(Long taskId) {
        redisTemplate.delete(TASK_PROGRESS_PREFIX + taskId);
        redisTemplate.delete(TASK_STATUS_PREFIX + taskId);
        log.debug("删除任务缓存: taskId={}", taskId);
    }
}
