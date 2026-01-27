package com.scheduler.domain;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a closing responsibility assignment (1R, 2F, 3F) at a location for a day.
 *
 * The closing role is an ADDITIONAL RESPONSIBILITY that a staff member takes on
 * while working their regular consultation shift. The same staff must work at
 * the location for BOTH AM and PM to be assigned a closing role.
 *
 * This is a PlanningEntity - the solver chooses which staff member gets assigned
 * each closing role, subject to constraints (must work at location, 1R != 2F, etc.)
 */
@PlanningEntity
public class ClosingAssignment {

    @PlanningId
    private UUID id;

    // Problem facts (FIXED - define what closing responsibility needs to be filled)
    private UUID locationId;
    private String locationName;
    private LocalDate date;
    private ClosingRole role;  // 1R, 2F, or 3F

    // Planning variable - the solver chooses which staff member
    @PlanningVariable(valueRangeProviderRefs = "staffRange")
    private Staff staff;

    public ClosingAssignment() {
        this.id = UUID.randomUUID();
    }

    public ClosingAssignment(UUID locationId, String locationName, LocalDate date, ClosingRole role) {
        this.id = UUID.randomUUID();
        this.locationId = locationId;
        this.locationName = locationName;
        this.date = date;
        this.role = role;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public String getLocationName() {
        return locationName;
    }

    public void setLocationName(String locationName) {
        this.locationName = locationName;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public ClosingRole getRole() {
        return role;
    }

    public void setRole(ClosingRole role) {
        this.role = role;
    }

    public Staff getStaff() {
        return staff;
    }

    public void setStaff(Staff staff) {
        this.staff = staff;
    }

    @Override
    public String toString() {
        String staffName = staff != null ? staff.getFullName() : "unassigned";
        return "ClosingAssignment{" + role.getDisplayName() +
               " @ " + locationName +
               " on " + date +
               " -> " + staffName + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClosingAssignment that = (ClosingAssignment) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
