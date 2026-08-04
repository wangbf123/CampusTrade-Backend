package com.campustrade.admin.controller;

import com.campustrade.admin.dto.AdminActionRequest;
import com.campustrade.admin.service.AdminModerationService;
import com.campustrade.common.web.ApiResponse;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.common.web.CurrentUserContext;
import com.campustrade.item.dto.ItemResponse;
import com.campustrade.registration.dto.CreateInviteCodeRequest;
import com.campustrade.registration.dto.InviteCodeResponse;
import com.campustrade.registration.model.InviteCodeStatus;
import com.campustrade.registration.service.InviteCodeService;
import com.campustrade.report.dto.ReportResponse;
import com.campustrade.report.dto.ResolveReportRequest;
import com.campustrade.report.model.ReportStatus;
import com.campustrade.report.service.ReportService;
import com.campustrade.risk.ratelimit.RateLimit;
import com.campustrade.risk.ratelimit.RateLimitScope;
import com.campustrade.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminModerationService adminModerationService;
    private final ReportService reportService;
    private final InviteCodeService inviteCodeService;

    public AdminController(
            AdminModerationService adminModerationService,
            ReportService reportService,
            InviteCodeService inviteCodeService
    ) {
        this.adminModerationService = adminModerationService;
        this.reportService = reportService;
        this.inviteCodeService = inviteCodeService;
    }

    @GetMapping("/reports")
    public ApiResponse<List<ReportResponse>> reports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        AuthenticatedUser admin = CurrentUserContext.require();
        adminModerationService.requireAdmin(admin);
        return ApiResponse.ok(reportService.list(status, page, size));
    }

    @PostMapping("/reports/{reportId}/resolve")
    @RateLimit(key = "admin:report:resolve", permits = 60, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<ReportResponse> resolveReport(
            @PathVariable Long reportId,
            @Valid @RequestBody ResolveReportRequest request
    ) {
        return ApiResponse.ok(adminModerationService.resolveReport(CurrentUserContext.require(), reportId, request));
    }

    @PutMapping("/users/{userId}/ban")
    @RateLimit(key = "admin:user:ban", permits = 60, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<UserResponse> banUser(
            @PathVariable Long userId,
            @Valid @RequestBody(required = false) AdminActionRequest request
    ) {
        return ApiResponse.ok(adminModerationService.banUser(CurrentUserContext.require(), userId, request));
    }

    @PutMapping("/users/{userId}/unban")
    @RateLimit(key = "admin:user:unban", permits = 60, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<UserResponse> unbanUser(
            @PathVariable Long userId,
            @Valid @RequestBody(required = false) AdminActionRequest request
    ) {
        return ApiResponse.ok(adminModerationService.unbanUser(CurrentUserContext.require(), userId, request));
    }

    @PutMapping("/items/{itemId}/off-shelf")
    @RateLimit(key = "admin:item:off-shelf", permits = 60, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<ItemResponse> offShelfItem(
            @PathVariable Long itemId,
            @Valid @RequestBody(required = false) AdminActionRequest request
    ) {
        return ApiResponse.ok(adminModerationService.offShelfItem(CurrentUserContext.require(), itemId, request));
    }

    @GetMapping("/invite-codes")
    public ApiResponse<List<InviteCodeResponse>> inviteCodes(
            @RequestParam(required = false) InviteCodeStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        AuthenticatedUser admin = CurrentUserContext.require();
        adminModerationService.requireAdmin(admin);
        return ApiResponse.ok(inviteCodeService.list(status, page, size));
    }

    @PostMapping("/invite-codes")
    @RateLimit(key = "admin:invite-code:create", permits = 60, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<InviteCodeResponse> createInviteCode(@Valid @RequestBody CreateInviteCodeRequest request) {
        AuthenticatedUser admin = CurrentUserContext.require();
        adminModerationService.requireAdmin(admin);
        return ApiResponse.ok(inviteCodeService.create(admin.id(), request));
    }

    @PutMapping("/invite-codes/{code}/revoke")
    @RateLimit(key = "admin:invite-code:revoke", permits = 60, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<InviteCodeResponse> revokeInviteCode(@PathVariable String code) {
        AuthenticatedUser admin = CurrentUserContext.require();
        adminModerationService.requireAdmin(admin);
        return ApiResponse.ok(inviteCodeService.revoke(admin.id(), code));
    }
}
