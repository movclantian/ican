-- Active: 1755149960906@@127.0.0.1@3306@mysql
-- Active: 1755149960906@@127.0.0.1@3306@ican24.62.55@7040@mysql
-- ============================
-- SmartPaperAI 用户表初始化脚本
-- ============================

-- 创建数据库(如果不存在)
CREATE DATABASE IF NOT EXISTS `ican` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `ican`;

-- 用户表
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码',
    `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像',
    `gender` TINYINT(1) DEFAULT 0 COMMENT '性别(0-未知,1-男,2-女)',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态(0-禁用,1-正常)',
    `last_login_time` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `last_login_ip` VARCHAR(50) DEFAULT NULL COMMENT '最后登录IP',
    `remark` VARCHAR(500) DEFAULT NULL COMMENT '备注',
    `create_by` VARCHAR(50) DEFAULT NULL COMMENT '创建者',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by` VARCHAR(50) DEFAULT NULL COMMENT '更新者',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除(0-未删除,1-已删除)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`) USING BTREE COMMENT '用户名唯一索引',
    KEY `idx_email` (`email`) USING BTREE COMMENT '邮箱索引',
    KEY `idx_phone` (`phone`) USING BTREE COMMENT '手机号索引',
    KEY `idx_status` (`status`) USING BTREE COMMENT '状态索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 插入测试用户
-- 密码为: 123456 (使用BCrypt加密)
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `email`, `status`, `gender`)
VALUES 
('admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE/TU.qG/4U0KW', '管理员', 'admin@example.com', 1, 1),
('user', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE/TU.qG/4U0KW', '普通用户', 'user@example.com', 1, 0);

-- ============================
-- 说明:
-- 默认密码都是: 123456
-- BCrypt 加密后的密码: $2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE/TU.qG/4U0KW
-- ============================
