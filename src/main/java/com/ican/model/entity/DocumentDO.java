package com.ican.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档实体类
 * 
 * @author ICan
 * @since 2024-10-06
 */
@Data
@TableName("documents")
public class DocumentDO implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 文档ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    
    /**
     * 上传用户ID
     */
    private Long userId;
    
    /**
     * 文档标题
     */
    private String title;
    
    /**
     * 文档类型: research_paper, teaching_material, other
     */
    private String type;
    
    /**
     * 文件URL (MinIO路径)
     */
    private String fileUrl;
    
    /**
     * 文件大小(字节)
     */
    private Long fileSize;
    
    /**
     * 处理状态: pending, processing, completed, failed
     */
    private String status;
    
    /**
     * 文档元数据(JSON)
     */
    private String metadata;
    
    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    
    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDeleted;
}

