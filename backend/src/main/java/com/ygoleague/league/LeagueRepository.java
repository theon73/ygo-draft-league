package com.ygoleague.league;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LeagueRepository extends JpaRepository<League, Long> {

    /** Leagues the user belongs to in any role ("My leagues"). */
    @Query("select m.league from LeagueMember m where m.user.id = :userId order by m.league.name")
    List<League> findAllForUser(Long userId);
}
