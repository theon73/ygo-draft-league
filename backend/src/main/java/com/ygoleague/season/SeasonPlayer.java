package com.ygoleague.season;

import com.ygoleague.league.LeagueMember;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * A league member taking part in one season. {@code leagueId} duplicates the season's league so
 * the database can enforce (via composite foreign keys) that the member is from the same league.
 */
@Entity
@Table(name = "season_player")
public class SeasonPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "season_id")
    private Season season;

    @Column(name = "league_id", nullable = false, updatable = false)
    private Long leagueId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "league_member_id")
    private LeagueMember member;

    /** 1-based draft seat; null until seats are assigned. */
    private Integer seat;

    @Column(name = "team_name")
    private String teamName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SeasonPlayer() {}

    public SeasonPlayer(Season season, LeagueMember member) {
        Long seasonLeague = season.getLeague().getId();
        if (!Objects.equals(seasonLeague, member.getLeague().getId())) {
            throw new IllegalArgumentException("Member belongs to a different league than the season");
        }
        this.season = season;
        this.leagueId = seasonLeague;
        this.member = member;
    }

    public Long getId() { return id; }
    public Season getSeason() { return season; }
    public Long getLeagueId() { return leagueId; }
    public LeagueMember getMember() { return member; }
    public Integer getSeat() { return seat; }
    public String getTeamName() { return teamName; }
    public Instant getCreatedAt() { return createdAt; }

    public void setSeat(Integer seat) { this.seat = seat; }
    public void setTeamName(String teamName) { this.teamName = teamName; }
}
