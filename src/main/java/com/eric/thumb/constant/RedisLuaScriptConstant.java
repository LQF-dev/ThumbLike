package com.eric.thumb.constant;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Redis Lua脚本常量类
 * 
 * @author Eric
 */
public class RedisLuaScriptConstant {
  
    /**  
     * 点赞 Lua 脚本  
     * KEYS[1]       -- 临时计数键  
     * KEYS[2]       -- 用户点赞状态键  
     * ARGV[1]       -- 用户 ID  
     * ARGV[2]       -- 博客 ID  
     * 返回:  
     * -1: 已点赞  
     * 1: 操作成功  
     */  
    public static final RedisScript<Long> THUMB_SCRIPT = new DefaultRedisScript<>("""  
            local tempThumbKey = KEYS[1]       -- 临时计数键（如 thumb:temp:{timeSlice}）  
            local userThumbKey = KEYS[2]       -- 用户点赞状态键（如 thumb:{userId}）  
            local userId = ARGV[1]             -- 用户 ID  
            local blogId = ARGV[2]             -- 博客 ID  
              
            -- 1. 检查是否已点赞（避免重复操作）  
            if redis.call('HEXISTS', userThumbKey, blogId) == 1 then  
                return -1  -- 已点赞，返回 -1 表示失败  
            end  
              
            -- 2. 获取旧值（不存在则默认为 0）  
            local hashKey = userId .. ':' .. blogId  
            local oldNumber = tonumber(redis.call('HGET', tempThumbKey, hashKey) or 0)  
              
            -- 3. 计算新值  
            local newNumber = oldNumber + 1  
              
            -- 4. 原子性更新：写入临时计数 + 标记用户已点赞  
            redis.call('HSET', tempThumbKey, hashKey, newNumber)  
            redis.call('HSET', userThumbKey, blogId, 1)  
              
            return 1  -- 返回 1 表示成功  
            """, Long.class);  
  
    /**  
     * 取消点赞 Lua 脚本  
     * 参数同上  
     * 返回：  
     * -1: 未点赞  
     * 1: 操作成功  
     */  
    public static final RedisScript<Long> UNTHUMB_SCRIPT = new DefaultRedisScript<>("""  
            local tempThumbKey = KEYS[1]      -- 临时计数键（如 thumb:temp:{timeSlice}）  
            local userThumbKey = KEYS[2]      -- 用户点赞状态键（如 thumb:{userId}）  
            local userId = ARGV[1]            -- 用户 ID  
            local blogId = ARGV[2]            -- 博客 ID  
              
            -- 1. 检查用户是否已点赞（若未点赞，直接返回失败）  
            if redis.call('HEXISTS', userThumbKey, blogId) ~= 1 then  
                return -1  -- 未点赞，返回 -1 表示失败  
            end  
              
            -- 2. 获取当前临时计数（若不存在则默认为 0）  
            local hashKey = userId .. ':' .. blogId  
            local oldNumber = tonumber(redis.call('HGET', tempThumbKey, hashKey) or 0)  
              
            -- 3. 计算新值并更新  
            local newNumber = oldNumber - 1  
              
            -- 4. 原子性操作：更新临时计数 + 删除用户点赞标记  
            redis.call('HSET', tempThumbKey, hashKey, newNumber)  
            redis.call('HDEL', userThumbKey, blogId)  
              
            return 1  -- 返回 1 表示成功  
            """, Long.class);


    /**
     * 点赞 Lua 脚本
     * KEYS[1]       -- 用户点赞状态键
     * ARGV[1]       -- 博客 ID
     * 返回:
     * -1: 已点赞
     * 1: 操作成功
     */
    public static final RedisScript<Long> THUMB_SCRIPT_MQ = new DefaultRedisScript<>("""  
                local userThumbKey = KEYS[1]  
                local blogId = ARGV[1]  
          
                -- 判断是否已经点赞  
                if redis.call("HEXISTS", userThumbKey, blogId) == 1 then  
                    return -1  
                end  
          
                -- 添加点赞记录  
                redis.call("HSET", userThumbKey, blogId, 1)  
                return 1  
        """, Long.class);

    /**
     * 取消点赞 Lua 脚本
     * KEYS[1]       -- 用户点赞状态键
     * ARGV[1]       -- 博客 ID
     * 返回:
     * -1: 已点赞
     * 1: 操作成功
     */
    public static final RedisScript<Long> UNTHUMB_SCRIPT_MQ = new DefaultRedisScript<>("""  
        local userThumbKey = KEYS[1]  
        local blogId = ARGV[1]  
          
        -- 判断是否已点赞  
        if redis.call("HEXISTS", userThumbKey, blogId) == 0 then  
            return -1  
        end  
          
        -- 删除点赞记录  
        redis.call("HDEL", userThumbKey, blogId)  
        return 1  
        """, Long.class);

    /**
     * 增强版点赞检查脚本 - 支持批量检查和热点统计
     * KEYS[1]       -- 用户点赞状态键
     * KEYS[2]       -- 热点统计键
     * ARGV[1]       -- 博客ID列表 (逗号分隔)
     * ARGV[2]       -- 当前时间戳
     * 返回: 点赞状态数组 (1已点赞, 0未点赞)
     */
    public static final RedisScript<List> BATCH_CHECK_THUMB_SCRIPT = new DefaultRedisScript<>("""
        local userThumbKey = KEYS[1]
        local hotStatsKey = KEYS[2]
        local blogIds = {}
        local currentTime = ARGV[2]
        
        -- 解析博客ID列表
        for blogId in string.gmatch(ARGV[1], '([^,]+)') do
            table.insert(blogIds, blogId)
        end
        
        local results = {}
        for i, blogId in ipairs(blogIds) do
            -- 检查点赞状态
            local hasThumb = redis.call("HEXISTS", userThumbKey, blogId)
            table.insert(results, hasThumb)
            
            -- 更新热点统计 (使用 ZINCRBY 进行计数)
            redis.call("ZINCRBY", hotStatsKey, 1, "blog:" .. blogId)
        end
        
        -- 设置热点统计的过期时间 (24小时)
        redis.call("EXPIRE", hotStatsKey, 86400)
        
        return results
        """, List.class);

    /**
     * 热点数据快速点赞脚本 - 针对热点博客优化
     * KEYS[1]       -- 用户点赞状态键
     * KEYS[2]       -- 热点统计键  
     * KEYS[3]       -- 点赞计数缓存键
     * ARGV[1]       -- 博客ID
     * ARGV[2]       -- 用户ID
     * ARGV[3]       -- 当前时间戳
     * 返回: 1成功, -1已点赞, -2系统忙
     */
    public static final RedisScript<Long> HOT_THUMB_SCRIPT = new DefaultRedisScript<>("""
        local userThumbKey = KEYS[1]
        local hotStatsKey = KEYS[2]
        local thumbCountKey = KEYS[3]
        local blogId = ARGV[1]
        local userId = ARGV[2]
        local currentTime = ARGV[3]
        
        -- 检查是否已点赞
        if redis.call("HEXISTS", userThumbKey, blogId) == 1 then
            return -1
        end
        
        -- 记录点赞
        redis.call("HSET", userThumbKey, blogId, currentTime)
        
        -- 更新热点统计
        redis.call("ZINCRBY", hotStatsKey, 1, "blog:" .. blogId)
        
        -- 更新点赞计数缓存 (用于快速查询)
        local currentCount = redis.call("HINCRBY", thumbCountKey, blogId, 1)
        
        -- 设置过期时间
        redis.call("EXPIRE", userThumbKey, 86400)
        redis.call("EXPIRE", hotStatsKey, 86400)
        redis.call("EXPIRE", thumbCountKey, 3600)
        
        return 1
        """, Long.class);

    /**
     * 获取热点博客排行榜脚本
     * KEYS[1]       -- 热点统计键
     * ARGV[1]       -- 返回数量 (top N)
     * 返回: [blogId, score, blogId, score, ...]
     */
    public static final RedisScript<List> GET_HOT_BLOGS_SCRIPT = new DefaultRedisScript<>("""
        local hotStatsKey = KEYS[1]
        local limit = tonumber(ARGV[1]) or 10
        
        -- 获取热点博客 (按分数降序)
        local hotBlogs = redis.call("ZREVRANGE", hotStatsKey, 0, limit - 1, "WITHSCORES")
        
        return hotBlogs
        """, List.class);

}
