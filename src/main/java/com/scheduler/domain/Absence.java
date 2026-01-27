package com.scheduler.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a staff absence period.
 */
public class Absence {

    private UUID id;
    private UUID userId;
    private LocalDate date;
    private Integer periodId; // null = full day, 1 = morning, 2 = afternoon

    public Absence() {}

    public Absence(UUID userId, LocalDate date, Integer periodId) {
        this.userId = userId;
        this.date = date;
        this.periodId = periodId;
    }

    // Check if this absence covers a specific period
    public boolean coversDate(LocalDate checkDate, int checkPeriodId) {
        if (!date.equals(checkDate)) return false;
        // null periodId means full day absence
        return periodId == null || periodId == checkPeriodId;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public Integer getPeriodId() { return periodId; }
    public void setPeriodId(Integer periodId) { this.periodId = periodId; }
}
