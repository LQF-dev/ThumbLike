package com.eric.thumb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.eric.thumb.constant.UserConstant;
import com.eric.thumb.model.entity.User;
import com.eric.thumb.service.UserService;
import com.eric.thumb.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/**
 * @author Eric
* @description 针对表【user】的数据库操作Service实现
* @createDate 2025-06-11 11:17:15
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{


    @Override
    public User getLoginUser(HttpServletRequest request) {
        return (User) request.getSession().getAttribute(UserConstant.LOGIN_USER);
    }
}




