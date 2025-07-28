package com.eric.thumb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eric.thumb.constant.ThumbConstant;
import com.eric.thumb.listener.thumb.msg.ThumbEvent;
import com.eric.thumb.manager.cache.CacheManager;
import com.eric.thumb.mapper.ThumbMapper;
import com.eric.thumb.model.dto.thumb.DoThumbRequest;
import com.eric.thumb.model.entity.Thumb;
import com.eric.thumb.model.entity.User;
import com.eric.thumb.service.ThumbService;
import com.eric.thumb.service.UserService;
import com.eric.thumb.util.RedisKeyUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;


/**
 * 简化优化后的点赞服务实现
 *
 * 基于用户洞察的关键理解：
 * ✅ CacheManager已经自动实现了完美的智能缓存逻辑
 * ✅ 所有数据都走统一的读取路径：L1本地缓存 -> L2Redis -> 数据库
 * ✅ 只有热点数据会被自动缓存到L1，无需业务代码干预
 * ✅ 热点检测和缓存决策完全自动化
 *
 * 核心设计原则：
 * 1. 写入路径统一：所有数据都发送到同一个MQ，复用现有的批量消费者
 * 2. 读取路径交给CacheManager：业务代码无需关心缓存策略
 * 3. 架构极简：移除冗余逻辑，让专业组件做专业的事
 * 4. 充分复用：利用现有的ThumbConsumerConfig(1000条批量处理)
 * 5. 分布式锁：防止重复点赞，保证数据一致性
 *
 * @author Eric
 */
@Service("thumbService")
@Slf4j
@RequiredArgsConstructor
public class ThumbServiceMQCacheImpl extends ServiceImpl<ThumbMapper, Thumb> implements ThumbService {

    private final UserService userService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final PulsarTemplate<ThumbEvent> pulsarTemplate;
    private final CacheManager cacheManager;
    private final RedissonClient redissonClient;

    /**
     * 点赞操作 - 统一架构，极简设计 + 分布式锁
     */
    @Override
    public Boolean doThumb(DoThumbRequest doThumbRequest, HttpServletRequest request) {
        if (doThumbRequest == null || doThumbRequest.getBlogId() == null) {
            throw new RuntimeException("参数错误");
        }

        User loginUser = userService.getLoginUser(request);
        Long loginUserId = loginUser.getId();
        Long blogId = doThumbRequest.getBlogId();

        // 分布式锁key：基于userId + blogId组合
        String lockKey = "thumb:lock:" + loginUserId + ":" + blogId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 立即尝试获取锁，获取不到直接返回失败
            if (lock.tryLock(200, TimeUnit.MILLISECONDS)){
                try {
                    // 1. 使用多级缓存检查用户是否已点赞
                    Boolean exists = this.hasThumb(blogId, loginUserId);
                    if (exists) {
                        throw new RuntimeException("用户已点赞");
                    }

                    // 2. Redis记录点赞状态
                    String userThumbKey = RedisKeyUtil.getUserThumbKey(loginUserId);
                    redisTemplate.opsForHash().put(userThumbKey, blogId.toString(), System.currentTimeMillis());

                    // 3. 本地缓存设置 为 【已点赞】
                    cacheManager.put(userThumbKey, blogId.toString(), System.currentTimeMillis());

                    ThumbEvent thumbEvent = ThumbEvent.builder()
                            .blogId(blogId)
                            .userId(loginUserId)
                            .type(ThumbEvent.EventType.INCR)
                            .eventTime(LocalDateTime.now())
                            .build();
                    pulsarTemplate.sendAsync("thumb-topic", thumbEvent).exceptionally(ex -> {
                        redisTemplate.opsForHash().delete(userThumbKey, blogId.toString());
                        cacheManager.putIfPresent(userThumbKey, blogId.toString(), ThumbConstant.UN_THUMB_CONSTANT);
                        log.error("点赞事件发送失败: userId={}, blogId={}", loginUserId, blogId, ex);
                        return null;
                    });

                    return true;
                } finally {
                    // 确保锁被释放
                    lock.unlock();
                }
            } else {
                throw new RuntimeException("操作过于频繁，请稍后再试");
            }
        } catch (Exception e) {
            log.error("点赞操作异常: userId={}, blogId={}", loginUserId, blogId, e);
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * 取消点赞操作 - 统一架构 + 分布式锁
     */
    @Override
    public Boolean undoThumb(DoThumbRequest doThumbRequest, HttpServletRequest request) {
        if (doThumbRequest == null || doThumbRequest.getBlogId() == null) {
            throw new RuntimeException("参数错误");
        }

        User loginUser = userService.getLoginUser(request);
        Long loginUserId = loginUser.getId();
        Long blogId = doThumbRequest.getBlogId();

        // 分布式锁key：基于userId + blogId组合
        String lockKey = "thumb:lock:" + loginUserId + ":" + blogId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 立即尝试获取锁，获取不到直接返回失败
            if (lock.tryLock(200, TimeUnit.MILLISECONDS)){
                try {
                    // 1. 使用多级缓存检查用户是否已点赞
                    Boolean exists = this.hasThumb(blogId, loginUserId);
                    if (!exists) {
                        throw new RuntimeException("用户未点赞");
                    }

                    // 2. Redis删除点赞记录
                    String userThumbKey = RedisKeyUtil.getUserThumbKey(loginUserId);
                    redisTemplate.opsForHash().delete(userThumbKey, blogId.toString());

                    // 3. 本地缓存设置 为 【未点赞】
                    cacheManager.put(userThumbKey, blogId.toString(), ThumbConstant.UN_THUMB_CONSTANT);

                    // 3. 统一发送MQ
                    ThumbEvent thumbEvent = ThumbEvent.builder()
                            .blogId(blogId)
                            .userId(loginUserId)
                            .type(ThumbEvent.EventType.DECR)
                            .eventTime(LocalDateTime.now())
                            .build();
                    pulsarTemplate.sendAsync("thumb-topic", thumbEvent).exceptionally(ex -> {
                        redisTemplate.opsForHash().put(userThumbKey, blogId.toString(), true);
                        cacheManager.putIfPresent(userThumbKey, blogId.toString(), true);
                        log.error("取消点赞事件发送失败: userId={}, blogId={}", loginUserId, blogId, ex);
                        return null;
                    });

                    return true;
                } finally {
                    // 确保锁被释放
                    lock.unlock();
                }
            } else {
                throw new RuntimeException("操作过于频繁，请稍后再试");
            }
        } catch (Exception e) {
            log.error("取消点赞操作异常: userId={}, blogId={}", loginUserId, blogId, e);
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * 检查点赞状态 - 正确使用CacheManager的多级缓存
     *
     * 缓存数据格式说明：
     * - null: 缓存中没有数据
     * - UN_THUMB_CONSTANT (0L): 用户未点赞（避免缓存穿透）
     * - 具体的thumbId: 用户已点赞
     */
    @Override
    public Boolean hasThumb(Long blogId, Long userId) {
        String userThumbKey = RedisKeyUtil.getUserThumbKey(userId);

        // CacheManager自动处理多级缓存：
        // 1. 查询L1本地缓存
        // 2. L1未命中 -> 查询L2Redis缓存（所有数据）
        // 3. L2未命中 -> 查询数据库
        // 4. 记录访问并检测热点
        // 5. 如果是热点，自动缓存到L1
        // 6. 如果非热点，不污染L1缓存
        Object thumbIdObj = cacheManager.get(userThumbKey, blogId.toString());

        if (thumbIdObj == null) {
            // 缓存中没有数据，表示用户未点赞
            return false;
        }

        Long thumbId = Long.valueOf(thumbIdObj.toString());
        // 检查是否为UN_THUMB_CONSTANT，如果是则表示用户未点赞
        return !thumbId.equals(ThumbConstant.UN_THUMB_CONSTANT);
    }

} 