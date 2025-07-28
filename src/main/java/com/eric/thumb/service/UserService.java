package com.eric.thumb.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.eric.thumb.model.entity.User;
import jakarta.servlet.http.HttpServletRequest;

/**
 * @author Eric
* @description 针对表【user】的数据库操作Service
* @createDate 2025-06-11 11:17:15
*/
public interface UserService extends IService<User> {
    User getLoginUser(HttpServletRequest request);

}
