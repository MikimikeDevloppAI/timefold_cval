package com.scheduler.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Represents an assignment of a staff member to a shift.
 *
 * INVERTED MODEL:
 * - Staff is FIXED (pre-assigned for each available staff/date/period)
 * - Shift is the @PlanningVariable (Timefold chooses which shift to assign)
 *
 * This guarantees that every available staff member MUST be assigned to a shift.
 * If no consultation/surgical shift is available, the staff will be assigned to Admin.
 */
@PlanningEntity(difficultyComparatorClass = StaffAssignmentDifficultyComparator.class)
public class StaffAssignment {

    @PlanningId
    private UUID id;

    // The staff member this assignment is for (FIXED - not a planning variable)
    private Staff staff;

    // The date and period for this assignment (FIXED)
    private LocalDate date;
    private int periodId; // 1=morning, 2=afternoon

    // The assigned shift (PLANNING VARIABLE - Timefold changes this)
    // Uses entity-specific value range (validShiftRange) filtered by date/period/skill/site
    // nullable=false : REST is a synthetic shift (not null) - forces CH to assign something
    @PlanningVariable(valueRangeProviderRefs = "validShiftRange", nullable = false)
    private Shift shift;

    // Pre-filtered shifts valid for this staff/date/period (skill + site + date/period match)
    // This dramatically reduces the search space from ~90 to ~10-20 shifts per assignment
    private List<Shift> validShifts;

    // Closing roles (can be assigned independently of the shift's closing_role)
    private boolean closing1r;
    private boolean closing2f;
    private boolean closing3f;

    // Reference to the other half-day (AM↔PM) for the same staff/date
    // Allows O(1) constraint checks instead of expensive JOINs
    private StaffAssignment otherHalfDay;

    public StaffAssignment() {
        this.id = UUID.randomUUID();
    }

    public StaffAssignment(Staff staff, LocalDate date, int periodId) {
        this.id = UUID.randomUUID();
        this.staff = staff;
        this.date = date;
        this.periodId = periodId;
    }

    // Convenience getters through shift (when assigned)
    public UUID getLocationId() {
        return shift != null ? shift.getLocationId() : null;
    }

    public UUID getSkillId() {
        return shift != null ? shift.getSkillId() : null;
    }

    public Location getLocation() {
        return shift != null ? shift.getLocation() : null;
    }

    public UUID getSiteId() {
        return shift != null ? shift.getSiteId() : null;
    }

    // Check if this assignment violates skill eligibility
    // Returns true if valid (REST, admin, or staff has the skill)
    public boolean hasValidSkill() {
        if (shift == null) return true; // No shift assigned yet
        if (shift.isRest()) return true; // REST has no skill requirement
        if (shift.isAdmin()) return true; // Admin is always valid for anyone
        if (shift.isClosingShift()) return true; // Closing is always valid for anyone
        return staff.hasSkill(shift.getSkillId());
    }

    // Check if this assignment violates site eligibility
    // Returns true if valid (REST, admin, or staff can work at site)
    public boolean canWorkAtSite() {
        if (shift == null) return true; // No shift assigned yet
        if (shift.isRest()) return true; // REST has no site restriction
        if (shift.isAdmin()) return true; // Admin has no site restriction
        if (shift.isClosingShift()) return true; // Closing uses the same site as the staff's work shift
        UUID siteId = shift.getSiteId();
        return siteId == null || staff.canWorkAtSite(siteId);
    }

    // Get skill preference for scoring (lower is better, 1 is best)
    public int getSkillPreference() {
        if (staff == null || shift == null) return 0;
        if (shift.isAdmin()) return 0; // Admin has no skill preference
        if (shift.isClosingShift()) return 0; // Closing has no skill preference
        return staff.getSkillPreference(shift.getSkillId());
    }

    // Get site preference for scoring (lower is better, 1 is best)
    public int getSitePreference() {
        if (staff == null || shift == null) return 0;
        if (shift.isAdmin()) return 0;
        if (shift.isClosingShift()) return 0;
        UUID siteId = shift.getSiteId();
        if (siteId == null) return 0;
        return staff.getSitePriority(siteId);
    }

    // Check if this is an admin assignment
    public boolean isAdminAssignment() {
        return shift != null && shift.isAdmin();
    }

    // Check if this is a closing assignment
    public boolean isClosingAssignment() {
        return shift != null && shift.isClosingShift();
    }

    // Check if staff is working this slot (shift is not REST)
    public boolean isWorking() {
        return shift != null && !shift.isRest();
    }

    // Check if staff is resting this slot (shift is REST, only valid for flexible)
    public boolean isResting() {
        return shift != null && shift.isRest();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Staff getStaff() { return staff; }
    public void setStaff(Staff staff) { this.staff = staff; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public int getPeriodId() { return periodId; }
    public void setPeriodId(int periodId) { this.periodId = periodId; }

    public Shift getShift() { return shift; }
    public void setShift(Shift shift) { this.shift = shift; }

    public boolean isClosing1r() { return closing1r; }
    public void setClosing1r(boolean closing1r) { this.closing1r = closing1r; }

    public boolean isClosing2f() { return closing2f; }
    public void setClosing2f(boolean closing2f) { this.closing2f = closing2f; }

    public boolean isClosing3f() { return closing3f; }
    public void setClosing3f(boolean closing3f) { this.closing3f = closing3f; }

    public StaffAssignment getOtherHalfDay() { return otherHalfDay; }
    public void setOtherHalfDay(StaffAssignment otherHalfDay) { this.otherHalfDay = otherHalfDay; }

    // Entity-specific value range provider - only valid shifts for this staff/date/period
    // Filtered by: date + period + skill + site + (REST only for flexible)
    @ValueRangeProvider(id = "validShiftRange")
    public List<Shift> getValidShifts() { return validShifts; }
    public void setValidShifts(List<Shift> validShifts) { this.validShifts = validShifts; }

    public String getPeriodName() {
        return periodId == 1 ? "morning" : "afternoon";
    }

    @Override
    public String toString() {
        String staffName = staff != null ? staff.getFullName() : "no-staff";
        String shiftInfo = shift != null ? shift.toString() : "REST";
        return "Assignment{" + staffName + " on " + date + " " + getPeriodName() + " -> " + shiftInfo + "}";
    }
}
