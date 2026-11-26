package com.ygoleague.league;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface LeagueRepository extends JpaRepository<League, Long> {

    /** Leagues the user belongs to in any role ("My leagues"). */
    @Query("select m.league from LeagueMember m where m.user.id = :userId order by m.league.name")
    List<League> findAllForUser(Long userId);

    /**
     * Locks the league row. Every membership change takes this lock first, so concurrent changes
     * in one league run one at a time (e.g. two commissioners can't demote each other at once and
     * leave the league with none).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from League l where l.id = :id")
    Optional<League> lockById(Long id);
}
