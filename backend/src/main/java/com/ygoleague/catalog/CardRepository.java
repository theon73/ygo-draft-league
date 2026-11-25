package com.ygoleague.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CardRepository extends JpaRepository<Card, Long> {

    Optional<Card> findByKonamiId(int konamiId);

    /** The card with this passcode on any of its artworks. Uses the GIN index on passcodes. */
    @Query(value = "select * from card where passcodes @> array[cast(:passcode as bigint)]", nativeQuery = true)
    Optional<Card> findByPasscode(long passcode);

    /** Case-insensitive exact name match. Names are unique in practice but not by constraint. */
    @Query("select c from Card c where lower(c.name) = lower(:name)")
    List<Card> findByNameIgnoreCase(String name);
}
