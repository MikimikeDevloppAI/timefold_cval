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

        // Create dedicated closing shifts (1R, 2F) as full-day shifts with closingRoleEnum
        List<Shift> closingShifts = createClosingShifts(shifts);
        shifts.addAll(closingShifts);

        // Ensure Admin shifts exist - 1 per staff per date/period
        List<Shift> adminShifts = ensureAdminShifts(staffList, shifts, startDate, endDate);
        shifts.addAll(adminShifts);

        // NOTE: REST shifts removed - repos is now implicit (no assignment = rest)

        solution.setShifts(shifts);
        log.info("Loaded {} shifts (including {} closing, {} admin)",
            shifts.size(), closingShifts.size(), adminShifts.size());

        // Create ShiftSlots from shifts (1 slot per unit of coverage)
        List<ShiftSlot> shiftSlots = createShiftSlots(shifts);
        solution.setShiftSlots(shiftSlots);

        // Load physician presence and link to shifts
        loadPhysicianPresence(startDate, endDate, shifts);

        // Initialize lookup maps
        solution.initializeMaps();

        // NOTE: StaffAssignment model has been removed
        // All coverage is now handled via ShiftSlot (1 slot = 1 unit of coverage)
        // Staff flexible constraints are enforced via shadow variables on ShiftSlot

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

        // Filter out admin shifts - we create them manually via ensureAdminShifts()
        shifts.removeIf(Shift::isAdmin);

        // Log closing needs summary
        long shiftsWithClosing = shifts.stream().filter(Shift::requiresClosing).count();
        log.info("Loaded {} shifts ({} with closing responsibilities)", shifts.size(), shiftsWithClosing);

        return shifts;
    }

    /**
     * Create dedicated closing shifts from locations with closing needs.
     * For each location/date with needs_1r or needs_2f (and both AM+PM exist),
     * create a full-day shift with closingRoleEnum set.
     * Also reduces the quantity of original AM and PM shifts by the number of closing shifts.
     */
    private List<Shift> createClosingShifts(List<Shift> existingShifts) {
        List<Shift> closingShifts = new ArrayList<>();

        // Group non-admin/rest shifts by location/date
        Map<String, List<Shift>> byLocationDate = existingShifts.stream()
            .filter(s -> !s.isAdmin() && !s.isRest())
            .filter(s -> s.getLocationId() != null)
            .collect(Collectors.groupingBy(s -> s.getLocationId() + "|" + s.getDate()));

        for (var entry : byLocationDate.entrySet()) {
            List<Shift> shiftsAtLoc = entry.getValue();

            boolean needs1r = shiftsAtLoc.stream().anyMatch(Shift::isNeeds1r);
            boolean needs2f = shiftsAtLoc.stream().anyMatch(Shift::isNeeds2f);

            if (!needs1r && !needs2f) continue;

            boolean hasAM = shiftsAtLoc.stream().anyMatch(s -> s.getPeriodId() == 1);
            boolean hasPM = shiftsAtLoc.stream().anyMatch(s -> s.getPeriodId() == 2);

            if (!hasAM || !hasPM) continue; // Need both AM+PM for full-day closing

            // Use the first AM shift as template for location/site/skill info
            Shift templateShift = shiftsAtLoc.stream()
                .filter(s -> s.getPeriodId() == 1)
                .findFirst().orElse(shiftsAtLoc.get(0));

            int closingCount = 0;

            if (needs1r) {
                Shift closing1r = new Shift();
                closing1r.setId(UUID.randomUUID());
                closing1r.setLocationId(templateShift.getLocationId());
                closing1r.setLocationName(templateShift.getLocationName());
                closing1r.setSiteId(templateShift.getSiteId());
                closing1r.setSiteName(templateShift.getSiteName());
                closing1r.setDate(templateShift.getDate());
                closing1r.setPeriodId(1); // Will be treated as full-day via fullDaySlot flag
                closing1r.setSkillId(templateShift.getSkillId());
                closing1r.setSkillName(templateShift.getSkillName());
                closing1r.setQuantityNeeded(1);
                closing1r.setNeedType("consultation");
                closing1r.setClosingRoleEnum(ClosingRole.ROLE_1R);
                closing1r.setHasClosing(true);
                closingShifts.add(closing1r);
                closingCount++;
            }

            if (needs2f) {
                Shift closing2f = new Shift();
                closing2f.setId(UUID.randomUUID());
                closing2f.setLocationId(templateShift.getLocationId());
                closing2f.setLocationName(templateShift.getLocationName());
                closing2f.setSiteId(templateShift.getSiteId());
                closing2f.setSiteName(templateShift.getSiteName());
                closing2f.setDate(templateShift.getDate());
                closing2f.setPeriodId(1); // Will be treated as full-day via fullDaySlot flag
                closing2f.setSkillId(templateShift.getSkillId());
                closing2f.setSkillName(templateShift.getSkillName());
                closing2f.setQuantityNeeded(1);
                closing2f.setNeedType("consultation");
                closing2f.setClosingRoleEnum(ClosingRole.ROLE_2F);
                closing2f.setHasClosing(true);
                closingShifts.add(closing2f);
                closingCount++;
            }

            // Reduce quantity of original AM and PM shifts
            for (Shift s : shiftsAtLoc) {
                if (s.getPeriodId() == 1 || s.getPeriodId() == 2) {
                    int reduced = Math.max(0, s.getQuantityNeeded() - closingCount);
                    if (reduced != s.getQuantityNeeded()) {
                        log.debug("Reduced {} {} from qty={} to qty={} (closing slots take {} places)",
                            s.getLocationName(), s.getDate() + " " + s.getPeriodName(),
                            s.getQuantityNeeded(), reduced, closingCount);
                        s.setQuantityNeeded(reduced);
                    }
                }
            }
        }

        log.info("Created {} closing shifts (1R: {}, 2F: {})",
            closingShifts.size(),
            closingShifts.stream().filter(s -> s.getClosingRoleEnum() == ClosingRole.ROLE_1R).count(),
            closingShifts.stream().filter(s -> s.getClosingRoleEnum() == ClosingRole.ROLE_2F).count());

        return closingShifts;
    }

    /**
     * Create Admin shifts - 1 per NON-FLEXIBLE staff per date/period.
     * Each admin shift has a designated staff member (1:1 model).
     * The move filter ensures only the designated staff can be assigned.
     *
     * NOTE: Flexible staff use REST slots instead of admin slots.
     */
    private List<Shift> ensureAdminShifts(List<Staff> staffList, List<Shift> existingShifts,
                                           LocalDate startDate, LocalDate endDate) {
        List<Shift> adminShifts = new ArrayList<>();

        // Only non-flexible staff get admin slots
        List<Staff> nonFlexibleStaff = staffList.stream()
            .filter(s -> !s.isHasFlexibleSchedule())
            .toList();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            int dayOfWeek = date.getDayOfWeek().getValue();
            if (dayOfWeek >= 6) continue;

            for (int periodId = 1; periodId <= 2; periodId++) {
                for (Staff staff : nonFlexibleStaff) {
                    if (!staff.isAvailable(dayOfWeek, periodId)) continue;

                    Shift admin = new Shift();
                    admin.setId(UUID.randomUUID());
                    admin.setDate(date);
                    admin.setPeriodId(periodId);
                    admin.setNeedType("admin");
                    admin.setQuantityNeeded(1);
                    admin.setAdmin(true);
                    admin.setLocationName("Admin");
                    admin.setSkillName("Admin");
                    admin.setDesignatedStaff(staff);
                    adminShifts.add(admin);
                }
            }
        }

        log.info("Created {} Admin shifts (1 per non-flexible staff per date/period)", adminShifts.size());
        return adminShifts;
    }

    // NOTE: createRestShifts() removed - REST is now implicit (no assignment = rest day)

    /**
     * Create ShiftSlots from shifts.
     * MODEL: 1 slot = 1 unit of coverage.
     * If a shift has quantityNeeded=3, we create 3 ShiftSlots.
     *
     * Closing shifts (with closingRoleEnum set) create 1 full-day slot each.
     * Admin shifts (1 per staff) create 1 slot each.
     * REST shifts create slots for flexible staff capacity.
     */
    private List<ShiftSlot> createShiftSlots(List<Shift> shifts) {
        List<ShiftSlot> slots = new ArrayList<>();

        for (Shift shift : shifts) {
            // Closing shifts: 1 full-day slot each
            if (shift.getClosingRoleEnum() != null) {
                ShiftSlot slot = new ShiftSlot(shift, 0);
                slot.setFullDaySlot(true);
                slots.add(slot);
                continue;
            }

            // Admin shifts (1:1 per staff): 1 slot each
            if (shift.isAdmin()) {
                slots.add(new ShiftSlot(shift, 0));
                continue;
            }

            // REST shifts (1 per flexible staff per day): 1 full-day slot each
            if (shift.isRest()) {
                ShiftSlot slot = new ShiftSlot(shift, 0);
                slot.setFullDaySlot(true); // REST is full-day
                slots.add(slot);
                continue;
            }

            // Normal consultation/surgical shifts: 1 slot per unit of coverage
            for (int i = 0; i < shift.getQuantityNeeded(); i++) {
                slots.add(new ShiftSlot(shift, i));
            }
        }

        // Log statistics
        long surgicalSlots = slots.stream().filter(ShiftSlot::isSurgical).count();
        long consultationSlots = slots.stream().filter(ShiftSlot::isConsultation).count();
        long closingSlots = slots.stream().filter(ShiftSlot::hasClosingRole).count();
        long adminSlots = slots.stream().filter(ShiftSlot::isAdmin).count();
        long restSlots = slots.stream().filter(ShiftSlot::isRest).count();

        log.info("Created {} ShiftSlots ({} surgical, {} consultation, {} closing, {} admin, {} REST)",
            slots.size(), surgicalSlots, consultationSlots, closingSlots, adminSlots, restSlots);

        return slots;
    }

    /**
     * Save ShiftSlot assignments to Supabase.
     * Closing roles are read directly from the ShiftSlot's parent Shift.
     */
    public void saveAssignments(List<ShiftSlot> slots, UUID solverRunId) throws Exception {
        log.info("Saving {} ShiftSlot assignments to Supabase", slots.size());

        List<Map<String, Object>> records = new ArrayList<>();
        for (ShiftSlot slot : slots) {
            // Skip unassigned, admin, and REST slots
            if (slot.getStaff() == null || slot.isAdmin() || slot.isRest()) {
                continue;
            }

            Map<String, Object> record = new HashMap<>();
            record.put("staff_id", slot.getStaff().getId().toString());

            if (slot.getLocationId() != null) {
                record.put("location_id", slot.getLocationId().toString());
            }

            record.put("date", slot.getDate().toString());
            // For full-day closing slots, save as period_id=1 (AM) since they cover both
            record.put("period_id", slot.isFullDaySlot() ? 1 : slot.getPeriodId());

            if (slot.getSkillId() != null) {
                record.put("skill_id", slot.getSkillId().toString());
            }

            // Closing role directly from slot's shift
            ClosingRole closingRole = slot.getClosingRole();
            record.put("is_closing_1r", closingRole == ClosingRole.ROLE_1R);
            record.put("is_closing_2f", closingRole == ClosingRole.ROLE_2F);
            record.put("is_closing_3f", closingRole == ClosingRole.ROLE_3F);

            record.put("solver_run_id", solverRunId.toString());
            records.add(record);
        }

        if (!records.isEmpty()) {
            postToSupabase("staff_assignments", records);
            log.info("Saved {} slot assignments", records.size());
        } else {
            log.warn("No slot assignments to save!");
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
