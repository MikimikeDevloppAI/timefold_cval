package com.scheduler.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a staff member with their skills and site preferences.
 * This is a ProblemFact (not a PlanningEntity).
 */
public class Staff {

    private UUID id;
    private UUID userId;
    private String firstName;
    private String lastName;
    private boolean hasFlexibleSchedule;
    private Double workPercentage;
    private Integer adminHalfDaysTarget;
    private Integer daysPerWeek;  // Target days per week for flexible staff
    private boolean active;

    // Skills with preferences (skill_id -> preference level 1-4)
    private Set<StaffSkill> skills = new HashSet<>();

    // Sites with preferences (site_id -> priority level 1-4)
    private Set<StaffSite> sites = new HashSet<>();

    // Availabilities (day_of_week + period_id combinations when staff can work)
    private Set<StaffAvailability> availabilities = new HashSet<>();

    // Preferred physicians (physician_id -> priority level 1-3)
    private Set<StaffPhysician> preferredPhysicians = new HashSet<>();

    // ========== PERFORMANCE: O(1) lookup caches ==========
    // These caches are initialized lazily on first access
    private transient Map<UUID, Integer> skillPreferenceCache;
    private transient Map<UUID, Integer> sitePriorityCache;
    private transient Map<UUID, Integer> physicianPriorityCache;
    private transient Set<UUID> preferredPhysicianIdsCache;

    public Staff() {}

    public Staff(UUID id, String firstName, String lastName) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    // ========== Initialize caches (lazy, called on first access) ==========
    private void initializeSkillCache() {
        if (skillPreferenceCache == null) {
            skillPreferenceCache = new HashMap<>();
            for (StaffSkill ss : skills) {
                skillPreferenceCache.put(ss.getSkillId(), ss.getPreference());
            }
        }
    }

    private void initializeSiteCache() {
        if (sitePriorityCache == null) {
            sitePriorityCache = new HashMap<>();
            for (StaffSite ss : sites) {
                sitePriorityCache.put(ss.getSiteId(), ss.getPriority());
            }
        }
    }

    private void initializePhysicianCache() {
        if (physicianPriorityCache == null) {
            physicianPriorityCache = new HashMap<>();
            preferredPhysicianIdsCache = new HashSet<>();
            for (StaffPhysician sp : preferredPhysicians) {
                physicianPriorityCache.put(sp.getPhysicianId(), sp.getPriority());
                preferredPhysicianIdsCache.add(sp.getPhysicianId());
            }
        }
    }

    // Check if staff has a specific skill - O(1)
    public boolean hasSkill(UUID skillId) {
        initializeSkillCache();
        return skillPreferenceCache.containsKey(skillId);
    }

    // Get skill preference (1-4), or 0 if not found - O(1)
    public int getSkillPreference(UUID skillId) {
        initializeSkillCache();
        return skillPreferenceCache.getOrDefault(skillId, 0);
    }

    // Check if staff can work at a specific site - O(1)
    public boolean canWorkAtSite(UUID siteId) {
        initializeSiteCache();
        return sitePriorityCache.containsKey(siteId);
    }

    // Get site priority (1-4), or 0 if not found - O(1)
    public int getSitePriority(UUID siteId) {
        initializeSiteCache();
        return sitePriorityCache.getOrDefault(siteId, 0);
    }

    // Check if staff is available on a specific day and period
    // Note: Kept as stream since availabilities set is small (max 10 entries)
    public boolean isAvailable(int dayOfWeek, int periodId) {
        return availabilities.stream()
            .anyMatch(a -> a.getDayOfWeek() == dayOfWeek && a.getPeriodId() == periodId);
    }

    // Check if staff has any preferred physicians
    public boolean hasPreferredPhysicians() {
        return !preferredPhysicians.isEmpty();
    }

    // Get physician preference priority (1-3), or 0 if not preferred - O(1)
    public int getPhysicianPriority(UUID physicianId) {
        initializePhysicianCache();
        return physicianPriorityCache.getOrDefault(physicianId, 0);
    }

    // Get all preferred physician IDs - cached, O(1) after first call
    public Set<UUID> getPreferredPhysicianIds() {
        initializePhysicianCache();
        return preferredPhysicianIdsCache;
    }

    // Get best (lowest) priority among preferred physicians
    public int getBestPhysicianPriority() {
        return preferredPhysicians.stream()
            .mapToInt(StaffPhysician::getPriority)
            .min()
            .orElse(0);
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public boolean isHasFlexibleSchedule() { return hasFlexibleSchedule; }
    public void setHasFlexibleSchedule(boolean hasFlexibleSchedule) { this.hasFlexibleSchedule = hasFlexibleSchedule; }

    public Double getWorkPercentage() { return workPercentage; }
    public void setWorkPercentage(Double workPercentage) { this.workPercentage = workPercentage; }

    public Integer getAdminHalfDaysTarget() { return adminHalfDaysTarget; }
    public void setAdminHalfDaysTarget(Integer adminHalfDaysTarget) { this.adminHalfDaysTarget = adminHalfDaysTarget; }

    public Integer getDaysPerWeek() { return daysPerWeek; }
    public void setDaysPerWeek(Integer daysPerWeek) { this.daysPerWeek = daysPerWeek; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Set<StaffSkill> getSkills() { return skills; }
    public void setSkills(Set<StaffSkill> skills) { this.skills = skills; }

    public Set<StaffSite> getSites() { return sites; }
    public void setSites(Set<StaffSite> sites) { this.sites = sites; }

    public Set<StaffAvailability> getAvailabilities() { return availabilities; }
    public void setAvailabilities(Set<StaffAvailability> availabilities) { this.availabilities = availabilities; }

    public Set<StaffPhysician> getPreferredPhysicians() { return preferredPhysicians; }
    public void setPreferredPhysicians(Set<StaffPhysician> preferredPhysicians) { this.preferredPhysicians = preferredPhysicians; }

    // Check if staff has admin target (admin_half_days_target > 0)
    public boolean hasAdminTarget() {
        return adminHalfDaysTarget != null && adminHalfDaysTarget > 0;
    }

    @Override
    public String toString() {
        return "Staff{" + firstName + " " + lastName + " [" + id + "]}";
    }
}
