package com.eric.thumb.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eric.thumb.model.entity.Blog;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * @author Eric
* @description 针对表【blog】的数据库操作Mapper
* @createDate 2025-06-11 11:20:53
* @Entity com.eric.thumb.domain.Blog
*/
public interface BlogMapper extends BaseMapper<Blog> {
    void batchUpdateThumbCount(@Param("countMap") Map<Long, Long> countMap);
}





