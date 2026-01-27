package com.scheduler.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a shift that needs to be filled.
 * One Shift = one (location, date, period, skill) combination that needs staffing.
 * This is a ProblemFact.
 *
 * needType can be: 'consultation', 'surgical', 'admin', 'closing'
 * closingRole can be: '1R', '2F', '3F' (or null if not a closing shift)
 * For closing shifts with period_id=0, it means full_day (both morning and afternoon)
 */
public class Shift {

    private UUID id; // Generated for identification
    private UUID locationId;
    private String locationName;
    private UUID siteId;
    private String siteName;
    private LocalDate date;
    private int periodId; // 1=morning, 2=afternoon, 0=full_day (for closing)
    private UUID skillId;
    private String skillName;
    private int quantityNeeded;
    private boolean hasClosing;

    // New fields for solver
    private String needType;    // 'consultation', 'surgical', 'admin'
    private boolean isAdmin;
    private String closingRole; // '1R', '2F', '3F' or null (legacy, kept for compatibility)

    // Closing needs flags (for consultation shifts that require closing responsibilities)
    // These indicate that staff assigned to this shift should also take on closing roles
    private boolean needs1r;
    private boolean needs2f;
    private boolean needs3f;
    private Boolean samePersonAllDay; // true = 1R/2F must be same person morning & afternoon

    // Transient reference to Location object
    private Location location;

    // Physicians present at this shift (location+date+period)
    private Set<UUID> physicianIds = new HashSet<>();

    // Physician names (comma-separated) for display
    private String physicianNames;

    public Shift() {
        this.id = UUID.randomUUID();
    }

    public Shift(UUID locationId, LocalDate date, int periodId, UUID skillId, int quantityNeeded) {
        this.id = UUID.randomUUID();
        this.locationId = locationId;
        this.date = date;
        this.periodId = periodId;
        this.skillId = skillId;
        this.quantityNeeded = quantityNeeded;
    }

    public String getPeriodName() {
        return periodId == 1 ? "morning" : "afternoon";
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public int getPeriodId() { return periodId; }
    public void setPeriodId(int periodId) { this.periodId = periodId; }

    public UUID getSkillId() { return skillId; }
    public void setSkillId(UUID skillId) { this.skillId = skillId; }

    public int getQuantityNeeded() { return quantityNeeded; }
    public void setQuantityNeeded(int quantityNeeded) { this.quantityNeeded = quantityNeeded; }

    public boolean isHasClosing() { return hasClosing; }
    public void setHasClosing(boolean hasClosing) { this.hasClosing = hasClosing; }

    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }

    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }

    public String getSiteName() { return siteName; }
    public void setSiteName(String siteName) { this.siteName = siteName; }

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }

    public String getNeedType() { return needType; }
    public void setNeedType(String needType) { this.needType = needType; }

    public boolean isAdmin() { return isAdmin; }
    public void setAdmin(boolean admin) { isAdmin = admin; }

    public String getClosingRole() { return closingRole; }
    public void setClosingRole(String closingRole) { this.closingRole = closingRole; }

    public boolean isNeeds1r() { return needs1r; }
    public void setNeeds1r(boolean needs1r) { this.needs1r = needs1r; }

    public boolean isNeeds2f() { return needs2f; }
    public void setNeeds2f(boolean needs2f) { this.needs2f = needs2f; }

    public boolean isNeeds3f() { return needs3f; }
    public void setNeeds3f(boolean needs3f) { this.needs3f = needs3f; }

    public Boolean getSamePersonAllDay() { return samePersonAllDay; }
    public void setSamePersonAllDay(Boolean samePersonAllDay) { this.samePersonAllDay = samePersonAllDay; }

    // Check if this shift requires any closing responsibility
    public boolean requiresClosing() { return needs1r || needs2f || needs3f; }

    public boolean isClosingShift() { return "closing".equals(needType); }

    // Check if this is a REST shift (for flexible staff days off)
    public boolean isRest() { return "rest".equals(needType); }

    // Check if this is a full-day shift (period_id=0, used for closing)
    public boolean isFullDay() { return periodId == 0; }

    public Location getLocation() { return location; }
    public void setLocation(Location location) { this.location = location; }

    public Set<UUID> getPhysicianIds() { return physicianIds; }
    public void setPhysicianIds(Set<UUID> physicianIds) { this.physicianIds = physicianIds; }

    // Check if a specific physician is present at this shift
    public boolean hasPhysician(UUID physicianId) {
        return physicianIds.contains(physicianId);
    }

    public String getPhysicianNames() { return physicianNames; }
    public void setPhysicianNames(String physicianNames) { this.physicianNames = physicianNames; }

    @Override
    public String toString() {
        String loc = locationName != null ? locationName : String.valueOf(locationId);
        String skill = skillName != null ? skillName : String.valueOf(skillId);
        String type = needType != null ? needType : "unknown";
        String closing = closingRole != null ? "/" + closingRole : "";
        return "Shift{" + date + " " + getPeriodName() + " @ " + loc + " [" + type + closing + "] " + skill + " x" + quantityNeeded + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Shift shift = (Shift) o;
        return id.equals(shift.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
