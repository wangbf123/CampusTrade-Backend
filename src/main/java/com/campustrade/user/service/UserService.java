package com.campustrade.user.service;

import com.campustrade.auth.dto.AuthResponse;
import com.campustrade.auth.dto.LoginRequest;
import com.campustrade.auth.dto.RegisterRequest;
import com.campustrade.auth.security.PasswordHasher;
import com.campustrade.auth.security.TokenService;
import com.campustrade.common.exception.BizException;
import com.campustrade.registration.service.InviteCodeService;
import com.campustrade.user.dto.UserResponse;
import com.campustrade.user.model.User;
import com.campustrade.user.model.UserRole;
import com.campustrade.user.model.UserStatus;
import com.campustrade.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final InviteCodeService inviteCodeService;

    public UserService(
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            TokenService tokenService,
            InviteCodeService inviteCodeService
    ) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.inviteCodeService = inviteCodeService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        userRepository.findByUsername(request.username()).ifPresent(user -> {
            throw BizException.conflict("用户名已存在");
        });
        inviteCodeService.redeemForRegistration(request.inviteCode());

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordHasher.hash(request.password()));
        user.setNickname(request.nickname());
        user.setPhone(request.phone());
        user.setCampus(request.campus());
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.NORMAL);
        user.setCreditScore(100);
        userRepository.save(user);

        return new AuthResponse(tokenService.issue(user), UserResponse.from(user));
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> BizException.unauthorized("用户名或密码错误"));
        if (user.getStatus() == UserStatus.BANNED) {
            throw BizException.forbidden("账号已被封禁");
        }
        if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (passwordHasher.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(passwordHasher.hash(request.password()));
            userRepository.save(user);
        }
        return new AuthResponse(tokenService.issue(user), UserResponse.from(user));
    }

    public User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("用户不存在"));
    }

    public UserResponse me(Long userId) {
        return UserResponse.from(requireUser(userId));
    }
}
