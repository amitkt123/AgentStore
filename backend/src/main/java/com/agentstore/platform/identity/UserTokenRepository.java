package com.agentstore.platform.identity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {

    /** Locked for update: two concurrent redemptions of the same link must serialize, not both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from UserToken t where t.tokenHash = :hash and t.purpose = :purpose")
    Optional<UserToken> findByTokenHashAndPurpose(@Param("hash") String tokenHash,
                                                  @Param("purpose") UserTokenPurpose purpose);

    /** Retires earlier unused tokens so only the newest emailed link works. */
    @Modifying
    @Query("update UserToken t set t.usedAt = :now "
            + "where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
    int retireUnused(@Param("userId") UUID userId, @Param("purpose") UserTokenPurpose purpose,
                     @Param("now") Instant now);
}
