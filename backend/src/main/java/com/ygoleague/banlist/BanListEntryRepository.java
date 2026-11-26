package com.ygoleague.banlist;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BanListEntryRepository extends JpaRepository<BanListEntry, BanListEntry.Key> {

    List<BanListEntry> findByRevisionId(Long revisionId);
}
