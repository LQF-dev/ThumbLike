package com.eric.thumb.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.eric.thumb.model.dto.thumb.DoThumbRequest;
import com.eric.thumb.model.entity.Thumb;
import jakarta.servlet.http.HttpServletRequest;

/**
 * @author Eric
* @description 针对表【thumb】的数据库操作Service
* @createDate 2025-06-11 11:20:46
*/
public interface ThumbService extends IService<Thumb> {
    /**
     * 点赞
     * @param doThumbRequest
     * @param request
     * @return {@link Boolean }
     */
    Boolean doThumb(DoThumbRequest doThumbRequest, HttpServletRequest request);

    /**
     * 取消点赞
     * @param doThumbRequest
     * @param request
     * @return {@link Boolean }
     */
    Boolean undoThumb(DoThumbRequest doThumbRequest, HttpServletRequest request);

    Boolean hasThumb(Long blogId, Long userId);




}
