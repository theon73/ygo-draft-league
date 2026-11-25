package com.ygoleague.league;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeagueInviteRepository extends JpaRepository<LeagueInvite, Long> {

    Optional<LeagueInvite> findByCode(String code);
}
