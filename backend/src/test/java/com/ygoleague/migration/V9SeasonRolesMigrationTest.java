package com.ygoleague.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V9 rewrites real data (league roles become owner/member, other commissioners become season
 * commissioners), so it gets tested against data written in the V8 shape. Plain Flyway + JDBC,
 * no Spring context.
 */
@Testcontainers
class V9SeasonRolesMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Test
    void ownersMembersAndSeasonCommissionersComeOutRight() {
        DriverManagerDataSource ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).target("8").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        long yugi = user(jdbc, "Yugi"), kaiba = user(jdbc, "Kaiba"), joey = user(jdbc, "Joey"), mai = user(jdbc, "Mai");
        // A: Yugi created it; Kaiba is a co-commissioner; Joey plays; Mai views. One existing season.
        long a = league(jdbc, "A", yugi);
        member(jdbc, a, yugi, "Yugi", "COMMISSIONER");
        member(jdbc, a, kaiba, "Kaiba", "COMMISSIONER");
        member(jdbc, a, joey, "Joey", "PLAYER");
        member(jdbc, a, mai, "Mai", "VIEWER");
        member(jdbc, a, null, "Tea (placeholder)", "PLAYER");
        long season = jdbc.queryForObject("insert into season (league_id, name) values (?, 'S1') returning id", Long.class, a);
        jdbc.update("insert into league_invite (league_id, code, role, created_by) values (?, 'open1', 'VIEWER', ?)", a, yugi);
        // B: Joey created it, then handed commissioner to Mai and demoted himself. He's still the owner.
        long b = league(jdbc, "B", joey);
        member(jdbc, b, joey, "Joey", "PLAYER");
        member(jdbc, b, mai, "Mai", "COMMISSIONER");
        // C: Kaiba created it and later left; the longest-standing commissioner (Mai) becomes owner.
        long c = league(jdbc, "C", kaiba);
        member(jdbc, c, joey, "Joey", "PLAYER");
        member(jdbc, c, mai, "Mai", "COMMISSIONER");
        member(jdbc, c, yugi, "Yugi", "COMMISSIONER");

        Flyway.configure().dataSource(ds).load().migrate();

        assertEquals(Map.of("Yugi", "OWNER", "Kaiba", "MEMBER", "Joey", "MEMBER", "Mai", "MEMBER", "Tea (placeholder)", "MEMBER"), roles(jdbc, a));
        assertEquals(Map.of("Joey", "OWNER", "Mai", "MEMBER"), roles(jdbc, b));
        assertEquals(Map.of("Mai", "OWNER", "Yugi", "MEMBER", "Joey", "MEMBER"), roles(jdbc, c));

        // Kaiba keeps commissioning A's existing season; the owner is implicit, so isn't listed.
        assertEquals(List.of("Kaiba"), jdbc.queryForList("""
                select m.display_name from season_commissioner sc join league_member m on m.id = sc.league_member_id
                where sc.season_id = ?""", String.class, season));
        assertEquals("MEMBER", jdbc.queryForObject("select role from league_invite where code = 'open1'", String.class));

        // At most one owner per league from now on.
        assertThrows(Exception.class, () -> jdbc.update("update league_member set role = 'OWNER' where league_id = ? and display_name = 'Kaiba'", a));
    }

    private static long user(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("insert into app_user (display_name) values (?) returning id", Long.class, name);
    }

    private static long league(JdbcTemplate jdbc, String name, long createdBy) {
        return jdbc.queryForObject("insert into league (name, created_by) values (?, ?) returning id", Long.class, name, createdBy);
    }

    private static void member(JdbcTemplate jdbc, long league, Long user, String name, String role) {
        jdbc.update("insert into league_member (league_id, user_id, display_name, role) values (?, ?, ?, ?)", league, user, name, role);
    }

    private static Map<String, String> roles(JdbcTemplate jdbc, long league) {
        Map<String, String> roles = new java.util.HashMap<>();
        jdbc.query("select display_name, role from league_member where league_id = ?",
                rs -> { roles.put(rs.getString(1), rs.getString(2)); }, league);
        return roles;
    }
}
