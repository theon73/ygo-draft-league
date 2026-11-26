package com.ygoleague.league;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LeagueInviteRepository extends JpaRepository<LeagueInvite, Long> {

    Optional<LeagueInvite> findByCode(String code);

    /** Locks the invite row, so two people redeeming the last use can't both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LeagueInvite i where i.code = :code")
    Optional<LeagueInvite> lockByCode(String code);

    List<LeagueInvite> findByLeagueIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long leagueId);

    /** Claim invites pointing at a placeholder, removed together with it. */
    @Modifying
    @Query("delete from LeagueInvite i where i.member.id = :memberId")
    void deleteByMemberId(Long memberId);
}
