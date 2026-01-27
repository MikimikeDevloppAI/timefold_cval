package com.scheduler.domain;

import java.util.UUID;

/**
 * Represents a staff member's recurring availability for a specific day and period.
 * Maps to staff_recurring_schedules table.
 */
public class StaffAvailability {

    private UUID staffId;
    private int dayOfWeek;  // 1=Monday, 2=Tuesday, ..., 7=Sunday
    private int periodId;   // 1=AM, 2=PM

    public StaffAvailability() {}

    public StaffAvailability(UUID staffId, int dayOfWeek, int periodId) {
        this.staffId = staffId;
        this.dayOfWeek = dayOfWeek;
        this.periodId = periodId;
    }

    // Getters and Setters
    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }

    public int getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(int dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public int getPeriodId() { return periodId; }
    public void setPeriodId(int periodId) { this.periodId = periodId; }

    @Override
    public String toString() {
        return "Availability{day=" + dayOfWeek + ", period=" + periodId + "}";
    }
}
