package com.ygoleague.banlist;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BanListRevisionRepository extends JpaRepository<BanListRevision, Long> {

    Optional<BanListRevision> findFirstByBanListIdOrderByRevisionDesc(Long banListId);
}
