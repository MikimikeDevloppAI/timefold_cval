package com.scheduler.domain;

import java.util.UUID;

/**
 * Represents a staff member's site preference with priority level.
 */
public class StaffSite {

    private UUID staffId;
    private UUID siteId;
    private int priority; // 1=P1 (best), 2=P2, 3=P3, 4=P4

    public StaffSite() {}

    public StaffSite(UUID staffId, UUID siteId, int priority) {
        this.staffId = staffId;
        this.siteId = siteId;
        this.priority = priority;
    }

    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }

    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StaffSite that = (StaffSite) o;
        return staffId.equals(that.staffId) && siteId.equals(that.siteId);
    }

    @Override
    public int hashCode() {
        return 31 * staffId.hashCode() + siteId.hashCode();
    }
}
