package com.eric.thumb.service.impl;

import cn.hutool.core.date.StopWatch;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eric.thumb.constant.RedisLuaScriptConstant;
import com.eric.thumb.listener.thumb.msg.ThumbEvent;
import com.eric.thumb.mapper.ThumbMapper;
import com.eric.thumb.model.dto.thumb.DoThumbRequest;
import com.eric.thumb.model.entity.Thumb;
import com.eric.thumb.model.entity.User;
import com.eric.thumb.model.enums.LuaStatusEnum;
import com.eric.thumb.service.ThumbService;
import com.eric.thumb.service.UserService;
import com.eric.thumb.util.RedisKeyUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
//
//@Service("thumbServiceMQ")
//@Service("thumbService")

/**
 * 基于消息队列的点赞服务实现
 * 
 * @author Eric
 */
@Slf4j
@RequiredArgsConstructor
public class ThumbServiceMQImpl extends ServiceImpl<ThumbMapper, Thumb>
        implements ThumbService {
  
    private final UserService userService;
  
    private final RedisTemplate<String, Object> redisTemplate;
  
    private final PulsarTemplate<ThumbEvent> pulsarTemplate;
  
    @Override  
    public Boolean doThumb(DoThumbRequest doThumbRequest, HttpServletRequest request) {
        if (doThumbRequest == null || doThumbRequest.getBlogId() == null) {  
            throw new RuntimeException("参数错误");  
        }  
        User loginUser = userService.getLoginUser(request);
        Long loginUserId = loginUser.getId();  
        Long blogId = doThumbRequest.getBlogId();  
        String userThumbKey = RedisKeyUtil.getUserThumbKey(loginUserId);
        // 执行 Lua 脚本，点赞存入 Redis
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("redis操作");
        long result = redisTemplate.execute(  
                RedisLuaScriptConstant.THUMB_SCRIPT_MQ,
                List.of(userThumbKey),
                blogId  
        );  
        if (LuaStatusEnum.FAIL.getValue() == result) {
            throw new RuntimeException("用户已点赞");  
        }

        stopWatch.stop();

        stopWatch.start("队列操作");

        ThumbEvent thumbEvent = ThumbEvent.builder()  
                .blogId(blogId)  
                .userId(loginUserId)  
                .type(ThumbEvent.EventType.INCR)  
                .eventTime(LocalDateTime.now())
                .build();  
        pulsarTemplate.sendAsync("thumb-topic", thumbEvent).exceptionally(ex -> {  
            redisTemplate.opsForHash().delete(userThumbKey, blogId.toString(), true);  
            log.error("点赞事件发送失败: userId={}, blogId={}", loginUserId, blogId, ex);  
            return null;  
        });
        stopWatch.stop();
        System.out.println(stopWatch.prettyPrint());
  
        return true;  
    }  
  
    @Override  
    public Boolean undoThumb(DoThumbRequest doThumbRequest, HttpServletRequest request) {  
        if (doThumbRequest == null || doThumbRequest.getBlogId() == null) {  
            throw new RuntimeException("参数错误");  
        }  
        User loginUser = userService.getLoginUser(request);  
        Long loginUserId = loginUser.getId();  
        Long blogId = doThumbRequest.getBlogId();  
        String userThumbKey = RedisKeyUtil.getUserThumbKey(loginUserId);  
        // 执行 Lua 脚本，点赞记录从 Redis 删除  
        long result = redisTemplate.execute(  
                RedisLuaScriptConstant.UNTHUMB_SCRIPT_MQ,  
                List.of(userThumbKey),  
                blogId  
        );  
        if (LuaStatusEnum.FAIL.getValue() == result) {  
            throw new RuntimeException("用户未点赞");  
        }  
        ThumbEvent thumbEvent = ThumbEvent.builder()  
                .blogId(blogId)  
                .userId(loginUserId)  
                .type(ThumbEvent.EventType.DECR)  
                .eventTime(LocalDateTime.now())  
                .build();  
        pulsarTemplate.sendAsync("thumb-topic", thumbEvent).exceptionally(ex -> {  
            redisTemplate.opsForHash().put(userThumbKey, blogId.toString(), true);  
            log.error("点赞事件发送失败: userId={}, blogId={}", loginUserId, blogId, ex);  
            return null;  
        });  
  
        return true;  
    }  
  
    @Override  
    public Boolean hasThumb(Long blogId, Long userId) {  
        return redisTemplate.opsForHash().hasKey(RedisKeyUtil.getUserThumbKey(userId), blogId.toString());  
    }  
  
}
