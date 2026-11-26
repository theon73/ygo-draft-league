package com.ygoleague.season;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeasonPlayerRepository extends JpaRepository<SeasonPlayer, Long> {

    List<SeasonPlayer> findBySeasonIdOrderBySeat(Long seasonId);

    /** Whether the member has played in any season (then they can't be removed from the league). */
    boolean existsByMemberId(Long memberId);
}
