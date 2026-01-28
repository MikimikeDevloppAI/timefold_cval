package com.scheduler.domain;

import java.util.UUID;

/**
 * Represents a staff member's skill with preference level.
 */
public class StaffSkill {

    private UUID staffId;
    private UUID skillId;
    private int preference; // 1=P1 (best), 2=P2, 3=P3, 4=P4

    public StaffSkill() {}

    public StaffSkill(UUID staffId, UUID skillId, int preference) {
        this.staffId = staffId;
        this.skillId = skillId;
        this.preference = preference;
    }

    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }

    public UUID getSkillId() { return skillId; }
    public void setSkillId(UUID skillId) { this.skillId = skillId; }

    public int getPreference() { return preference; }
    public void setPreference(int preference) { this.preference = preference; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StaffSkill that = (StaffSkill) o;
        return staffId.equals(that.staffId) && skillId.equals(that.skillId);
    }

    @Override
    public int hashCode() {
        return 31 * staffId.hashCode() + skillId.hashCode();
    }
}
