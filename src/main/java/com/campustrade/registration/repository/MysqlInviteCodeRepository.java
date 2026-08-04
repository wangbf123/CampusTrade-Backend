package com.campustrade.registration.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.registration.mapper.InviteCodeMapper;
import com.campustrade.registration.model.InviteCode;
import com.campustrade.registration.model.InviteCodeStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MysqlInviteCodeRepository implements InviteCodeRepository {

    private final InviteCodeMapper inviteCodeMapper;

    public MysqlInviteCodeRepository(InviteCodeMapper inviteCodeMapper) {
        this.inviteCodeMapper = inviteCodeMapper;
    }

    @Override
    public InviteCode save(InviteCode inviteCode) {
        LocalDateTime now = LocalDateTime.now();
        if (inviteCode.getId() == null) {
            inviteCode.setCreatedAt(now);
            inviteCode.setUpdatedAt(now);
            inviteCodeMapper.insert(inviteCode);
            return inviteCode;
        }
        inviteCode.setUpdatedAt(now);
        inviteCodeMapper.updateById(inviteCode);
        return inviteCode;
    }

    @Override
    public Optional<InviteCode> findByCode(String code) {
        InviteCode inviteCode = inviteCodeMapper.selectOne(new LambdaQueryWrapper<InviteCode>()
                .eq(InviteCode::getCode, code)
                .last("LIMIT 1"));
        return Optional.ofNullable(inviteCode);
    }

    @Override
    public List<InviteCode> findByStatus(InviteCodeStatus status, int page, int size) {
        int offset = (page - 1) * size;
        return inviteCodeMapper.selectList(new LambdaQueryWrapper<InviteCode>()
                .eq(status != null, InviteCode::getStatus, status)
                .orderByDesc(InviteCode::getCreatedAt)
                .orderByDesc(InviteCode::getId)
                .last("LIMIT " + offset + ", " + size));
    }

    @Override
    public boolean consumeIfAvailable(String code, LocalDateTime now) {
        int rows = inviteCodeMapper.update(null, new LambdaUpdateWrapper<InviteCode>()
                .eq(InviteCode::getCode, code)
                .eq(InviteCode::getStatus, InviteCodeStatus.ACTIVE)
                .apply("used_count < max_uses")
                .and(wrapper -> wrapper.isNull(InviteCode::getExpiresAt)
                        .or()
                        .gt(InviteCode::getExpiresAt, now))
                .set(InviteCode::getUpdatedAt, now)
                .setSql("used_count = used_count + 1"));
        return rows == 1;
    }
}
