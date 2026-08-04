package com.campustrade.registration.service;

import com.campustrade.admin.service.AdminAuditService;
import com.campustrade.common.exception.BizException;
import com.campustrade.registration.RegistrationProperties;
import com.campustrade.registration.dto.CreateInviteCodeRequest;
import com.campustrade.registration.dto.InviteCodeResponse;
import com.campustrade.registration.model.InviteCode;
import com.campustrade.registration.model.InviteCodeStatus;
import com.campustrade.registration.repository.InviteCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class InviteCodeService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int GENERATED_CODE_LENGTH = 10;

    private final SecureRandom secureRandom = new SecureRandom();
    private final RegistrationProperties registrationProperties;
    private final InviteCodeRepository inviteCodeRepository;
    private final AdminAuditService adminAuditService;

    public InviteCodeService(
            RegistrationProperties registrationProperties,
            InviteCodeRepository inviteCodeRepository,
            AdminAuditService adminAuditService
    ) {
        this.registrationProperties = registrationProperties;
        this.inviteCodeRepository = inviteCodeRepository;
        this.adminAuditService = adminAuditService;
    }

    @Transactional
    public InviteCodeResponse create(Long adminId, CreateInviteCodeRequest request) {
        String code = request.code() == null || request.code().isBlank()
                ? generateUniqueCode()
                : normalizeCode(request.code());
        inviteCodeRepository.findByCode(code).ifPresent(existing -> {
            throw BizException.conflict("邀请码已存在");
        });

        InviteCode inviteCode = new InviteCode();
        inviteCode.setCode(code);
        inviteCode.setCreatedBy(adminId);
        inviteCode.setMaxUses(request.maxUses() == null ? 1 : request.maxUses());
        inviteCode.setUsedCount(0);
        inviteCode.setStatus(InviteCodeStatus.ACTIVE);
        inviteCode.setExpiresAt(request.expiresAt());
        inviteCode.setRemark(request.remark());
        inviteCodeRepository.save(inviteCode);
        adminAuditService.record(adminId, "CREATE_INVITE_CODE", "INVITE_CODE", inviteCode.getId(), code);
        return InviteCodeResponse.from(inviteCode);
    }

    public List<InviteCodeResponse> list(InviteCodeStatus status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        return inviteCodeRepository.findByStatus(status, safePage, safeSize).stream()
                .map(InviteCodeResponse::from)
                .toList();
    }

    @Transactional
    public InviteCodeResponse revoke(Long adminId, String code) {
        InviteCode inviteCode = inviteCodeRepository.findByCode(normalizeCode(code))
                .orElseThrow(() -> BizException.notFound("邀请码不存在"));
        inviteCode.setStatus(InviteCodeStatus.REVOKED);
        inviteCodeRepository.save(inviteCode);
        adminAuditService.record(adminId, "REVOKE_INVITE_CODE", "INVITE_CODE", inviteCode.getId(), inviteCode.getCode());
        return InviteCodeResponse.from(inviteCode);
    }

    @Transactional
    public void redeemForRegistration(String code) {
        if ((code == null || code.isBlank()) && !registrationProperties.isInviteCodeRequired()) {
            return;
        }
        if (code == null || code.isBlank()) {
            throw BizException.badRequest("注册邀请码不能为空");
        }
        boolean consumed = inviteCodeRepository.consumeIfAvailable(normalizeCode(code), LocalDateTime.now());
        if (!consumed) {
            throw BizException.conflict("邀请码无效、已过期或已用完");
        }
    }

    private String generateUniqueCode() {
        for (int attempts = 0; attempts < 10; attempts++) {
            String code = generateCode();
            if (inviteCodeRepository.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Failed to generate unique invite code");
    }

    private String generateCode() {
        StringBuilder builder = new StringBuilder(GENERATED_CODE_LENGTH);
        for (int i = 0; i < GENERATED_CODE_LENGTH; i++) {
            builder.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw BizException.badRequest("邀请码不能为空");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
