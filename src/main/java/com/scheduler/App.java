package com.scheduler;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicType;
import ai.timefold.solver.core.config.constructionheuristic.placer.QueuedEntityPlacerConfig;
import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.RuinRecreateMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.value.ValueSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;

import com.scheduler.domain.ClosingAssignment;
import com.scheduler.domain.ClosingRole;
import com.scheduler.domain.ScheduleSolution;
import com.scheduler.domain.ShiftSlot;
import com.scheduler.persistence.SupabaseRepository;
import com.scheduler.solver.ScheduleConstraintProvider;
import com.scheduler.solver.ShiftSlotChangeMoveFilter;

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
            log.info("  - {} shift slots", problem.getShiftSlots().size());
            log.info("  - {} closing assignments", problem.getClosingAssignments().size());
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
            // ShiftSlot: staff variable | ClosingAssignment: staff variable
            SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(ScheduleSolution.class)
                .withEntityClasses(ShiftSlot.class, ClosingAssignment.class)
                .withConstraintProviderClass(ScheduleConstraintProvider.class)
                .withPhases(
                    // Phase 1: Construction Heuristic for ShiftSlot (variable: staff)
                    // ShiftSlot uses allowsUnassigned=true, so some slots may remain unassigned
                    // Filter eliminates invalid moves (wrong skill/site/availability)
                    new ConstructionHeuristicPhaseConfig()
                        .withEntityPlacerConfig(new QueuedEntityPlacerConfig()
                            .withEntitySelectorConfig(new EntitySelectorConfig()
                                .withEntityClass(ShiftSlot.class))
                            .withMoveSelectorConfigList(java.util.List.of(
                                new ChangeMoveSelectorConfig()
                                    .withEntitySelectorConfig(new EntitySelectorConfig()
                                        .withEntityClass(ShiftSlot.class))
                                    .withValueSelectorConfig(new ValueSelectorConfig()
                                        .withVariableName("staff"))
                                    .withFilterClass(ShiftSlotChangeMoveFilter.class)))),
                    // Phase 2: Construction Heuristic for ClosingAssignment
                    new ConstructionHeuristicPhaseConfig()
                        .withEntityPlacerConfig(new QueuedEntityPlacerConfig()
                            .withEntitySelectorConfig(new EntitySelectorConfig()
                                .withEntityClass(ClosingAssignment.class))),
                    // Phase 3: Local Search - optimizes both ShiftSlot.staff and ClosingAssignment.staff
                    new LocalSearchPhaseConfig()
                        .withLocalSearchType(LocalSearchType.LATE_ACCEPTANCE)
                        .withMoveSelectorConfig(
                            new UnionMoveSelectorConfig()
                                .withMoveSelectorList(java.util.List.of(
                                    // Move selector for ShiftSlot.staff variable (filtered)
                                    new ChangeMoveSelectorConfig()
                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                            .withEntityClass(ShiftSlot.class))
                                        .withValueSelectorConfig(new ValueSelectorConfig()
                                            .withVariableName("staff"))
                                        .withFilterClass(ShiftSlotChangeMoveFilter.class),
                                    // Move selector for ClosingAssignment.staff variable
                                    new ChangeMoveSelectorConfig()
                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                            .withEntityClass(ClosingAssignment.class))
                                        .withValueSelectorConfig(new ValueSelectorConfig()
                                            .withVariableName("staff"))
                                ))
                        )
                )
                .withTerminationConfig(
                    new TerminationConfig()
                        .withSecondsSpentLimit(10L)          // 10s max total
                        .withUnimprovedSecondsSpentLimit(5L) // Stop après 5s sans amélioration
                );

            // Create solver factory
            SolverFactory<ScheduleSolution> solverFactory = SolverFactory.create(solverConfig);

            // Test: Calculate initial score manually
            log.info("Testing constraint provider...");
            var scoreManager = ai.timefold.solver.core.api.solver.SolutionManager.create(solverFactory);
            scoreManager.update(problem);
            log.info("Initial score (all null): {}", problem.getScore());

            // Count unassigned slots
            long unassignedCount = problem.getShiftSlots().stream().filter(s -> s.getStaff() == null).count();
            log.info("Unassigned ShiftSlots: {}", unassignedCount);

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

            // Count slot coverage
            long assignedSlots = solution.getShiftSlots().stream()
                .filter(s -> s.getStaff() != null)
                .count();
            long unassignedSlots = solution.getShiftSlots().size() - assignedSlots;

            log.info("ShiftSlots: {} assigned, {} unassigned", assignedSlots, unassignedSlots);

            // Print slots by location
            log.info("\n=== Slots by location ===");
            var byLoc = new java.util.HashMap<String, int[]>();
            for (var slot : solution.getShiftSlots()) {
                String locName = slot.getLocationName() != null ? slot.getLocationName() : "Unknown";
                byLoc.computeIfAbsent(locName, k -> new int[2]);
                byLoc.get(locName)[1]++;
                if (slot.getStaff() != null) byLoc.get(locName)[0]++;
            }
            byLoc.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> log.info("  {}: {}/{} assigned", e.getKey(), e.getValue()[0], e.getValue()[1]));

            // Print flexible staff summary using shadow variables
            log.info("\n=== Flexible Staff Summary ===");
            var flexStaff = solution.getStaffList().stream()
                .filter(s -> s.isHasFlexibleSchedule())
                .toList();
            for (var staff : flexStaff) {
                // Count work days using shadow variable
                Integer workDays = solution.getShiftSlots().stream()
                    .filter(s -> staff.equals(s.getStaff()))
                    .map(ShiftSlot::getStaffWorkDayCount)
                    .filter(c -> c != null)
                    .findFirst()
                    .orElse(0);
                // Check if working full days
                long partialDays = solution.getShiftSlots().stream()
                    .filter(s -> staff.equals(s.getStaff()))
                    .filter(s -> !Boolean.TRUE.equals(s.getIsWorkingFullDay()))
                    .count();
                String status = (workDays <= staff.getDaysPerWeek() && partialDays == 0) ? "OK" : "VIOLATION";
                log.info("  {} : target={}j, workDays={}, partialDays={} {}",
                    staff.getFullName(), staff.getDaysPerWeek(), workDays, partialDays, status);
            }

            // Print flexible staff slot assignments
            log.info("\n=== Flexible Staff Slot Assignments ===");
            solution.getShiftSlots().stream()
                .filter(s -> s.getStaff() != null && s.getStaff().isHasFlexibleSchedule())
                .sorted((a, b) -> {
                    int cmp = a.getStaff().getFullName().compareTo(b.getStaff().getFullName());
                    if (cmp != 0) return cmp;
                    cmp = a.getDate().compareTo(b.getDate());
                    if (cmp != 0) return cmp;
                    return a.getPeriodId() - b.getPeriodId();
                })
                .forEach(s -> {
                    String fullDay = Boolean.TRUE.equals(s.getIsWorkingFullDay()) ? "FULL" : "PARTIAL";
                    log.info("  {} ({}j, workDays={}) -> {} {} : {} @ {} [{}]",
                        s.getStaff().getFullName(),
                        s.getStaff().getDaysPerWeek(),
                        s.getStaffWorkDayCount(),
                        s.getDate(),
                        s.getPeriodId() == 1 ? "AM" : "PM",
                        s.getNeedType(),
                        s.getLocationName() != null ? s.getLocationName() : "-",
                        fullDay);
                });

            // Print some details
            log.info("\n=== ShiftSlots (first 30) ===");
            solution.getShiftSlots().stream()
                .filter(s -> s.getStaff() != null)
                .limit(30) // Show first 30
                .forEach(s -> log.info("  {} -> {} {} @ {}",
                    s.getStaff().getFullName(),
                    s.getDate(),
                    s.getPeriodId() == 1 ? "AM" : "PM",
                    s.getLocationName()));

            // Analyze unassigned slots
            log.info("\n=== Unassigned Slots Analysis ===");
            var unassignedSlotsList = solution.getShiftSlots().stream()
                .filter(s -> s.getStaff() == null)
                .toList();

            // Count by type
            long unassignedConsultation = unassignedSlotsList.stream()
                .filter(ShiftSlot::isConsultation)
                .count();
            long unassignedSurgical = unassignedSlotsList.stream()
                .filter(ShiftSlot::isSurgical)
                .count();

            // Closing analysis (ClosingAssignment entity)
            var closingAssignments = solution.getClosingAssignments();
            long closingAssigned = closingAssignments.stream()
                .filter(ca -> ca.getStaff() != null)
                .count();
            log.info("Closing analysis: {} closing assignments, {} with staff",
                closingAssignments.size(), closingAssigned);

            // Diagnostic: For each location/date, show closing roles status
            log.info("\n=== Closing Assignments Diagnostic ===");
            var closingByLocationDate = closingAssignments.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    ca -> ca.getLocationId() + "|" + ca.getDate() + "|" + ca.getLocationName()));
            for (var entry : closingByLocationDate.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                UUID locId = UUID.fromString(parts[0]);
                LocalDate date = LocalDate.parse(parts[1]);
                String locName = parts[2];

                var caAtLoc = entry.getValue();
                long assignedAtLoc = caAtLoc.stream().filter(ca -> ca.getStaff() != null).count();

                log.info("  {} @ {} (loc={}): {} closing assignments, {} with staff",
                    date, locName, locId.toString().substring(0, 8),
                    caAtLoc.size(), assignedAtLoc);

                // Show who has closing role
                caAtLoc.stream()
                    .filter(ca -> ca.getStaff() != null)
                    .forEach(ca -> log.info("    {} -> {}",
                        ca.getRole().getDisplayName(),
                        ca.getStaff().getFullName()));
            }

            // Show closing assignments
            if (!closingAssignments.isEmpty()) {
                log.info("Closing assignments:");
                closingAssignments.stream()
                    .sorted((a, b) -> {
                        int cmp = a.getDate().compareTo(b.getDate());
                        if (cmp != 0) return cmp;
                        return a.getRole().compareTo(b.getRole());
                    })
                    .limit(20)
                    .forEach(ca -> log.info("  {} {} @ {} -> {}",
                        ca.getDate(),
                        ca.getRole().getDisplayName(),
                        ca.getLocationName(),
                        ca.getStaff() != null ? ca.getStaff().getFullName() : "UNASSIGNED"));

                // Log closing count per staff (for balance monitoring)
                log.info("\n=== Closing Balance par Staff ===");
                var closingByStaff = closingAssignments.stream()
                    .filter(ca -> ca.getStaff() != null)
                    .collect(java.util.stream.Collectors.groupingBy(
                        ca -> ca.getStaff().getFullName(),
                        java.util.stream.Collectors.counting()));
                closingByStaff.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        String warning = e.getValue() > 2 ? " TROP DE CLOSING!" : "";
                        log.info("  {}: {} closing{}", e.getKey(), e.getValue(), warning);
                    });

                // Total closing and staff with closing
                int staffWithClosing = closingByStaff.size();
                double avgClosingPerStaff = staffWithClosing > 0 ? (double) closingAssignments.size() / staffWithClosing : 0;
                log.info("  Total: {} closing assignments, {} staff, moyenne: {} par staff",
                    closingAssignments.size(), staffWithClosing, String.format("%.1f", avgClosingPerStaff));
            }

            // Analyze unassigned ShiftSlots (replacing old uncovered shifts analysis)
            if (unassignedSlots == 0) {
                log.info("All ShiftSlots are fully covered!");
            } else {
                log.info("Found {} unassigned ShiftSlots:", unassignedSlots);
                log.info("  Unassigned by type: consultation={}, surgical={}",
                    unassignedConsultation, unassignedSurgical);
                // Show first 20 unassigned slots
                unassignedSlotsList.stream().limit(20).forEach(slot -> {
                    String locName = slot.getLocationName() != null ? slot.getLocationName() : "Unknown";
                    String skillName = slot.getSkillName() != null ? slot.getSkillName() : "Unknown";
                    String period = slot.getPeriodId() == 1 ? "AM" : "PM";
                    log.info("  {} {} {} @ {} [{}] - UNASSIGNED",
                        slot.getDate(), period, slot.getNeedType(),
                        locName, skillName);
                });
            }

            // Save results to Supabase (commented out for testing)
            // UUID solverRunId = UUID.randomUUID();
            // repository.saveAssignments(solution.getShiftSlots(), solverRunId);
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
