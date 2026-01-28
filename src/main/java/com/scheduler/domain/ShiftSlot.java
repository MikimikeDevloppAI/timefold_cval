package com.scheduler.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import ai.timefold.solver.core.api.domain.variable.ShadowVariable;
import com.scheduler.solver.FullDayWorkListener;
import com.scheduler.solver.WorkDayCountListener;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a single staffing slot to be filled.
 *
 * NEW MODEL: 1 slot = 1 unit of coverage
 * If a shift needs 3 people, we create 3 ShiftSlots.
 * This makes capacity structural (no H9b constraint needed).
 *
 * The solver chooses which Staff to assign to each slot.
 * A slot can be unassigned (allowsUnassigned=true) in overconstrained scenarios.
 */
@PlanningEntity
public class ShiftSlot {

    @PlanningId
    private UUID id;

    // Problem facts (FIXED - define what needs to be covered)
    private Shift shift;        // The parent shift (contains location, date, period, skill, needType)
    private int slotIndex;      // 0, 1, 2... to distinguish slots of the same shift

    // Planning variable - the solver chooses which staff member
    // allowsUnassigned=true : null means "slot not filled" (overconstrained)
    @PlanningVariable(valueRangeProviderRefs = "staffRange", allowsUnassigned = true)
    private Staff staff;

    // NOTE: closingRole removed - closing responsibilities are now handled via ClosingAssignment entity

    // SHADOW VARIABLE 1: Number of distinct days this staff works
    // Used for flexible staff max days constraint
    @ShadowVariable(variableListenerClass = WorkDayCountListener.class,
                    sourceEntityClass = ShiftSlot.class,
                    sourceVariableName = "staff")
    private Integer staffWorkDayCount;

    // SHADOW VARIABLE 2: Is this staff working full day (AM + PM) on this date?
    // Used for flexible staff full day constraint
    @ShadowVariable(variableListenerClass = FullDayWorkListener.class,
                    sourceEntityClass = ShiftSlot.class,
                    sourceVariableName = "staff")
    private Boolean isWorkingFullDay;

    public ShiftSlot() {
        this.id = UUID.randomUUID();
    }

    public ShiftSlot(Shift shift, int slotIndex) {
        this.id = UUID.randomUUID();
        this.shift = shift;
        this.slotIndex = slotIndex;
    }

    // ========== Convenience getters (delegate to shift) ==========

    public LocalDate getDate() {
        return shift != null ? shift.getDate() : null;
    }

    public int getPeriodId() {
        return shift != null ? shift.getPeriodId() : 0;
    }

    public String getPeriodName() {
        return shift != null ? shift.getPeriodName() : "unknown";
    }

    public UUID getLocationId() {
        return shift != null ? shift.getLocationId() : null;
    }

    public String getLocationName() {
        return shift != null ? shift.getLocationName() : null;
    }

    public UUID getSiteId() {
        return shift != null ? shift.getSiteId() : null;
    }

    public String getSiteName() {
        return shift != null ? shift.getSiteName() : null;
    }

    public UUID getSkillId() {
        return shift != null ? shift.getSkillId() : null;
    }

    public String getSkillName() {
        return shift != null ? shift.getSkillName() : null;
    }

    public String getNeedType() {
        return shift != null ? shift.getNeedType() : null;
    }

    public boolean isSurgical() {
        return "surgical".equals(getNeedType());
    }

    public boolean isConsultation() {
        return "consultation".equals(getNeedType());
    }

    public boolean isAdmin() {
        return shift != null && shift.isAdmin();
    }

    public boolean isRest() {
        return shift != null && shift.isRest();
    }

    // NOTE: hasClosingRole(), getClosingRole(), setClosingRole() removed
    // Closing responsibilities are now handled via ClosingAssignment entity

    /**
     * Check if this slot is eligible for closing responsibility.
     * Only consultation slots at locations with needs1r/needs2f can have closing.
     */
    public boolean isEligibleForClosing() {
        return shift != null && (shift.isNeeds1r() || shift.isNeeds2f());
    }

    /**
     * Check if this is a closing shift (for backward compatibility).
     * Actual closing assignments are now via ClosingAssignment entity.
     */
    public boolean isClosing() {
        return shift != null && shift.isClosingShift();
    }

    public boolean isFullDay() {
        return getPeriodId() == 0;
    }

    // ========== Assignment helpers ==========

    /**
     * Check if this slot is assigned (has a staff member).
     */
    public boolean isAssigned() {
        return staff != null;
    }

    /**
     * Check if staff has the required skill for this slot.
     * Returns true if valid (admin, rest, or staff has skill).
     */
    public boolean hasValidSkill() {
        if (staff == null) return true; // Unassigned is OK
        if (isAdmin() || isRest()) return true; // No skill requirement
        return staff.hasSkill(getSkillId());
    }

    /**
     * Check if staff can work at the site of this slot.
     * Returns true if valid (admin, rest, or staff can work at site).
     */
    public boolean canWorkAtSite() {
        if (staff == null) return true; // Unassigned is OK
        if (isAdmin() || isRest()) return true; // No site restriction
        UUID siteId = getSiteId();
        return siteId == null || staff.canWorkAtSite(siteId);
    }

    /**
     * Check if staff is available on this day and period.
     */
    public boolean staffIsAvailable() {
        if (staff == null) return true; // Unassigned is OK
        if (getDate() == null) return true;
        int dayOfWeek = getDate().getDayOfWeek().getValue(); // 1=Monday, 7=Sunday
        return staff.isAvailable(dayOfWeek, getPeriodId());
    }

    /**
     * Get skill preference for scoring (lower is better, 1 is best).
     */
    public int getSkillPreference() {
        if (staff == null || getSkillId() == null) return 0;
        return staff.getSkillPreference(getSkillId());
    }

    /**
     * Get site priority for scoring (lower is better, 1 is best).
     */
    public int getSitePriority() {
        if (staff == null || getSiteId() == null) return 0;
        return staff.getSitePriority(getSiteId());
    }

    // ========== Getters and Setters ==========

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Shift getShift() {
        return shift;
    }

    public void setShift(Shift shift) {
        this.shift = shift;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public void setSlotIndex(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public Staff getStaff() {
        return staff;
    }

    public void setStaff(Staff staff) {
        this.staff = staff;
    }

    public Integer getStaffWorkDayCount() {
        return staffWorkDayCount;
    }

    public void setStaffWorkDayCount(Integer staffWorkDayCount) {
        this.staffWorkDayCount = staffWorkDayCount;
    }

    public Boolean getIsWorkingFullDay() {
        return isWorkingFullDay;
    }

    public void setIsWorkingFullDay(Boolean isWorkingFullDay) {
        this.isWorkingFullDay = isWorkingFullDay;
    }

    @Override
    public String toString() {
        String staffName = staff != null ? staff.getFullName() : "UNASSIGNED";
        String shiftInfo = shift != null ?
            (getLocationName() + " " + getDate() + " " + getPeriodName() + " [" + getNeedType() + "]") :
            "no-shift";
        return "ShiftSlot{" + shiftInfo + " #" + slotIndex + " -> " + staffName + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShiftSlot that = (ShiftSlot) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
