package com.scheduler.domain;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The main planning solution containing all data and assignments.
 *
 * MODEL:
 * - ShiftSlot: 1 slot = 1 unit of coverage (staff @PlanningVariable)
 * - Closing roles are attributes on Shift (problem fact), not separate entities
 */
@PlanningSolution
public class ScheduleSolution {

    // Problem facts (input data - not changed by solver)
    @ProblemFactCollectionProperty
    private List<Staff> staffList = new ArrayList<>();

    // Shifts define what needs to be covered (parent of ShiftSlot)
    @ProblemFactCollectionProperty
    private List<Shift> shifts = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Location> locations = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Absence> absences = new ArrayList<>();

    // ShiftSlots - 1 slot = 1 unit of coverage
    // Each slot can be assigned to one staff member (or left unassigned if overconstrained)
    @PlanningEntityCollectionProperty
    private List<ShiftSlot> shiftSlots = new ArrayList<>();

    // NOTE: ClosingAssignment entity removed - closing roles are now attributes on Shift/ShiftSlot

    // Value range for ShiftSlot.staff
    // Note: Le filtrage des candidats invalides est fait via ShiftSlotChangeMoveFilter
    @ValueRangeProvider(id = "staffRange")
    public List<Staff> getStaffRange() {
        return staffList;
    }

    // NOTE: closingRoleRange removed - closing roles are attributes on Shift

    // Score
    @PlanningScore
    private HardMediumSoftScore score;

    // Lookup maps (for constraint checks)
    private Map<UUID, Location> locationMap = new HashMap<>();
    private Map<UUID, Staff> staffByUserId = new HashMap<>();

    public ScheduleSolution() {}

    // Initialize lookup maps after loading data
    public void initializeMaps() {
        locationMap.clear();
        for (Location loc : locations) {
            locationMap.put(loc.getId(), loc);
        }
        staffByUserId.clear();
        for (Staff s : staffList) {
            if (s.getUserId() != null) {
                staffByUserId.put(s.getUserId(), s);
            }
        }
        // Link locations to shifts
        for (Shift shift : shifts) {
            shift.setLocation(locationMap.get(shift.getLocationId()));
        }
    }

    public Location getLocationById(UUID id) {
        return locationMap.get(id);
    }

    public Staff getStaffByUserId(UUID userId) {
        return staffByUserId.get(userId);
    }

    // Check if a staff member is absent on a given date/period
    public boolean isStaffAbsent(Staff staff, java.time.LocalDate date, int periodId) {
        if (staff == null || staff.getUserId() == null) return false;
        return absences.stream()
            .anyMatch(a -> a.getUserId().equals(staff.getUserId()) && a.coversDate(date, periodId));
    }

    // Getters and Setters
    public List<Staff> getStaffList() { return staffList; }
    public void setStaffList(List<Staff> staffList) { this.staffList = staffList; }

    public List<Shift> getShifts() { return shifts; }
    public void setShifts(List<Shift> shifts) { this.shifts = shifts; }

    public List<Location> getLocations() { return locations; }
    public void setLocations(List<Location> locations) { this.locations = locations; }

    public List<Absence> getAbsences() { return absences; }
    public void setAbsences(List<Absence> absences) { this.absences = absences; }

    public List<ShiftSlot> getShiftSlots() { return shiftSlots; }
    public void setShiftSlots(List<ShiftSlot> shiftSlots) { this.shiftSlots = shiftSlots; }

    /**
     * Get closing slots (ShiftSlots with a closing role) for convenience.
     */
    public List<ShiftSlot> getClosingSlots() {
        return shiftSlots.stream()
            .filter(ShiftSlot::hasClosingRole)
            .toList();
    }

    public HardMediumSoftScore getScore() { return score; }
    public void setScore(HardMediumSoftScore score) { this.score = score; }
}
