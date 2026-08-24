package cn.jia.user.security;

import cn.jia.user.dao.UserInfoDao;
import cn.jia.user.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AccountSecurityServiceImpl implements AccountSecurityService {
    private final UserInfoDao userInfoDao;

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountSecuritySnapshot> findByUserId(long userId) {
        if (userId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(userInfoDao.selectSecurityById(userId)).map(this::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountSecuritySnapshot> findUniqueByExactJiacn(String jiacn) {
        if (jiacn == null || jiacn.isBlank()) {
            return Optional.empty();
        }
        List<UserEntity> matches = userInfoDao.selectSecurityByExactJiacn(jiacn);
        if (matches == null || matches.size() != 1) {
            return Optional.empty();
        }
        AccountSecuritySnapshot snapshot = snapshot(matches.getFirst());
        return jiacn.equals(snapshot.jiacn()) ? Optional.of(snapshot) : Optional.empty();
    }

    @Override
    @Transactional
    public int revokeAllSessions(long userId, long expectedEpoch) {
        if (userId <= 0 || expectedEpoch < 0 || expectedEpoch == Long.MAX_VALUE) {
            return 0;
        }
        return userInfoDao.incrementAuthEpoch(userId, expectedEpoch);
    }

    private AccountSecuritySnapshot snapshot(UserEntity user) {
        Long id = user.getId();
        Long epoch = user.getAuthEpoch();
        return new AccountSecuritySnapshot(
                id == null ? 0 : id,
                user.getJiacn(),
                AccountState.fromDatabaseValue(user.getAccountState()),
                epoch == null ? -1 : epoch);
    }
}
