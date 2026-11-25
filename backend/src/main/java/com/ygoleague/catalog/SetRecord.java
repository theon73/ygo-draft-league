package com.ygoleague.catalog;

import java.time.LocalDate;

/** A TCG product as delivered by a catalog source. {@code name} is the natural key. */
public record SetRecord(String name, String code, LocalDate tcgDate, Integer cardCount) {}
