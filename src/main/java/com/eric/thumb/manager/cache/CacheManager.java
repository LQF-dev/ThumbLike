package com.eric.thumb.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.eric.thumb.constant.ThumbConstant;
import com.eric.thumb.mapper.ThumbMapper;
import com.eric.thumb.model.entity.Thumb;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 缓存类
 *
 * @author Eric
 */
@Component
@Slf4j
public class CacheManager {

    private TopK hotKeyDetector;

    private Cache<String, Object> localCache;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    
    @Resource
    private ThumbMapper thumbMapper;

    @Bean
    public TopK getHotKeyDetector() {
        hotKeyDetector = new HeavyKeeper(
                // 监控 Top 100 Key
                100,
                // 哈希表宽度
                100000,
                // 哈希表深度
                5,
                // 衰减系数
                0.92,
                // 最小出现 10 次才记录
                10
        );
        return hotKeyDetector;
    }

    @Bean
    public Cache<String, Object> localCache() {
        return localCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
    }

    // 辅助方法：构造复合 key
    private String buildCacheKey(String hashKey, String key) {
        return hashKey + ":" + key;
    }

    public Object get(String hashKey, String key) {
        // 构造唯一的 composite key
        String compositeKey = buildCacheKey(hashKey, key);

        // 1. 先查本地缓存
        Object value = localCache.getIfPresent(compositeKey);
        if (value != null) {
            log.info("本地缓存获取到数据 {} = {}", compositeKey, value);
            // 记录访问次数（每次访问计数 +1）
            hotKeyDetector.add(key, 1);
            return value;
        }

        // 2. 本地缓存未命中，查询 Redis
        Object redisValue = redisTemplate.opsForHash().get(hashKey, key);
        if (redisValue == null) {
            // 3. Redis也未命中，查询数据库（第三层校验）
            Object dbValue = queryFromDatabase(hashKey, key);
            if (dbValue != null) {
                // 记录访问并决定是否缓存
                AddResult addResult = hotKeyDetector.add(key, 1);
                
                // 将数据库结果写入Redis
                redisTemplate.opsForHash().put(hashKey, key, dbValue);
                
                // 如果是热Key，也写入本地缓存
                if (addResult.isHotKey()) {
                    localCache.put(compositeKey, dbValue);
                }
                
                return dbValue;
            }
            
            // 数据库也没有，返回空并缓存空值防止缓存穿透
            redisTemplate.opsForHash().put(hashKey, key, ThumbConstant.UN_THUMB_CONSTANT);
            return null;
        }

        // 3. Redis命中，记录访问（计数 +1）
        AddResult addResult = hotKeyDetector.add(key, 1);

        // 4. 如果是热 Key 且不在本地缓存，则缓存数据
        if (addResult.isHotKey()) {
            localCache.put(compositeKey, redisValue);
        }

        return redisValue;
    }

    public void putIfPresent(String hashKey, String key, Object value) {
        String compositeKey = buildCacheKey(hashKey, key);
        Object object = localCache.getIfPresent(compositeKey);
        // 这里有问题
        if (object != null) {
            return;
        }
        localCache.put(compositeKey, value);
    }

    public void put(String hashKey, String key, Object value) {
        String compositeKey = buildCacheKey(hashKey, key);

        localCache.put(compositeKey, value);
    }

    /**
     * 从数据库查询点赞记录
     * @param hashKey 格式：thumb:userId
     * @param key blogId
     * @return 查询结果，存在返回时间戳，不存在返回null
     */
    private Object queryFromDatabase(String hashKey, String key) {
        try {
            // 从hashKey中提取userId，格式：thumb:userId
            String userIdStr = hashKey.replace(ThumbConstant.USER_THUMB_KEY_PREFIX, "");
            Long userId = Long.valueOf(userIdStr);
            Long blogId = Long.valueOf(key);
            
            // 查询数据库是否存在点赞记录
            LambdaQueryWrapper<Thumb> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Thumb::getUserId, userId)
                       .eq(Thumb::getBlogId, blogId);
            
            Thumb thumb = thumbMapper.selectOne(queryWrapper);
            
            if (thumb != null) {
                // 存在记录，返回创建时间戳（模拟点赞时间）
                return thumb.getCreateTime();
            }
            
            return null;
        } catch (Exception e) {
            log.error("查询数据库失败: hashKey={}, key={}", hashKey, key, e);
            return null;
        }
    }

    // 定时清理过期的热 Key 检测数据
    @Scheduled(fixedRate = 20, timeUnit = TimeUnit.SECONDS)
    public void cleanHotKeys() {
        hotKeyDetector.fading();
    }
}