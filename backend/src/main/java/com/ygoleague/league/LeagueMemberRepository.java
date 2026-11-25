package com.ygoleague.league;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeagueMemberRepository extends JpaRepository<LeagueMember, Long> {

    List<LeagueMember> findByLeagueIdOrderByDisplayName(Long leagueId);

    /** The user's membership in a league; empty means no access. Basis for per-league authorization. */
    Optional<LeagueMember> findByLeagueIdAndUserId(Long leagueId, Long userId);
}
