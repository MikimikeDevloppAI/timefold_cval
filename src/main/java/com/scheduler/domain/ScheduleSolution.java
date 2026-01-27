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
 * INVERTED MODEL:
 * - Staff is fixed in StaffAssignment (one assignment per available staff/date/period)
 * - Shift is the @PlanningVariable (Timefold chooses which shift to assign)
 * - @ValueRangeProvider is on shifts (the pool of possible shifts)
 */
@PlanningSolution
public class ScheduleSolution {

    // Problem facts (input data - not changed by solver)
    @ProblemFactCollectionProperty
    private List<Staff> staffList = new ArrayList<>();

    // Shifts are the value range for StaffAssignment.shift
    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "shiftRange")
    private List<Shift> shifts = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Location> locations = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<Absence> absences = new ArrayList<>();

    // Planning entities (assignments to be optimized)
    @PlanningEntityCollectionProperty
    private List<StaffAssignment> assignments = new ArrayList<>();

    // Closing assignments - planning entities for closing responsibilities (1R, 2F)
    // The solver assigns staff members to these closing roles
    @PlanningEntityCollectionProperty
    private List<ClosingAssignment> closingAssignments = new ArrayList<>();

    // Value range for ClosingAssignment.staff (reuses staffList)
    @ValueRangeProvider(id = "staffRange")
    public List<Staff> getStaffRange() {
        return staffList;
    }

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

    public List<StaffAssignment> getAssignments() { return assignments; }
    public void setAssignments(List<StaffAssignment> assignments) { this.assignments = assignments; }

    public List<ClosingAssignment> getClosingAssignments() { return closingAssignments; }
    public void setClosingAssignments(List<ClosingAssignment> closingAssignments) { this.closingAssignments = closingAssignments; }

    public HardMediumSoftScore getScore() { return score; }
    public void setScore(HardMediumSoftScore score) { this.score = score; }
}
