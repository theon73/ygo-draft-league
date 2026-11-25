package com.ygoleague.season;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeasonPlayerRepository extends JpaRepository<SeasonPlayer, Long> {

    List<SeasonPlayer> findBySeasonIdOrderBySeat(Long seasonId);
}
