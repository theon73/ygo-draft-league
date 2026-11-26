package com.ygoleague.catalog;

/** Published after a catalog sync succeeds, so dependents (e.g. ban list import) can refresh. */
public record CatalogSyncedEvent(CatalogSync run) {}
