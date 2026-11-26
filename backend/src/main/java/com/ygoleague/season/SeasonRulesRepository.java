package com.ygoleague.season;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeasonRulesRepository extends JpaRepository<SeasonRules, Long> {

    /** Rules in effect for a week: the highest version that has taken effect by then. */
    Optional<SeasonRules> findFirstBySeasonIdAndEffectiveFromWeekLessThanEqualOrderByVersionDesc(
            Long seasonId, int week);

    /** The latest version saved before a moment (undoing an old draft start, YGO-19). */
    Optional<SeasonRules> findFirstBySeasonIdAndCreatedAtBeforeOrderByVersionDesc(Long seasonId, java.time.Instant before);

    /** Latest version regardless of week; used to number and validate the next version. */
    Optional<SeasonRules> findFirstBySeasonIdOrderByVersionDesc(Long seasonId);
}
