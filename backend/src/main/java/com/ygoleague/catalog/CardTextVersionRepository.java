package com.ygoleague.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardTextVersionRepository extends JpaRepository<CardTextVersion, Long> {

    List<CardTextVersion> findByCardIdOrderByValidUntil(Long cardId);
}
