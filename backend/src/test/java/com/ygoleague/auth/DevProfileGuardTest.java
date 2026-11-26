package com.ygoleague.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DevProfileGuardTest {

    @Test
    void allowsLocalDatabases() {
        assertDoesNotThrow(() -> DevProfileGuard.check(false, "jdbc:postgresql://localhost:5432/ygoleague"));
        assertDoesNotThrow(() -> DevProfileGuard.check(false, "jdbc:postgresql://127.0.0.1:55001/test?loggerLevel=OFF"));
    }

    @Test
    void refusesProdProfileAndRemoteOrUnreadableUrls() {
        assertThrows(IllegalStateException.class, () -> DevProfileGuard.check(true, "jdbc:postgresql://localhost/x"));
        assertThrows(IllegalStateException.class, () -> DevProfileGuard.check(false, "jdbc:postgresql://db.example.com:5432/x"));
        assertThrows(IllegalStateException.class, () -> DevProfileGuard.check(false, "jdbc:postgresql://localhost.evil.com/x"));
        assertThrows(IllegalStateException.class, () -> DevProfileGuard.check(false, "garbage"));
        assertEquals("localhost", DevProfileGuard.host("jdbc:postgresql://LOCALHOST:5432/x"));
    }
}
