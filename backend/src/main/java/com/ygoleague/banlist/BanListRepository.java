package com.ygoleague.banlist;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BanListRepository extends JpaRepository<BanList, Long> {

    Optional<BanList> findByOfficialKey(String officialKey);

    List<BanList> findByLeagueIsNullOrderByEffectiveDateDesc();

    List<BanList> findByLeagueIdOrderByName(Long leagueId);
}
