package com.campustrade.admin.service;

import com.campustrade.admin.dto.AdminActionRequest;
import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.item.dto.ItemResponse;
import com.campustrade.item.service.ItemService;
import com.campustrade.report.dto.ReportResponse;
import com.campustrade.report.dto.ResolveReportRequest;
import com.campustrade.report.service.ReportService;
import com.campustrade.user.dto.UserResponse;
import com.campustrade.user.model.User;
import com.campustrade.user.model.UserRole;
import com.campustrade.user.model.UserStatus;
import com.campustrade.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminModerationService {

    private final UserRepository userRepository;
    private final ItemService itemService;
    private final ReportService reportService;
    private final AdminAuditService adminAuditService;

    public AdminModerationService(
            UserRepository userRepository,
            ItemService itemService,
            ReportService reportService,
            AdminAuditService adminAuditService
    ) {
        this.userRepository = userRepository;
        this.itemService = itemService;
        this.reportService = reportService;
        this.adminAuditService = adminAuditService;
    }

    @Transactional
    public UserResponse banUser(AuthenticatedUser admin, Long userId, AdminActionRequest request) {
        requireAdmin(admin);
        if (admin.id().equals(userId)) {
            throw BizException.badRequest("管理员不能封禁自己");
        }
        User user = requireUser(userId);
        user.setStatus(UserStatus.BANNED);
        userRepository.save(user);
        adminAuditService.record(admin.id(), "BAN_USER", "USER", userId, reason(request));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse unbanUser(AuthenticatedUser admin, Long userId, AdminActionRequest request) {
        requireAdmin(admin);
        User user = requireUser(userId);
        user.setStatus(UserStatus.NORMAL);
        userRepository.save(user);
        adminAuditService.record(admin.id(), "UNBAN_USER", "USER", userId, reason(request));
        return UserResponse.from(user);
    }

    @Transactional
    public ItemResponse offShelfItem(AuthenticatedUser admin, Long itemId, AdminActionRequest request) {
        requireAdmin(admin);
        ItemResponse response = itemService.forceOffShelfByAdmin(itemId);
        adminAuditService.record(admin.id(), "OFF_SHELF_ITEM", "ITEM", itemId, reason(request));
        return response;
    }

    @Transactional
    public ReportResponse resolveReport(AuthenticatedUser admin, Long reportId, ResolveReportRequest request) {
        requireAdmin(admin);
        ReportResponse response = reportService.resolve(admin.id(), reportId, request);
        adminAuditService.record(admin.id(), "RESOLVE_REPORT", "REPORT", reportId,
                request.status() + ": " + (request.auditResult() == null ? "" : request.auditResult()));
        return response;
    }

    public void requireAdmin(AuthenticatedUser admin) {
        if (admin.role() != UserRole.ADMIN) {
            throw BizException.forbidden("需要管理员权限");
        }
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BizException.notFound("用户不存在"));
    }

    private String reason(AdminActionRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            return "";
        }
        return request.reason().trim();
    }
}
