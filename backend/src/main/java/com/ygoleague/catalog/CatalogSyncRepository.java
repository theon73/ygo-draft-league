package com.ygoleague.catalog;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogSyncRepository extends JpaRepository<CatalogSync, Long> {

    Optional<CatalogSync> findFirstByStatusOrderByStartedAtDesc(SyncStatus status);
}
