package com.eric.thumb.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.eric.thumb.model.entity.Blog;
import com.eric.thumb.model.vo.BlogVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * @author Eric
* @description 针对表【blog】的数据库操作Service
* @createDate 2025-06-11 11:20:53
*/
public interface BlogService extends IService<Blog> {
    BlogVO getBlogVOById(long blogId, HttpServletRequest request);
    List<BlogVO> getBlogVOList(List<Blog> blogList, HttpServletRequest request);

}
