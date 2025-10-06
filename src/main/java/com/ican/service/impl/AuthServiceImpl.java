package com.ican.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ican.mapper.UserMapper;
import com.ican.model.dto.LoginRequest;
import com.ican.model.dto.RegisterRequest;
import com.ican.model.entity.User;
import com.ican.model.vo.CaptchaVO;
import com.ican.model.vo.LoginVO;
import com.ican.model.vo.UserInfoVO;
import com.ican.service.AuthService;
import com.wf.captcha.base.Captcha;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.starter.captcha.graphic.core.GraphicCaptchaService;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务实现类
 *
 * @author ICan
 * @since 2024-10-03
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final GraphicCaptchaService graphicCaptchaService;

    /**
     * 验证码 Redis Key 前缀
     */
    @Value("${captcha.redis-key-prefix:captcha:}")
    private String captchaRedisKeyPrefix;

    /**
     * 验证码过期时间(分钟)
     */
    @Value("${captcha.expire-minutes:5}")
    private long captchaExpireMinutes;

    @Override
    public CaptchaVO getCaptcha() {
        // 1. 生成验证码
        Captcha captcha = graphicCaptchaService.getCaptcha();
        
        // 2. 生成唯一标识
        String uuid = UUID.randomUUID().toString(true);
        
        // 3. 将验证码存入 Redis，设置过期时间
        String redisKey = captchaRedisKeyPrefix + uuid;
        stringRedisTemplate.opsForValue().set(
                redisKey, 
                captcha.text(), 
                captchaExpireMinutes, 
                TimeUnit.MINUTES
        );
        
        // 4. 返回验证码信息
        return CaptchaVO.builder()
                .captchaKey(uuid)
                .captchaImage(captcha.toBase64())
                .expireTime(captchaExpireMinutes * 60)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(LoginRequest loginRequest) {
        // 1. 参数校验
        if (StrUtil.isBlank(loginRequest.getUsername()) || StrUtil.isBlank(loginRequest.getPassword())) {
            throw new BusinessException("用户名或密码不能为空");
        }

        // 2. 验证码校验(如果提供了验证码key,则必须验证)
        if (StrUtil.isNotBlank(loginRequest.getCaptchaKey())) {
            validateCaptcha(loginRequest.getCaptchaKey(), loginRequest.getCaptcha());
        }
        // 如果提供了验证码但没有提供key,也需要报错
        if (StrUtil.isNotBlank(loginRequest.getCaptcha()) && StrUtil.isBlank(loginRequest.getCaptchaKey())) {
            throw new BusinessException("验证码key不能为空");
        }

        // 3. 查询用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, loginRequest.getUsername())
                .eq(User::getDeleted, 0));

        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        // 4. 验证密码
        if (!BCrypt.checkpw(loginRequest.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 5. 检查用户状态
        if (user.getStatus() == 0) {
            throw new BusinessException("该账号已被禁用,请联系管理员");
        }

        // 6. 执行登录
        StpUtil.login(user.getId());

        // 7. 更新最后登录信息
        user.setLastLoginTime(LocalDateTime.now());
        // TODO: 获取真实IP
        // user.setLastLoginIp(ServletUtil.getClientIP(request));
        userMapper.updateById(user);

        // 8. 获取 Token 信息
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();

        // 9. 构建用户信息
        UserInfoVO userInfo = UserInfoVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .gender(user.getGender())
                .phone(user.getPhone())
                .lastLoginTime(user.getLastLoginTime())
                .lastLoginIp(user.getLastLoginIp())
                .build();

        // 10. 返回登录信息
        return LoginVO.builder()
                .token(tokenInfo.getTokenValue())
                .tokenName(tokenInfo.getTokenName())
                .tokenPrefix("Bearer")
                .tokenTimeout(tokenInfo.getTokenTimeout())
                .userInfo(userInfo)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest registerRequest) {
        // 1. 参数校验
        if (!registerRequest.getPassword().equals(registerRequest.getConfirmPassword())) {
            throw new BusinessException("两次密码输入不一致");
        }

        // 2. 验证码校验(如果提供了验证码key,则必须验证)
        if (StrUtil.isNotBlank(registerRequest.getCaptchaKey())) {
            validateCaptcha(registerRequest.getCaptchaKey(), registerRequest.getCaptcha());
        }
        // 如果提供了验证码但没有提供key,也需要报错
        if (StrUtil.isNotBlank(registerRequest.getCaptcha()) && StrUtil.isBlank(registerRequest.getCaptchaKey())) {
            throw new BusinessException("验证码key不能为空");
        }

        // 3. 检查用户名是否已存在
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, registerRequest.getUsername())
                .eq(User::getDeleted, 0));

        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }

        // 4. 检查邮箱是否已被使用
        if (StrUtil.isNotBlank(registerRequest.getEmail())) {
            Long emailCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getEmail, registerRequest.getEmail())
                    .eq(User::getDeleted, 0));

            if (emailCount > 0) {
                throw new BusinessException("邮箱已被使用");
            }
        }

        // 5. 加密密码
        String encryptedPassword = BCrypt.hashpw(registerRequest.getPassword());

        // 6. 创建用户
        User user = User.builder()
                .username(registerRequest.getUsername())
                .password(encryptedPassword)
                .nickname(registerRequest.getUsername())
                .email(registerRequest.getEmail())
                .status(1)
                .gender(0)
                .build();

        userMapper.insert(user);

        log.info("用户注册成功: {}", registerRequest.getUsername());
    }

    @Override
    public void logout() {
        // 检查是否已登录
        if (!StpUtil.isLogin()) {
            throw new BusinessException("未登录,无需退出");
        }

        // 执行退出登录
        StpUtil.logout();

        log.info("用户退出登录成功");
    }

    @Override
    public Long getCurrentUserId() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        return StpUtil.getLoginIdAsLong();
    }

    @Override
    public boolean isLogin() {
        return StpUtil.isLogin();
    }

    /**
     * 验证验证码
     *
     * @param captchaKey 验证码key
     * @param captcha    验证码
     */
    private void validateCaptcha(String captchaKey, String captcha) {
        // 1. 校验验证码是否为空
        if (StrUtil.isBlank(captcha)) {
            throw new BusinessException("验证码不能为空");
        }

        // 2. 从 Redis 获取验证码
        String redisKey = captchaRedisKeyPrefix + captchaKey;
        String correctCaptcha = stringRedisTemplate.opsForValue().get(redisKey);

        // 3. 验证码是否存在或已过期
        if (StrUtil.isBlank(correctCaptcha)) {
            throw new BusinessException("验证码已过期,请重新获取");
        }

        // 4. 验证码校验(不区分大小写)
        if (!captcha.equalsIgnoreCase(correctCaptcha)) {
            throw new BusinessException("验证码错误");
        }

        // 5. 验证成功后删除验证码
        stringRedisTemplate.delete(redisKey);
    }
}
