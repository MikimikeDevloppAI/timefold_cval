package com.scheduler.domain;

import java.util.UUID;

/**
 * Represents a location (consultation room, surgical block, etc.)
 */
public class Location {

    private UUID id;
    private UUID siteId;
    private UUID specialtyId;
    private String name;
    private String staffingType; // A, B, C, D
    private boolean hasClosing;
    private String distanceType; // 'reference' or 'distant'

    public Location() {}

    public Location(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean isDistant() {
        return "distant".equals(distanceType);
    }

    public boolean isSurgical() {
        return "C".equals(staffingType);
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }

    public UUID getSpecialtyId() { return specialtyId; }
    public void setSpecialtyId(UUID specialtyId) { this.specialtyId = specialtyId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStaffingType() { return staffingType; }
    public void setStaffingType(String staffingType) { this.staffingType = staffingType; }

    public boolean isHasClosing() { return hasClosing; }
    public void setHasClosing(boolean hasClosing) { this.hasClosing = hasClosing; }

    public String getDistanceType() { return distanceType; }
    public void setDistanceType(String distanceType) { this.distanceType = distanceType; }

    @Override
    public String toString() {
        return "Location{" + name + " [" + id + "]}";
    }
}
