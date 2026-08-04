package com.campustrade.admin.service;

import com.campustrade.admin.dto.AdminActionRequest;
import com.campustrade.admin.repository.InMemoryAdminOperationLogRepository;
import com.campustrade.cache.CacheProperties;
import com.campustrade.cache.InMemoryHotItemRankService;
import com.campustrade.cache.InMemoryItemDetailCache;
import com.campustrade.common.exception.BizException;
import com.campustrade.common.web.AuthenticatedUser;
import com.campustrade.item.dto.CreateItemRequest;
import com.campustrade.item.dto.ItemResponse;
import com.campustrade.item.model.ConditionLevel;
import com.campustrade.item.model.ItemStatus;
import com.campustrade.item.repository.InMemoryItemRepository;
import com.campustrade.item.service.ItemService;
import com.campustrade.report.dto.CreateReportRequest;
import com.campustrade.report.dto.ResolveReportRequest;
import com.campustrade.report.model.ReportStatus;
import com.campustrade.report.repository.InMemoryReportRepository;
import com.campustrade.report.service.ReportService;
import com.campustrade.user.model.User;
import com.campustrade.user.model.UserRole;
import com.campustrade.user.model.UserStatus;
import com.campustrade.user.repository.InMemoryUserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminModerationServiceTest {

    @Test
    void adminShouldBanAndUnbanUser() {
        Fixture fixture = new Fixture();
        User user = fixture.saveUser("buyer", UserRole.USER);

        fixture.adminModerationService.banUser(fixture.admin(), user.getId(), new AdminActionRequest("fraud"));
        assertEquals(UserStatus.BANNED, fixture.userRepository.findById(user.getId()).orElseThrow().getStatus());

        fixture.adminModerationService.unbanUser(fixture.admin(), user.getId(), new AdminActionRequest("appeal accepted"));
        assertEquals(UserStatus.NORMAL, fixture.userRepository.findById(user.getId()).orElseThrow().getStatus());
    }

    @Test
    void nonAdminShouldNotRunAdminAction() {
        Fixture fixture = new Fixture();
        User user = fixture.saveUser("buyer", UserRole.USER);

        assertThrows(BizException.class, () -> fixture.adminModerationService.banUser(
                new AuthenticatedUser(user.getId(), user.getUsername(), UserRole.USER),
                user.getId(),
                new AdminActionRequest("nope")
        ));
    }

    @Test
    void adminShouldForceOffShelfItem() {
        Fixture fixture = new Fixture();
        ItemResponse item = fixture.itemService.create(10L, new CreateItemRequest(
                "违规商品",
                "违规商品描述",
                "其他",
                new BigDecimal("99.00"),
                ConditionLevel.LIKE_NEW,
                "前卫南区",
                "图书馆门口",
                List.of()
        ));

        ItemResponse response = fixture.adminModerationService.offShelfItem(
                fixture.admin(),
                item.id(),
                new AdminActionRequest("违规")
        );

        assertEquals(ItemStatus.OFF_SHELF, response.status());
    }

    @Test
    void adminShouldResolveReport() {
        Fixture fixture = new Fixture();
        var report = fixture.reportService.create(1L, new CreateReportRequest("item", 2L, "违规", "描述"));

        var resolved = fixture.adminModerationService.resolveReport(
                fixture.admin(),
                report.id(),
                new ResolveReportRequest(ReportStatus.RESOLVED, "已下架")
        );

        assertEquals(ReportStatus.RESOLVED, resolved.status());
        assertEquals(fixture.admin().id(), resolved.auditUserId());
    }

    private static class Fixture {
        private final InMemoryUserRepository userRepository = new InMemoryUserRepository();
        private final ItemService itemService = new ItemService(
                new InMemoryItemRepository(),
                new InMemoryItemDetailCache(new CacheProperties()),
                new InMemoryHotItemRankService()
        );
        private final ReportService reportService = new ReportService(new InMemoryReportRepository());
        private final AdminModerationService adminModerationService = new AdminModerationService(
                userRepository,
                itemService,
                reportService,
                new AdminAuditService(new InMemoryAdminOperationLogRepository())
        );

        private Fixture() {
            saveUser("admin", UserRole.ADMIN);
        }

        private AuthenticatedUser admin() {
            User admin = userRepository.findByUsername("admin").orElseThrow();
            return new AuthenticatedUser(admin.getId(), admin.getUsername(), admin.getRole());
        }

        private User saveUser(String username, UserRole role) {
            User user = new User();
            user.setUsername(username);
            user.setPasswordHash("hash");
            user.setNickname(username);
            user.setRole(role);
            user.setStatus(UserStatus.NORMAL);
            user.setCreditScore(100);
            return userRepository.save(user);
        }
    }
}
