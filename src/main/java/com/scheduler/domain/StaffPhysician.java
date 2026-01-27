package com.scheduler.domain;

import java.util.UUID;

/**
 * Represents a staff member's preferred physician with priority level.
 */
public class StaffPhysician {

    private UUID staffId;
    private UUID physicianId;
    private int priority; // 1=P1 (best), 2=P2, 3=P3

    public StaffPhysician() {}

    public StaffPhysician(UUID staffId, UUID physicianId, int priority) {
        this.staffId = staffId;
        this.physicianId = physicianId;
        this.priority = priority;
    }

    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }

    public UUID getPhysicianId() { return physicianId; }
    public void setPhysicianId(UUID physicianId) { this.physicianId = physicianId; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StaffPhysician that = (StaffPhysician) o;
        return staffId.equals(that.staffId) && physicianId.equals(that.physicianId);
    }

    @Override
    public int hashCode() {
        return 31 * staffId.hashCode() + physicianId.hashCode();
    }
}
