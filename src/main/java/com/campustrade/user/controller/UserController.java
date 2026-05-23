package com.campustrade.user.controller;

import com.campustrade.common.web.ApiResponse;
import com.campustrade.common.web.CurrentUserContext;
import com.campustrade.user.dto.UserResponse;
import com.campustrade.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        return ApiResponse.ok(userService.me(CurrentUserContext.require().id()));
    }
}
