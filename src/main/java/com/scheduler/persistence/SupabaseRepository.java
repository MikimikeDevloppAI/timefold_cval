package com.scheduler.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.scheduler.domain.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Repository for loading data from Supabase and saving results.
 */
public class SupabaseRepository {

    private static final Logger log = LoggerFactory.getLogger(SupabaseRepository.class);

    private final String supabaseUrl;
    private final String supabaseKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SupabaseRepository(String supabaseUrl, String supabaseKey) {
        this.supabaseUrl = supabaseUrl;
        this.supabaseKey = supabaseKey;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Load all data needed for the solver.
     */
    public ScheduleSolution loadSolution(LocalDate startDate, LocalDate endDate) throws Exception {
        ScheduleSolution solution = new ScheduleSolution();

        log.info("Loading data from Supabase for {} to {}", startDate, endDate);

        // Load locations
        List<Location> locations = loadLocations();
        solution.setLocations(locations);
        log.info("Loaded {} locations", locations.size());

        // Load staff with skills and sites
        List<Staff> staffList = loadStaffWithPreferences();
        solution.setStaffList(staffList);
        log.info("Loaded {} staff members", staffList.size());

        // Load absences
        List<Absence> absences = loadAbsences(startDate, endDate);
        solution.setAbsences(absences);
        log.info("Loaded {} absences", absences.size());

        // Load shifts from v_staff_needs (consultation, surgical, admin)
        List<Shift> shifts = loadShifts(startDate, endDate);

        // Ensure Admin shifts exist for every date/period (fallback for staff with no valid shifts)
        List<Shift> adminShifts = ensureAdminShifts(shifts, startDate, endDate);
        shifts.addAll(adminShifts);

        // Create REST shifts for each date/period (used by flexible staff for days off)
        List<Shift> restShifts = createRestShifts(startDate, endDate);
        shifts.addAll(restShifts);

        solution.setShifts(shifts);
        log.info("Loaded {} shifts (including {} Admin + {} REST)",
            shifts.size(), adminShifts.size(), restShifts.size());

        // Create closing assignments (separate planning entities)
        List<ClosingAssignment> closingAssignments = createClosingAssignments(shifts);
        solution.setClosingAssignments(closingAssignments);
        log.info("Created {} closing assignments (1R/2F/3F)", closingAssignments.size());

        // Load physician presence and link to shifts
        loadPhysicianPresence(startDate, endDate, shifts);

        // Initialize lookup maps
        solution.initializeMaps();

        // Create assignments (one per available staff/date/period - INVERTED MODEL)
        List<StaffAssignment> assignments = createAssignmentsFromStaff(
            staffList, shifts, absences, startDate, endDate);
        solution.setAssignments(assignments);
        log.info("Created {} assignments from staff availability", assignments.size());

        return solution;
    }

    /**
     * Load locations with site information.
     */
    private List<Location> loadLocations() throws Exception {
        String query = "select=id,site_id,specialty_id,name,staffing_type,has_closing,sites(distance_type)";
        JsonNode data = fetchFromSupabase("locations", query);

        List<Location> locations = new ArrayList<>();
        for (JsonNode node : data) {
            Location loc = new Location();
            loc.setId(UUID.fromString(node.get("id").asText()));
            loc.setSiteId(UUID.fromString(node.get("site_id").asText()));
            if (node.has("specialty_id") && !node.get("specialty_id").isNull()) {
                loc.setSpecialtyId(UUID.fromString(node.get("specialty_id").asText()));
            }
            loc.setName(node.get("name").asText());
            loc.setStaffingType(node.get("staffing_type").asText());
            loc.setHasClosing(node.get("has_closing").asBoolean());
            if (node.has("sites") && !node.get("sites").isNull()) {
                loc.setDistanceType(node.get("sites").get("distance_type").asText());
            }
            locations.add(loc);
        }
        return locations;
    }

    /**
     * Load staff members with their skills and site preferences.
     */
    private List<Staff> loadStaffWithPreferences() throws Exception {
        // Load staff with user info
        // Note: days_per_week is calculated from work_percentage if not available
        String staffQuery = "select=id,user_id,has_flexible_schedule,work_percentage,admin_half_days_target,users(first_name,last_name,is_active)";
        JsonNode staffData = fetchFromSupabase("staff_members", staffQuery);

        Map<UUID, Staff> staffMap = new HashMap<>();
        for (JsonNode node : staffData) {
            Staff staff = new Staff();
            staff.setId(UUID.fromString(node.get("id").asText()));
            staff.setUserId(UUID.fromString(node.get("user_id").asText()));
            staff.setHasFlexibleSchedule(node.get("has_flexible_schedule").asBoolean());
            if (node.has("work_percentage") && !node.get("work_percentage").isNull()) {
                staff.setWorkPercentage(node.get("work_percentage").asDouble());
            }
            if (node.has("admin_half_days_target") && !node.get("admin_half_days_target").isNull()) {
                staff.setAdminHalfDaysTarget(node.get("admin_half_days_target").asInt());
            }
            // Auto-calculate days_per_week from work_percentage for flexible staff
            if (staff.getDaysPerWeek() == null && staff.getWorkPercentage() != null && staff.isHasFlexibleSchedule()) {
                int calculatedDays = (int) Math.round(staff.getWorkPercentage() * 5 / 100.0);
                staff.setDaysPerWeek(calculatedDays);
                log.debug("Calculated days_per_week={} for {} from work_percentage={}%",
                    calculatedDays, staff.getFirstName() + " " + staff.getLastName(), staff.getWorkPercentage());
            }
            // Note: prefers_admin column may not exist, defaults to false in Staff class
            if (node.has("users") && !node.get("users").isNull()) {
                JsonNode userNode = node.get("users");
                staff.setFirstName(userNode.get("first_name").asText());
                staff.setLastName(userNode.get("last_name").asText());
                staff.setActive(userNode.get("is_active").asBoolean());
            }
            staffMap.put(staff.getId(), staff);
        }

        // Load staff skills
        JsonNode skillsData = fetchFromSupabase("staff_skills", "select=staff_id,skill_id,preference&is_active=eq.true");
        for (JsonNode node : skillsData) {
            UUID staffId = UUID.fromString(node.get("staff_id").asText());
            Staff staff = staffMap.get(staffId);
            if (staff != null) {
                StaffSkill skill = new StaffSkill(
                    staffId,
                    UUID.fromString(node.get("skill_id").asText()),
                    node.get("preference").asInt()
                );
                staff.getSkills().add(skill);
            }
        }

        // Load staff sites
        JsonNode sitesData = fetchFromSupabase("staff_sites", "select=staff_id,site_id,priority&is_active=eq.true");
        for (JsonNode node : sitesData) {
            UUID staffId = UUID.fromString(node.get("staff_id").asText());
            Staff staff = staffMap.get(staffId);
            if (staff != null) {
                StaffSite site = new StaffSite(
                    staffId,
                    UUID.fromString(node.get("site_id").asText()),
                    node.get("priority").asInt()
                );
                staff.getSites().add(site);
            }
        }

        // Load staff availabilities from staff_recurring_schedules
        JsonNode availData = fetchFromSupabase("staff_recurring_schedules", "select=staff_id,day_of_week,period_id&is_active=eq.true");
        for (JsonNode node : availData) {
            UUID staffId = UUID.fromString(node.get("staff_id").asText());
            Staff staff = staffMap.get(staffId);
            if (staff != null) {
                StaffAvailability avail = new StaffAvailability(
                    staffId,
                    node.get("day_of_week").asInt(),
                    node.get("period_id").asInt()
                );
                staff.getAvailabilities().add(avail);
            }
        }

        // Load staff physicians (preferred physicians)
        JsonNode physData = fetchFromSupabase("staff_physicians", "select=staff_id,physician_id,priority&is_active=eq.true");
        for (JsonNode node : physData) {
            UUID staffId = UUID.fromString(node.get("staff_id").asText());
            Staff staff = staffMap.get(staffId);
            if (staff != null) {
                StaffPhysician sp = new StaffPhysician(
                    staffId,
                    UUID.fromString(node.get("physician_id").asText()),
                    node.get("priority").asInt()
                );
                staff.getPreferredPhysicians().add(sp);
            }
        }
        log.info("Loaded staff_physicians preferences");

        // Include ALL staff (including flexible staff with availabilities)
        // Flexible staff are now managed via constraint H6 (exact days per week)
        return new ArrayList<>(staffMap.values());
    }

    /**
     * Load absences for the date range.
     */
    private List<Absence> loadAbsences(LocalDate startDate, LocalDate endDate) throws Exception {
        String query = String.format("select=id,user_id,date,period_id&date=gte.%s&date=lte.%s",
            startDate, endDate);
        JsonNode data = fetchFromSupabase("user_absences", query);

        List<Absence> absences = new ArrayList<>();
        for (JsonNode node : data) {
            Absence absence = new Absence();
            absence.setId(UUID.fromString(node.get("id").asText()));
            absence.setUserId(UUID.fromString(node.get("user_id").asText()));
            absence.setDate(LocalDate.parse(node.get("date").asText()));
            if (node.has("period_id") && !node.get("period_id").isNull()) {
                absence.setPeriodId(node.get("period_id").asInt());
            }
            absences.add(absence);
        }
        return absences;
    }

    /**
     * Load physician presence from v_physician_effective_schedule and link to shifts.
     */
    private void loadPhysicianPresence(LocalDate startDate, LocalDate endDate, List<Shift> shifts) throws Exception {
        String query = String.format("select=physician_id,location_id,date,period_id&date=gte.%s&date=lte.%s",
            startDate, endDate);
        JsonNode data = fetchFromSupabase("v_physician_effective_schedule", query);

        // Build a map from (location_id, date, period_id) -> set of physician_ids
        Map<String, Set<UUID>> presenceMap = new HashMap<>();
        for (JsonNode node : data) {
            UUID locationId = UUID.fromString(node.get("location_id").asText());
            LocalDate date = LocalDate.parse(node.get("date").asText());
            int periodId = node.get("period_id").asInt();
            UUID physicianId = UUID.fromString(node.get("physician_id").asText());

            String key = locationId + "|" + date + "|" + periodId;
            presenceMap.computeIfAbsent(key, k -> new HashSet<>()).add(physicianId);
        }

        // Link physicians to shifts
        for (Shift shift : shifts) {
            String key = shift.getLocationId() + "|" + shift.getDate() + "|" + shift.getPeriodId();
            Set<UUID> physicians = presenceMap.get(key);
            if (physicians != null) {
                shift.setPhysicianIds(physicians);
            }
        }
        log.info("Loaded physician presence, {} unique location/date/period combinations", presenceMap.size());
    }

    /**
     * Load shifts from v_solver_staff_needs view.
     * Includes consultation, surgical, and admin shifts.
     * Closing needs (1R, 2F, 3F) are flags on consultation shifts, not separate shifts.
     */
    private List<Shift> loadShifts(LocalDate startDate, LocalDate endDate) throws Exception {
        String query = String.format(
            "select=location_id,location_name,site_id,site_name,date,period_id,skill_id,skill_name," +
            "quantity_needed,has_closing,physician_names,procedure_type_id,procedure_type_name," +
            "need_type,is_admin,needs_1r,needs_2f,needs_3f,same_person_all_day&date=gte.%s&date=lte.%s",
            startDate, endDate);
        JsonNode data = fetchFromSupabase("v_solver_staff_needs", query);

        List<Shift> shifts = new ArrayList<>();
        for (JsonNode node : data) {
            Shift shift = new Shift();

            // Location (can be null for admin)
            if (node.has("location_id") && !node.get("location_id").isNull()) {
                shift.setLocationId(UUID.fromString(node.get("location_id").asText()));
            }
            if (node.has("location_name") && !node.get("location_name").isNull()) {
                shift.setLocationName(node.get("location_name").asText());
            }

            // Site (can be null for admin)
            if (node.has("site_id") && !node.get("site_id").isNull()) {
                shift.setSiteId(UUID.fromString(node.get("site_id").asText()));
            }
            if (node.has("site_name") && !node.get("site_name").isNull()) {
                shift.setSiteName(node.get("site_name").asText());
            }

            shift.setDate(LocalDate.parse(node.get("date").asText()));
            shift.setPeriodId(node.get("period_id").asInt());

            // Skill (can be null for admin)
            if (node.has("skill_id") && !node.get("skill_id").isNull()) {
                shift.setSkillId(UUID.fromString(node.get("skill_id").asText()));
            }
            if (node.has("skill_name") && !node.get("skill_name").isNull()) {
                shift.setSkillName(node.get("skill_name").asText());
            }

            shift.setQuantityNeeded(node.get("quantity_needed").asInt());
            shift.setHasClosing(node.get("has_closing").asBoolean());

            // Need type and admin flag
            if (node.has("need_type") && !node.get("need_type").isNull()) {
                shift.setNeedType(node.get("need_type").asText());
            }
            if (node.has("is_admin") && !node.get("is_admin").isNull()) {
                shift.setAdmin(node.get("is_admin").asBoolean());
            }

            // Closing needs flags (for consultation shifts at locations with closing)
            // These indicate which closing responsibilities need to be assigned to staff on this shift
            if (node.has("needs_1r") && !node.get("needs_1r").isNull()) {
                shift.setNeeds1r(node.get("needs_1r").asBoolean());
            }
            if (node.has("needs_2f") && !node.get("needs_2f").isNull()) {
                shift.setNeeds2f(node.get("needs_2f").asBoolean());
            }
            if (node.has("needs_3f") && !node.get("needs_3f").isNull()) {
                shift.setNeeds3f(node.get("needs_3f").asBoolean());
            }
            if (node.has("same_person_all_day") && !node.get("same_person_all_day").isNull()) {
                shift.setSamePersonAllDay(node.get("same_person_all_day").asBoolean());
            }

            // Physician names for display in reports
            if (node.has("physician_names") && !node.get("physician_names").isNull()) {
                shift.setPhysicianNames(node.get("physician_names").asText());
            }

            shifts.add(shift);
        }

        // Log closing needs summary
        long shiftsWithClosing = shifts.stream().filter(Shift::requiresClosing).count();
        log.info("Loaded {} shifts ({} with closing responsibilities)", shifts.size(), shiftsWithClosing);

        return shifts;
    }

    /**
     * Ensure Admin shifts exist for every (date, period) combination.
     * Admin serves as a fallback for staff who have no valid consultation/surgical shifts.
     * If Admin shifts already exist in the database, this won't create duplicates.
     */
    private List<Shift> ensureAdminShifts(List<Shift> existingShifts, LocalDate startDate, LocalDate endDate) {
        // Find existing Admin shifts by date+period
        Set<String> existingAdminKeys = existingShifts.stream()
            .filter(Shift::isAdmin)
            .map(s -> s.getDate() + "|" + s.getPeriodId())
            .collect(Collectors.toSet());

        List<Shift> newAdminShifts = new ArrayList<>();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            int dayOfWeek = date.getDayOfWeek().getValue();
            if (dayOfWeek >= 6) continue;  // No Admin on Saturday/Sunday

            for (int periodId = 1; periodId <= 2; periodId++) {
                String key = date + "|" + periodId;
                if (!existingAdminKeys.contains(key)) {
                    // Create Admin shift for this date/period
                    Shift admin = new Shift();
                    admin.setId(UUID.randomUUID());
                    admin.setDate(date);
                    admin.setPeriodId(periodId);
                    admin.setNeedType("admin");
                    admin.setQuantityNeeded(999);  // Unlimited capacity
                    admin.setAdmin(true);
                    admin.setLocationName("Admin");
                    admin.setSkillName("Admin");
                    newAdminShifts.add(admin);
                }
            }
        }

        if (!newAdminShifts.isEmpty()) {
            log.info("Created {} Admin fallback shifts for date/period combinations missing Admin",
                newAdminShifts.size());
        }
        return newAdminShifts;
    }

    /**
     * Create REST shifts for each (date, period) combination.
     * REST shifts are used by flexible staff for their days off.
     * With nullable=false, the solver must assign something, so REST is an explicit shift.
     */
    private List<Shift> createRestShifts(LocalDate startDate, LocalDate endDate) {
        List<Shift> restShifts = new ArrayList<>();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            int dayOfWeek = date.getDayOfWeek().getValue();
            if (dayOfWeek >= 6) continue;  // No REST shifts on Saturday/Sunday (already off)

            for (int periodId = 1; periodId <= 2; periodId++) {
                Shift rest = new Shift();
                rest.setId(UUID.randomUUID());
                rest.setDate(date);
                rest.setPeriodId(periodId);
                rest.setNeedType("rest");
                rest.setQuantityNeeded(999);  // Unlimited capacity (any number of staff can REST)
                rest.setAdmin(false);
                rest.setLocationName("REST");
                rest.setSkillName("REST");
                restShifts.add(rest);
            }
        }

        log.info("Created {} REST shifts for flexible staff days off", restShifts.size());
        return restShifts;
    }

    /**
     * Create ClosingAssignment entities for each (location, date, role) that needs closing.
     * These are separate planning entities - the solver chooses which staff gets assigned.
     * One ClosingAssignment covers the ENTIRE DAY (both AM and PM).
     */
    private List<ClosingAssignment> createClosingAssignments(List<Shift> shifts) {
        List<ClosingAssignment> closingAssignments = new ArrayList<>();

        // Group shifts by (locationId, date) to find locations with closing needs
        Map<String, List<Shift>> shiftsByLocationDate = shifts.stream()
            .filter(s -> s.getLocationId() != null)
            .filter(s -> s.isHasClosing())
            .filter(s -> !s.isAdmin() && !s.isRest())
            .collect(Collectors.groupingBy(s -> s.getLocationId() + "|" + s.getDate()));

        for (Map.Entry<String, List<Shift>> entry : shiftsByLocationDate.entrySet()) {
            List<Shift> shiftsAtLocation = entry.getValue();
            if (shiftsAtLocation.isEmpty()) continue;

            // Get a reference shift for location info
            Shift refShift = shiftsAtLocation.get(0);

            // Check which periods have staff needs (both AM and PM required for closing)
            boolean hasMorning = shiftsAtLocation.stream().anyMatch(s -> s.getPeriodId() == 1);
            boolean hasAfternoon = shiftsAtLocation.stream().anyMatch(s -> s.getPeriodId() == 2);

            // Only create closing assignments if location is open for both periods
            // (closing responsibility requires working the whole day at the location)
            if (!hasMorning || !hasAfternoon) {
                log.debug("Skipping closing for {} on {} - not open full day (AM={}, PM={})",
                    refShift.getLocationName(), refShift.getDate(), hasMorning, hasAfternoon);
                continue;
            }

            // Calculate staff needed per period (AM/PM)
            // Only need closing assignments if multiple staff work the same period (>1 person per period)
            // If only 1 person works AM and 1 person works PM, no closing is needed
            int staffNeededAM = shiftsAtLocation.stream()
                .filter(s -> s.getPeriodId() == 1)
                .mapToInt(Shift::getQuantityNeeded)
                .sum();
            int staffNeededPM = shiftsAtLocation.stream()
                .filter(s -> s.getPeriodId() == 2)
                .mapToInt(Shift::getQuantityNeeded)
                .sum();

            // Need closing only if there are multiple staff in at least one period
            // (if only 1 staff per period, that person does everything - no need to assign closing roles)
            if (staffNeededAM <= 1 && staffNeededPM <= 1) {
                log.debug("Skipping closing for {} on {} - only {} staff AM, {} staff PM (no closing needed for single person)",
                    refShift.getLocationName(), refShift.getDate(), staffNeededAM, staffNeededPM);
                continue;
            }

            // Determine if we need 1R, 2F, 3F based on any shift at this location/date
            boolean needs1r = shiftsAtLocation.stream().anyMatch(Shift::isNeeds1r);
            boolean needs2f = shiftsAtLocation.stream().anyMatch(Shift::isNeeds2f);
            boolean needs3f = shiftsAtLocation.stream().anyMatch(Shift::isNeeds3f);

            // Create one ClosingAssignment per role needed (covers entire day)
            if (needs1r) {
                closingAssignments.add(new ClosingAssignment(
                    refShift.getLocationId(),
                    refShift.getLocationName(),
                    refShift.getDate(),
                    ClosingRole.ROLE_1R
                ));
            }
            if (needs2f) {
                closingAssignments.add(new ClosingAssignment(
                    refShift.getLocationId(),
                    refShift.getLocationName(),
                    refShift.getDate(),
                    ClosingRole.ROLE_2F
                ));
            }
            if (needs3f) {
                closingAssignments.add(new ClosingAssignment(
                    refShift.getLocationId(),
                    refShift.getLocationName(),
                    refShift.getDate(),
                    ClosingRole.ROLE_3F
                ));
            }
        }

        log.info("Created {} closing assignments (1R/2F/3F) from {} locations with closing needs",
            closingAssignments.size(), shiftsByLocationDate.size());

        return closingAssignments;
    }

    /**
     * Create assignment slots based on STAFF AVAILABILITY (INVERTED MODEL).
     * One assignment is created for each (staff, date, period) where staff is available.
     * The solver will then assign each staff to a shift.
     */
    private List<StaffAssignment> createAssignmentsFromStaff(
            List<Staff> staffList,
            List<Shift> shifts,
            List<Absence> absences,
            LocalDate startDate,
            LocalDate endDate) {

        // Build absence lookup map: userId -> set of (date, period) pairs
        Map<UUID, Set<String>> absenceMap = new HashMap<>();
        for (Absence absence : absences) {
            absenceMap.computeIfAbsent(absence.getUserId(), k -> new HashSet<>());
            if (absence.getPeriodId() != null) {
                // Specific period absence
                absenceMap.get(absence.getUserId()).add(absence.getDate() + "|" + absence.getPeriodId());
            } else {
                // Full day absence (both periods)
                absenceMap.get(absence.getUserId()).add(absence.getDate() + "|1");
                absenceMap.get(absence.getUserId()).add(absence.getDate() + "|2");
            }
        }

        List<StaffAssignment> assignments = new ArrayList<>();

        // For each staff member
        for (Staff staff : staffList) {
            // For each date in the range
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                int dayOfWeek = date.getDayOfWeek().getValue(); // 1=Monday, 7=Sunday

                // For each period
                for (int periodId = 1; periodId <= 2; periodId++) {
                    // Check if staff is available this day/period
                    if (!staff.isAvailable(dayOfWeek, periodId)) {
                        continue; // Skip - staff not available
                    }

                    // Check if staff has an absence
                    String absenceKey = date + "|" + periodId;
                    Set<String> staffAbsences = absenceMap.get(staff.getUserId());
                    if (staffAbsences != null && staffAbsences.contains(absenceKey)) {
                        continue; // Skip - staff is absent
                    }

                    // Create an assignment for this staff/date/period
                    StaffAssignment assignment = new StaffAssignment(staff, date, periodId);

                    // Pre-filter valid shifts for this assignment
                    // - Admin: always valid (fallback)
                    // - REST: only valid for flexible staff (for their days off)
                    // - Consultation/Surgical: valid if staff has skill AND can work at site
                    final LocalDate assignmentDate = date;
                    final int assignmentPeriod = periodId;
                    final boolean isFlexible = staff.isHasFlexibleSchedule();
                    List<Shift> validShifts = shifts.stream()
                        .filter(s -> s.getDate().equals(assignmentDate) &&
                            s.getPeriodId() == assignmentPeriod)
                        .filter(s -> {
                            // REST is only valid for flexible staff
                            if (s.isRest()) {
                                return isFlexible;
                            }
                            // Admin is always valid (fallback universel)
                            if (s.isAdmin()) return true;
                            // For consultation/surgical: require ELIGIBILITY (hasSkill + canWorkAtSite)
                            // Preferences affect SOFT score, not which shifts are valid
                            if (s.getSkillId() != null && !staff.hasSkill(s.getSkillId())) {
                                return false; // Doesn't have the skill
                            }
                            UUID siteId = s.getSiteId();
                            if (siteId != null && !staff.canWorkAtSite(siteId)) {
                                return false; // Can't work at this site
                            }
                            return true;
                        })
                        .toList();
                    assignment.setValidShifts(validShifts);

                    assignments.add(assignment);
                }
            }
        }

        // Log statistics about valid shifts
        int totalValidShifts = assignments.stream().mapToInt(a -> a.getValidShifts().size()).sum();
        double avgValidShifts = assignments.isEmpty() ? 0 : (double) totalValidShifts / assignments.size();
        log.info("Created {} assignments from {} staff members for {} to {} (avg {} valid shifts per assignment)",
            assignments.size(), staffList.size(), startDate, endDate, String.format("%.1f", avgValidShifts));

        // Log flexible staff summary (REST is now shift=null, not a separate shift)
        long flexibleCount = staffList.stream().filter(Staff::isHasFlexibleSchedule).count();
        log.info("Flexible staff: {} (will use shift=null for REST days)", flexibleCount);

        // DEBUG: Log flexible staff validShifts count
        for (Staff flexStaff : staffList.stream().filter(Staff::isHasFlexibleSchedule).toList()) {
            List<StaffAssignment> staffAssignments = assignments.stream()
                .filter(a -> a.getStaff().getId().equals(flexStaff.getId()))
                .toList();
            int flexTotalValid = staffAssignments.stream()
                .mapToInt(a -> a.getValidShifts() != null ? a.getValidShifts().size() : 0)
                .sum();
            int flexAvgValid = staffAssignments.isEmpty() ? 0 : flexTotalValid / staffAssignments.size();
            log.info("  {} ({}%): {} skills, {} sites, {} slots, avg {} valid shifts/slot",
                flexStaff.getFullName(),
                flexStaff.isHasFlexibleSchedule() ? (flexStaff.getDaysPerWeek() * 20) : 100,
                flexStaff.getSkills().size(),
                flexStaff.getSites().size(),
                staffAssignments.size(),
                flexAvgValid);
        }

        // Link AM↔PM assignments for O(1) constraint checks
        Map<String, StaffAssignment> assignmentMap = new HashMap<>();
        for (StaffAssignment a : assignments) {
            String key = a.getStaff().getId() + "|" + a.getDate() + "|" + a.getPeriodId();
            assignmentMap.put(key, a);
        }
        for (StaffAssignment a : assignments) {
            int otherPeriod = (a.getPeriodId() == 1) ? 2 : 1;
            String otherKey = a.getStaff().getId() + "|" + a.getDate() + "|" + otherPeriod;
            StaffAssignment other = assignmentMap.get(otherKey);
            a.setOtherHalfDay(other);
        }
        log.debug("Linked {} AM↔PM assignment pairs",
            assignments.stream().filter(a -> a.getOtherHalfDay() != null).count() / 2);

        return assignments;
    }

    /**
     * Save assignments to Supabase.
     * In the INVERTED MODEL, every assignment has a staff (fixed) and a shift (assigned by solver).
     * We skip admin assignments when saving to database (admin doesn't need to be stored).
     *
     * Closing responsibilities come from ClosingAssignment entities (separate planning entities).
     * We look up the staff assigned to each closing role and set flags on their regular assignments.
     */
    public void saveAssignments(List<StaffAssignment> assignments, List<ClosingAssignment> closingAssignments, UUID solverRunId) throws Exception {
        log.info("Saving {} assignments to Supabase", assignments.size());

        // Step 1: Build a map of closing roles from ClosingAssignment entities
        // Key format: staffId|locationId|date -> Set of ClosingRole
        Map<String, Set<ClosingRole>> closingRolesMap = new HashMap<>();
        for (ClosingAssignment ca : closingAssignments) {
            if (ca.getStaff() == null) continue;

            UUID staffId = ca.getStaff().getId();
            UUID locationId = ca.getLocationId();
            LocalDate date = ca.getDate();
            ClosingRole role = ca.getRole();

            if (locationId != null && role != null) {
                String key = staffId + "|" + locationId + "|" + date;
                closingRolesMap.computeIfAbsent(key, k -> new HashSet<>()).add(role);
            }
        }
        log.info("Found {} staff/location/date combinations with closing roles", closingRolesMap.size());

        // Step 2: Create records for regular assignments, applying closing flags
        List<Map<String, Object>> records = new ArrayList<>();
        for (StaffAssignment a : assignments) {
            // Skip if no shift assigned (shouldn't happen with nullable=false)
            if (a.getShift() == null) {
                continue;
            }

            // Skip admin assignments (we don't store "doing admin" in the database)
            if (a.getShift().isAdmin()) {
                continue;
            }

            // Skip REST shifts (days off for flexible staff, not stored)
            if (a.getShift().isRest()) {
                continue;
            }

            Map<String, Object> record = new HashMap<>();
            record.put("staff_id", a.getStaff().getId().toString());

            // Location may be null for admin (but we skip admin above)
            if (a.getLocationId() != null) {
                record.put("location_id", a.getLocationId().toString());
            }

            record.put("date", a.getDate().toString());
            record.put("period_id", a.getPeriodId());

            if (a.getSkillId() != null) {
                record.put("skill_id", a.getSkillId().toString());
            }

            // Look up closing roles for this staff/location/date
            UUID locationId = a.getLocationId();
            if (locationId != null) {
                String key = a.getStaff().getId() + "|" + locationId + "|" + a.getDate();
                Set<ClosingRole> roles = closingRolesMap.get(key);
                record.put("is_closing_1r", roles != null && roles.contains(ClosingRole.ROLE_1R));
                record.put("is_closing_2f", roles != null && roles.contains(ClosingRole.ROLE_2F));
                record.put("is_closing_3f", roles != null && roles.contains(ClosingRole.ROLE_3F));
            } else {
                record.put("is_closing_1r", false);
                record.put("is_closing_2f", false);
                record.put("is_closing_3f", false);
            }

            record.put("solver_run_id", solverRunId.toString());
            records.add(record);
        }

        if (!records.isEmpty()) {
            postToSupabase("staff_assignments", records);
            log.info("Saved {} assignments (excluding admin, rest)", records.size());
        } else {
            log.warn("No non-admin assignments to save!");
        }
    }

    /**
     * Fetch data from Supabase REST API.
     */
    private JsonNode fetchFromSupabase(String table, String query) throws Exception {
        String url = String.format("%s/rest/v1/%s?%s", supabaseUrl, table, query);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("apikey", supabaseKey)
            .header("Authorization", "Bearer " + supabaseKey)
            .header("Content-Type", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Supabase API error: " + response.statusCode() + " - " + response.body());
        }

        return objectMapper.readTree(response.body());
    }

    /**
     * Post data to Supabase REST API.
     */
    private void postToSupabase(String table, List<Map<String, Object>> records) throws Exception {
        String url = String.format("%s/rest/v1/%s", supabaseUrl, table);
        String json = objectMapper.writeValueAsString(records);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("apikey", supabaseKey)
            .header("Authorization", "Bearer " + supabaseKey)
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new IOException("Supabase API error: " + response.statusCode() + " - " + response.body());
        }
    }
}
