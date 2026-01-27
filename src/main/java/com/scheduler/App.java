package com.scheduler;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicType;
import ai.timefold.solver.core.config.constructionheuristic.placer.QueuedEntityPlacerConfig;
import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.value.ValueSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;

import com.scheduler.domain.ClosingAssignment;
import com.scheduler.domain.ScheduleSolution;
import com.scheduler.domain.StaffAssignment;
import com.scheduler.persistence.SupabaseRepository;
import com.scheduler.solver.ScheduleConstraintProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Main application for running the staff scheduler.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    // Default Supabase configuration (can be overridden by environment variables)
    private static final String DEFAULT_SUPABASE_URL = "https://rhrdtrgwfzmuyrhkkulv.supabase.co";

    public static void main(String[] args) {
        try {
            log.info("=== Staff Scheduler Starting ===");

            // Get configuration from environment or defaults
            String supabaseUrl = getEnv("SUPABASE_URL", DEFAULT_SUPABASE_URL);
            String supabaseKey = getEnv("SUPABASE_SERVICE_ROLE_KEY", null);

            if (supabaseKey == null) {
                log.error("SUPABASE_SERVICE_ROLE_KEY environment variable is required");
                System.exit(1);
            }

            // Parse date range from arguments or use default (current week)
            LocalDate startDate = LocalDate.now();
            LocalDate endDate = startDate.plusDays(7);

            if (args.length >= 2) {
                startDate = LocalDate.parse(args[0]);
                endDate = LocalDate.parse(args[1]);
            }

            log.info("Scheduling period: {} to {}", startDate, endDate);

            // Load data from Supabase
            SupabaseRepository repository = new SupabaseRepository(supabaseUrl, supabaseKey);
            ScheduleSolution problem = repository.loadSolution(startDate, endDate);

            log.info("Problem loaded:");
            log.info("  - {} staff members", problem.getStaffList().size());
            log.info("  - {} shifts", problem.getShifts().size());
            log.info("  - {} assignment slots", problem.getAssignments().size());
            log.info("  - {} closing assignments (1R/2F/3F)", problem.getClosingAssignments().size());
            log.info("  - {} locations", problem.getLocations().size());
            log.info("  - {} absences", problem.getAbsences().size());

            // Debug: show staff details
            log.info("Staff details:");
            problem.getStaffList().stream().limit(5).forEach(s ->
                log.info("  - {} {} (skills: {}, sites: {}, avails: {})",
                    s.getFirstName(), s.getLastName(),
                    s.getSkills().size(), s.getSites().size(), s.getAvailabilities().size()));

            // Debug: show first shift details
            if (!problem.getShifts().isEmpty()) {
                var firstShift = problem.getShifts().get(0);
                log.info("First shift: date={}, period={}, skillId={}, qty={}",
                    firstShift.getDate(), firstShift.getPeriodId(),
                    firstShift.getSkillId(), firstShift.getQuantityNeeded());
            }

            // Configure solver with optimized phases and termination
            // Need separate construction heuristic phases for each entity class
            SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(ScheduleSolution.class)
                .withEntityClasses(StaffAssignment.class, ClosingAssignment.class)
                .withConstraintProviderClass(ScheduleConstraintProvider.class)
                .withPhases(
                    // Phase 1: Construction Heuristic for StaffAssignment (variable: shift)
                    // The difficultyComparatorClass on StaffAssignment influences the order
                    new ConstructionHeuristicPhaseConfig()
                        .withEntityPlacerConfig(new QueuedEntityPlacerConfig()
                            .withEntitySelectorConfig(new EntitySelectorConfig()
                                .withEntityClass(StaffAssignment.class))
                            .withMoveSelectorConfigList(java.util.List.of(
                                new ChangeMoveSelectorConfig()
                                    .withEntitySelectorConfig(new EntitySelectorConfig()
                                        .withEntityClass(StaffAssignment.class))
                                    .withValueSelectorConfig(new ValueSelectorConfig()
                                        .withVariableName("shift"))))),
                    // Phase 2: Construction Heuristic for ClosingAssignment (variable: staff)
                    new ConstructionHeuristicPhaseConfig()
                        .withEntityPlacerConfig(new QueuedEntityPlacerConfig()
                            .withEntitySelectorConfig(new EntitySelectorConfig()
                                .withEntityClass(ClosingAssignment.class))
                            .withMoveSelectorConfigList(java.util.List.of(
                                new ChangeMoveSelectorConfig()
                                    .withEntitySelectorConfig(new EntitySelectorConfig()
                                        .withEntityClass(ClosingAssignment.class))
                                    .withValueSelectorConfig(new ValueSelectorConfig()
                                        .withVariableName("staff"))))),
                    // Phase 3: Local Search with LATE_ACCEPTANCE for faster convergence
                    new LocalSearchPhaseConfig()
                        .withLocalSearchType(LocalSearchType.LATE_ACCEPTANCE)
                )
                .withTerminationConfig(
                    new TerminationConfig()
                        .withSecondsSpentLimit(5L)            // Max 5 seconds total
                        .withUnimprovedSecondsSpentLimit(2L)  // Stop if no improvement for 2s
                );

            // Create solver factory
            SolverFactory<ScheduleSolution> solverFactory = SolverFactory.create(solverConfig);

            // Test: Calculate initial score manually
            log.info("Testing constraint provider...");
            var scoreManager = ai.timefold.solver.core.api.solver.SolutionManager.create(solverFactory);
            scoreManager.update(problem);
            log.info("Initial score (all null): {}", problem.getScore());

            // Count null assignments
            long nullCount = problem.getAssignments().stream().filter(a -> a.getStaff() == null).count();
            log.info("Null assignments: {}", nullCount);

            // Create and run solver
            Solver<ScheduleSolution> solver = solverFactory.buildSolver();

            log.info("Starting solver...");
            long startTime = System.currentTimeMillis();
            ScheduleSolution solution = solver.solve(problem);
            long endTime = System.currentTimeMillis();

            // Log results
            log.info("=== Solver Finished ===");
            log.info("Solving time: {} seconds", (endTime - startTime) / 1000.0);
            log.info("Score: {}", solution.getScore());

            // Score breakdown by constraint
            log.info("\n=== Score Analysis ===");
            var scoreAnalysis = scoreManager.explain(solution);
            log.info("{}", scoreAnalysis.getSummary());

            // Count assignments
            long assigned = solution.getAssignments().stream()
                .filter(a -> a.getStaff() != null)
                .count();
            long unassigned = solution.getAssignments().size() - assigned;

            log.info("Assignments: {} assigned, {} unassigned", assigned, unassigned);

            // Print assignments by location
            log.info("\n=== Assignments by location ===");
            var byLoc = new java.util.HashMap<String, int[]>();
            for (var a : solution.getAssignments()) {
                String locName = a.getLocation() != null ? a.getLocation().getName() : "Unknown";
                byLoc.computeIfAbsent(locName, k -> new int[2]);
                byLoc.get(locName)[1]++;
                if (a.getStaff() != null) byLoc.get(locName)[0]++;
            }
            byLoc.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> log.info("  {}: {}/{} assigned", e.getKey(), e.getValue()[0], e.getValue()[1]));

            // Print flexible staff summary
            log.info("\n=== Flexible Staff Summary ===");
            var flexStaff = solution.getStaffList().stream()
                .filter(s -> s.isHasFlexibleSchedule())
                .toList();
            for (var staff : flexStaff) {
                long totalSlots = solution.getAssignments().stream()
                    .filter(a -> a.getStaff() != null && a.getStaff().getId().equals(staff.getId()))
                    .count();
                long restSlots = solution.getAssignments().stream()
                    .filter(a -> a.getStaff() != null && a.getStaff().getId().equals(staff.getId()))
                    .filter(a -> a.getShift() != null && a.getShift().isRest())
                    .count();
                long workSlots = totalSlots - restSlots;
                long fullWorkDays = solution.getAssignments().stream()
                    .filter(a -> a.getStaff() != null && a.getStaff().getId().equals(staff.getId()))
                    .filter(a -> a.getPeriodId() == 1 && a.getShift() != null && !a.getShift().isRest())
                    .filter(a -> a.getOtherHalfDay() != null && a.getOtherHalfDay().getShift() != null && !a.getOtherHalfDay().getShift().isRest())
                    .count();
                String status = (fullWorkDays == staff.getDaysPerWeek()) ? "✓ OK" : "✗ VIOLATION";
                log.info("  {} : target={}j, slots={}, workDays={} {}",
                    staff.getFullName(), staff.getDaysPerWeek(), totalSlots, fullWorkDays, status);
            }

            // Print flexible staff assignments specifically
            log.info("\n=== Flexible Staff Assignments ===");
            solution.getAssignments().stream()
                .filter(a -> a.getStaff() != null && a.getStaff().isHasFlexibleSchedule())
                .sorted((a, b) -> {
                    int cmp = a.getStaff().getFullName().compareTo(b.getStaff().getFullName());
                    if (cmp != 0) return cmp;
                    cmp = a.getDate().compareTo(b.getDate());
                    if (cmp != 0) return cmp;
                    return a.getPeriodId() - b.getPeriodId();
                })
                .forEach(a -> {
                    String shiftType = "?";
                    if (a.getShift() != null) {
                        if (a.getShift().isRest()) shiftType = "REST";
                        else if (a.getShift().isAdmin()) shiftType = "ADMIN";
                        else shiftType = a.getShift().getNeedType();
                    }
                    String pair = a.getOtherHalfDay() != null ? "✓" : "✗NO-PAIR";
                    log.info("  {} ({}j) -> {} {} : {} @ {} [{}]",
                        a.getStaff().getFullName(),
                        a.getStaff().getDaysPerWeek(),
                        a.getDate(),
                        a.getPeriodId() == 1 ? "AM" : "PM",
                        shiftType,
                        a.getLocation() != null ? a.getLocation().getName() : "-",
                        pair);
                });

            // Print some details
            log.info("\n=== Assignments (first 30) ===");
            solution.getAssignments().stream()
                .filter(a -> a.getStaff() != null)
                .limit(30) // Show first 30
                .forEach(a -> log.info("  {} -> {} {} @ {}",
                    a.getStaff().getFullName(),
                    a.getDate(),
                    a.getPeriodId() == 1 ? "AM" : "PM",
                    a.getLocation() != null ? a.getLocation().getName() : a.getLocationId()));

            // Analyze uncovered shifts
            log.info("\n=== Uncovered Shifts Analysis ===");
            var shiftCoverage = new java.util.HashMap<com.scheduler.domain.Shift, Integer>();
            for (var shift : solution.getShifts()) {
                shiftCoverage.put(shift, 0);
            }
            for (var a : solution.getAssignments()) {
                if (a.getShift() != null) {
                    shiftCoverage.merge(a.getShift(), 1, Integer::sum);
                }
            }

            // Show uncovered or partially covered shifts (excluding REST only)
            var uncoveredShifts = shiftCoverage.entrySet().stream()
                .filter(e -> !e.getKey().isRest())
                .filter(e -> e.getValue() < e.getKey().getQuantityNeeded())
                .sorted((a, b) -> {
                    int cmp = a.getKey().getDate().compareTo(b.getKey().getDate());
                    if (cmp != 0) return cmp;
                    return Integer.compare(a.getKey().getPeriodId(), b.getKey().getPeriodId());
                })
                .toList();

            // Count by type
            long uncoveredConsultation = uncoveredShifts.stream()
                .filter(e -> "consultation".equals(e.getKey().getNeedType()))
                .mapToInt(e -> e.getKey().getQuantityNeeded() - e.getValue())
                .sum();
            long uncoveredSurgical = uncoveredShifts.stream()
                .filter(e -> "surgical".equals(e.getKey().getNeedType()))
                .mapToInt(e -> e.getKey().getQuantityNeeded() - e.getValue())
                .sum();
            long uncoveredAdmin = uncoveredShifts.stream()
                .filter(e -> e.getKey().isAdmin())
                .mapToInt(e -> e.getKey().getQuantityNeeded() - e.getValue())
                .sum();

            // Closing analysis (from ClosingAssignment entities)
            long closingTotal = solution.getClosingAssignments().size();
            long closingAssigned = solution.getClosingAssignments().stream()
                .filter(ca -> ca.getStaff() != null)
                .count();
            log.info("Closing analysis: {} closing assignments, {} assigned to staff",
                closingTotal, closingAssigned);

            // Diagnostic: For each closing location/date, how many staff work ALL DAY there?
            log.info("\n=== Closing Location Diagnostic ===");
            var closingLocationDates = solution.getClosingAssignments().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    ca -> ca.getLocationId() + "|" + ca.getDate() + "|" + ca.getLocationName()));
            for (var entry : closingLocationDates.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                UUID locId = UUID.fromString(parts[0]);
                LocalDate date = LocalDate.parse(parts[1]);
                String locName = parts[2];

                // Find staff who work AM and PM at this location on this date
                var staffAtLocAM = solution.getAssignments().stream()
                    .filter(a -> a.getShift() != null && !a.getShift().isAdmin() && !a.getShift().isRest())
                    .filter(a -> a.getDate().equals(date) && a.getPeriodId() == 1)
                    .filter(a -> locId.equals(a.getLocationId()))
                    .map(a -> a.getStaff().getId())
                    .collect(java.util.stream.Collectors.toSet());

                var staffAtLocPM = solution.getAssignments().stream()
                    .filter(a -> a.getShift() != null && !a.getShift().isAdmin() && !a.getShift().isRest())
                    .filter(a -> a.getDate().equals(date) && a.getPeriodId() == 2)
                    .filter(a -> locId.equals(a.getLocationId()))
                    .map(a -> a.getStaff().getId())
                    .collect(java.util.stream.Collectors.toSet());

                // Staff who work ALL DAY = intersection of AM and PM
                var allDayStaff = new java.util.HashSet<>(staffAtLocAM);
                allDayStaff.retainAll(staffAtLocPM);

                log.info("  {} @ {} (loc={}): {} staff work AM, {} staff work PM, {} work ALL DAY",
                    date, locName, locId.toString().substring(0, 8),
                    staffAtLocAM.size(), staffAtLocPM.size(), allDayStaff.size());

                // Show who works all day
                if (!allDayStaff.isEmpty()) {
                    var allDayNames = solution.getStaffList().stream()
                        .filter(s -> allDayStaff.contains(s.getId()))
                        .map(s -> s.getFullName())
                        .toList();
                    log.info("    All-day staff: {}", allDayNames);
                }
            }
            // Show closing assignments details
            if (!solution.getClosingAssignments().isEmpty()) {
                log.info("Closing assignments:");
                solution.getClosingAssignments().stream()
                    .sorted((a, b) -> {
                        int cmp = a.getDate().compareTo(b.getDate());
                        if (cmp != 0) return cmp;
                        return a.getRole().compareTo(b.getRole());
                    })
                    .limit(20)
                    .forEach(ca -> log.info("  {} {} @ {} (loc={}) -> {}",
                        ca.getDate(), ca.getRole().getDisplayName(), ca.getLocationName(),
                        ca.getLocationId(),
                        ca.getStaff() != null ? ca.getStaff().getFullName() : "UNASSIGNED"));

                // Log closing count per staff (for balance monitoring)
                log.info("\n=== Closing Balance par Staff ===");
                var closingByStaff = solution.getClosingAssignments().stream()
                    .filter(ca -> ca.getStaff() != null)
                    .collect(java.util.stream.Collectors.groupingBy(
                        ca -> ca.getStaff().getFullName(),
                        java.util.stream.Collectors.counting()));
                closingByStaff.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        String warning = e.getValue() > 2 ? " ⚠️ TROP DE CLOSING!" : "";
                        log.info("  {}: {} jours de closing{}", e.getKey(), e.getValue(), warning);
                    });

                // Total closing assignments and staff with closing
                int totalClosing = solution.getClosingAssignments().size();
                int staffWithClosing = closingByStaff.size();
                double avgClosingPerStaff = staffWithClosing > 0 ? (double)totalClosing / staffWithClosing : 0;
                log.info("  Total: {} closing, {} staff, moyenne: {:.1f} par staff",
                    totalClosing, staffWithClosing, avgClosingPerStaff);
            }

            if (uncoveredShifts.isEmpty()) {
                log.info("All shifts are fully covered!");
            } else {
                log.info("Found {} uncovered/partially covered shifts:", uncoveredShifts.size());
                log.info("  Uncovered by type: consultation={}, surgical={}, admin={}",
                    uncoveredConsultation, uncoveredSurgical, uncoveredAdmin);
                for (var entry : uncoveredShifts) {
                    var shift = entry.getKey();
                    if (shift.isAdmin()) continue; // Skip admin in detailed list
                    int covered = entry.getValue();
                    int needed = shift.getQuantityNeeded();
                    String locName = shift.getLocationName() != null ? shift.getLocationName() : "Unknown";
                    String skillName = shift.getSkillName() != null ? shift.getSkillName() : "Unknown";
                    String period = shift.getPeriodId() == 1 ? "AM" : "PM";
                    log.info("  {} {} {} @ {} [{}]: {}/{} covered (missing {})",
                        shift.getDate(), period, shift.getNeedType(),
                        locName, skillName,
                        covered, needed, needed - covered);
                }
            }

            // Save results to Supabase (commented out for testing)
            // UUID solverRunId = UUID.randomUUID();
            // repository.saveAssignments(solution.getAssignments(), solution.getClosingAssignments(), solverRunId);
            // log.info("Results saved with solver_run_id: {}", solverRunId);

            // Generate HTML report
            String htmlPath = "schedule_output.html";
            com.scheduler.report.HtmlReportGenerator.generateReport(
                solution, startDate, endDate, htmlPath);
            log.info("HTML report generated: {}", new java.io.File(htmlPath).getAbsolutePath());

            log.info("\n=== Done ===");

        } catch (Exception e) {
            log.error("Error running scheduler", e);
            System.exit(1);
        }
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
