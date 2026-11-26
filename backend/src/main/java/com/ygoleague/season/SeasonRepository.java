package com.ygoleague.season;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeasonRepository extends JpaRepository<Season, Long> {

    List<Season> findByLeagueIdOrderByCreatedAtDesc(Long leagueId);

    boolean existsByLeagueIdAndNameIgnoreCase(Long leagueId, String name);
}
