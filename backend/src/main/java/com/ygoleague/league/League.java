package com.ygoleague.league;

import com.ygoleague.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import com.ygoleague.calendar.WeekBoundary;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * A league: the tenant everything else hangs off. Visible to its members only. URLs identify it
 * by an opaque public id derived from {@code id} (see {@code common.PublicIds}).
 */
@Entity
@Table(name = "league")
public class League {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** IANA zone id; week boundaries, deadlines and draft times are in this zone. */
    @Column(name = "time_zone", nullable = false)
    private String timeZone = "UTC";

    /** When each league week starts, in {@link #timeZone} (see calendar.WeekBoundary). */
    @Enumerated(EnumType.STRING)
    @Column(name = "week_starts_on", nullable = false)
    private DayOfWeek weekStartsOn = DayOfWeek.MONDAY;

    @Column(name = "week_starts_at", nullable = false)
    private LocalTime weekStartsAt = LocalTime.MIDNIGHT;

    protected League() {}

    public League(String name, AppUser createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public AppUser getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    public String getTimeZone() { return timeZone; }
    public DayOfWeek getWeekStartsOn() { return weekStartsOn; }
    public LocalTime getWeekStartsAt() { return weekStartsAt; }

    public WeekBoundary weekBoundary() {
        return new WeekBoundary(ZoneId.of(timeZone), weekStartsOn, weekStartsAt);
    }

    public void setName(String name) { this.name = name; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public void setWeekStart(DayOfWeek day, LocalTime time) { this.weekStartsOn = day; this.weekStartsAt = time; }
}
