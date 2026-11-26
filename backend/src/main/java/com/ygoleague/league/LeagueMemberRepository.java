package com.ygoleague.league;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LeagueMemberRepository extends JpaRepository<LeagueMember, Long> {

    List<LeagueMember> findByLeagueIdOrderByDisplayName(Long leagueId);

    /** The user's membership in a league; empty means no access. Basis for per-league authorization. */
    Optional<LeagueMember> findByLeagueIdAndUserId(Long leagueId, Long userId);

    /** Same as {@link #findByLeagueIdAndUserId}, with the league fetched (used by {@link LeagueAccess}). */
    @Query("select m from LeagueMember m join fetch m.league where m.league.id = :leagueId and m.user.id = :userId")
    Optional<LeagueMember> findWithLeague(Long leagueId, Long userId);

    /** Every league the user belongs to, with the league fetched, by league name ("My leagues"). */
    @Query("select m from LeagueMember m join fetch m.league where m.user.id = :userId order by lower(m.league.name), m.league.id")
    List<LeagueMember> findMembershipsOfUser(Long userId);

    long countByLeagueIdAndRole(Long leagueId, LeagueRole role);

    boolean existsByLeagueIdAndDisplayNameIgnoreCase(Long leagueId, String displayName);

    /** [league id, member count] rows, placeholders included. */
    @Query("select m.league.id, count(m) from LeagueMember m where m.league.id in :leagueIds group by m.league.id")
    List<Object[]> countByLeague(Collection<Long> leagueIds);
}
