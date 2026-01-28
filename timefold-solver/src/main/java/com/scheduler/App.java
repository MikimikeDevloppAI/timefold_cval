package com.scheduler;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.constructionheuristic.placer.QueuedEntityPlacerConfig;
import ai.timefold.solver.core.config.heuristic.selector.entity.EntitySelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.value.ValueSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;

import com.scheduler.domain.ClosingRole;
import com.scheduler.domain.ScheduleSolution;
import com.scheduler.domain.ShiftSlot;
import com.scheduler.persistence.SupabaseRepository;
import com.scheduler.solver.ScheduleConstraintProvider;
import com.scheduler.solver.ShiftSlotChangeMoveFilter;
import com.scheduler.solver.ShiftSlotSwapMoveFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Main application for running the staff scheduler.
 *
 * MODEL: Single entity (ShiftSlot) with staff @PlanningVariable.
 * Closing roles are attributes on Shift (problem fact).
 * Admin shifts are 1:1 per staff. REST shifts are full-day for flexible staff.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private static final String DEFAULT_SUPABASE_URL = "https://rhrdtrgwfzmuyrhkkulv.supabase.co";

    public static void main(String[] args) {
        try {
            log.info("=== Staff Scheduler Starting ===");

            String supabaseUrl = getEnv("SUPABASE_URL", DEFAULT_SUPABASE_URL);
            String supabaseKey = getEnv("SUPABASE_SERVICE_ROLE_KEY", null);

            if (supabaseKey == null) {
                log.error("SUPABASE_SERVICE_ROLE_KEY environment variable is required");
                System.exit(1);
            }

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
            long closingSlots = problem.getShiftSlots().stream().filter(ShiftSlot::hasClosingRole).count();
            long adminSlots = problem.getShiftSlots().stream().filter(ShiftSlot::isAdmin).count();
            log.info("  - {} closing slots, {} admin slots", closingSlots, adminSlots);
            log.info("  - {} locations", problem.getLocations().size());
            log.info("  - {} absences", problem.getAbsences().size());

            // Debug mode: list all slots and exit
            boolean debugMode = args.length > 0 && "--debug-slots".equals(args[args.length - 1]);
            if (debugMode) {
                log.info("\n=== DEBUG: All Shifts ===");
                problem.getShifts().stream()
                    .sorted((a, b) -> {
                        int cmp = a.getDate().compareTo(b.getDate());
                        if (cmp != 0) return cmp;
                        cmp = Integer.compare(a.getPeriodId(), b.getPeriodId());
                        if (cmp != 0) return cmp;
                        return a.getNeedType() != null ? a.getNeedType().compareTo(b.getNeedType() != null ? b.getNeedType() : "") : 0;
                    })
                    .forEach(shift -> {
                        String closing = shift.getClosingRoleEnum() != null ? " [" + shift.getClosingRoleEnum().getDisplayName() + "]" : "";
                        String designated = shift.getDesignatedStaff() != null ? " (designated=" + shift.getDesignatedStaff().getFullName() + ")" : "";
                        log.info("  {} {} period={} {} @ {} qty={}{}{}",
                            shift.getDate(),
                            shift.getNeedType(),
                            shift.getPeriodId(),
                            shift.getSkillName() != null ? shift.getSkillName() : "-",
                            shift.getLocationName() != null ? shift.getLocationName() : "-",
                            shift.getQuantityNeeded(),
                            closing,
                            designated);
                    });

                log.info("\n=== DEBUG: All ShiftSlots ===");
                log.info("Closing slots:");
                problem.getShiftSlots().stream()
                    .filter(ShiftSlot::hasClosingRole)
                    .sorted((a, b) -> {
                        int cmp = a.getDate().compareTo(b.getDate());
                        if (cmp != 0) return cmp;
                        return a.getClosingRole().compareTo(b.getClosingRole());
                    })
                    .forEach(slot -> log.info("  {} {} @ {} fullDay={}",
                        slot.getDate(),
                        slot.getClosingRole().getDisplayName(),
                        slot.getLocationName(),
                        slot.isFullDaySlot()));

                log.info("\nAdmin slots:");
                problem.getShiftSlots().stream()
                    .filter(ShiftSlot::isAdmin)
                    .sorted((a, b) -> {
                        int cmp = a.getDate().compareTo(b.getDate());
                        if (cmp != 0) return cmp;
                        return Integer.compare(a.getPeriodId(), b.getPeriodId());
                    })
                    .forEach(slot -> {
                        String designated = slot.getDesignatedStaff() != null ? " designated=" + slot.getDesignatedStaff().getFullName() : " NO DESIGNATED";
                        log.info("  {} period={}{}", slot.getDate(), slot.getPeriodId(), designated);
                    });

                log.info("\nConsultation slots:");
                problem.getShiftSlots().stream()
                    .filter(ShiftSlot::isConsultation)
                    .filter(s -> !s.hasClosingRole())
                    .sorted((a, b) -> {
                        int cmp = a.getDate().compareTo(b.getDate());
                        if (cmp != 0) return cmp;
                        cmp = Integer.compare(a.getPeriodId(), b.getPeriodId());
                        if (cmp != 0) return cmp;
                        return (a.getLocationName() != null ? a.getLocationName() : "").compareTo(b.getLocationName() != null ? b.getLocationName() : "");
                    })
                    .forEach(slot -> log.info("  {} period={} {} @ {}",
                        slot.getDate(), slot.getPeriodId(),
                        slot.getSkillName() != null ? slot.getSkillName() : "-",
                        slot.getLocationName() != null ? slot.getLocationName() : "-"));

                log.info("\nSurgical slots:");
                problem.getShiftSlots().stream()
                    .filter(ShiftSlot::isSurgical)
                    .sorted((a, b) -> {
                        int cmp = a.getDate().compareTo(b.getDate());
                        if (cmp != 0) return cmp;
                        return Integer.compare(a.getPeriodId(), b.getPeriodId());
                    })
                    .forEach(slot -> log.info("  {} period={} {} @ {}",
                        slot.getDate(), slot.getPeriodId(),
                        slot.getSkillName() != null ? slot.getSkillName() : "-",
                        slot.getLocationName() != null ? slot.getLocationName() : "-"));

                log.info("\n=== DEBUG MODE: Exiting without solving ===");
                return;
            }

            // Configure solver: single entity (ShiftSlot), 2 phases
            SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(ScheduleSolution.class)
                .withEntityClasses(ShiftSlot.class)
                .withConstraintProviderClass(ScheduleConstraintProvider.class)
                .withPhases(
                    // Phase 1: Construction Heuristic for ShiftSlot
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
                    // Phase 2: Local Search for ShiftSlot
                    new LocalSearchPhaseConfig()
                        .withLocalSearchType(LocalSearchType.LATE_ACCEPTANCE)
                        .withMoveSelectorConfig(
                            new UnionMoveSelectorConfig()
                                .withMoveSelectorList(java.util.List.of(
                                    new ChangeMoveSelectorConfig()
                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                            .withEntityClass(ShiftSlot.class))
                                        .withValueSelectorConfig(new ValueSelectorConfig()
                                            .withVariableName("staff"))
                                        .withFilterClass(ShiftSlotChangeMoveFilter.class),
                                    new SwapMoveSelectorConfig()
                                        .withEntitySelectorConfig(new EntitySelectorConfig()
                                            .withEntityClass(ShiftSlot.class))
                                        .withFilterClass(ShiftSlotSwapMoveFilter.class)
                                ))
                        )
                )
                .withTerminationConfig(
                    new TerminationConfig()
                        .withSecondsSpentLimit(60L)
                        .withUnimprovedSecondsSpentLimit(30L)
                );

            // Create solver factory
            SolverFactory<ScheduleSolution> solverFactory = SolverFactory.create(solverConfig);

            // Calculate initial score
            log.info("Testing constraint provider...");
            var scoreManager = ai.timefold.solver.core.api.solver.SolutionManager.create(solverFactory);
            scoreManager.update(problem);
            log.info("Initial score (all null): {}", problem.getScore());

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

            // Score breakdown
            log.info("\n=== Score Analysis ===");
            var scoreAnalysis = scoreManager.explain(solution);
            log.info("{}", scoreAnalysis.getSummary());

            // Slot coverage
            long assignedSlots = solution.getShiftSlots().stream()
                .filter(s -> s.getStaff() != null)
                .count();
            long unassignedSlots = solution.getShiftSlots().stream()
                .filter(s -> s.getStaff() == null)
                .filter(s -> !s.isAdmin() && !s.isRest()) // Don't count admin/REST as unassigned
                .count();

            log.info("ShiftSlots: {} total, {} assigned, {} unassigned (excl. admin/REST)",
                solution.getShiftSlots().size(), assignedSlots, unassignedSlots);

            // Slots by location
            log.info("\n=== Slots by location ===");
            var byLoc = new java.util.HashMap<String, int[]>();
            for (var slot : solution.getShiftSlots()) {
                if (slot.isAdmin() || slot.isRest()) continue;
                String locName = slot.getLocationName() != null ? slot.getLocationName() : "Unknown";
                byLoc.computeIfAbsent(locName, k -> new int[2]);
                byLoc.get(locName)[1]++;
                if (slot.getStaff() != null) byLoc.get(locName)[0]++;
            }
            byLoc.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> log.info("  {}: {}/{} assigned", e.getKey(), e.getValue()[0], e.getValue()[1]));

            // Closing analysis (from ShiftSlot.closingRole)
            log.info("\n=== Closing Analysis ===");
            var closingSlotsResult = solution.getShiftSlots().stream()
                .filter(ShiftSlot::hasClosingRole)
                .toList();
            long closingAssigned = closingSlotsResult.stream()
                .filter(s -> s.getStaff() != null)
                .count();
            log.info("Closing slots: {} total, {} assigned", closingSlotsResult.size(), closingAssigned);

            closingSlotsResult.stream()
                .sorted((a, b) -> {
                    int cmp = a.getDate().compareTo(b.getDate());
                    if (cmp != 0) return cmp;
                    return a.getClosingRole().compareTo(b.getClosingRole());
                })
                .forEach(s -> log.info("  {} {} @ {} -> {}",
                    s.getDate(),
                    s.getClosingRole().getDisplayName(),
                    s.getLocationName(),
                    s.getStaff() != null ? s.getStaff().getFullName() : "UNASSIGNED"));

            // Closing balance per staff
            log.info("\n=== Closing Balance par Staff ===");
            var closingByStaff = closingSlotsResult.stream()
                .filter(s -> s.getStaff() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                    s -> s.getStaff().getFullName(),
                    java.util.stream.Collectors.counting()));
            closingByStaff.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> log.info("  {}: {} closing", e.getKey(), e.getValue()));

            // Flexible staff summary
            log.info("\n=== Flexible Staff Summary ===");
            var flexStaff = solution.getStaffList().stream()
                .filter(s -> s.isHasFlexibleSchedule())
                .toList();
            for (var staff : flexStaff) {
                long workDays = solution.getShiftSlots().stream()
                    .filter(s -> staff.equals(s.getStaff()))
                    .filter(s -> !s.isAdmin() && !s.isRest())
                    .map(ShiftSlot::getDate)
                    .distinct()
                    .count();
                log.info("  {} : target={}j, actual={}j",
                    staff.getFullName(), staff.getDaysPerWeek(), workDays);
            }

            // Unassigned slots analysis
            if (unassignedSlots == 0) {
                log.info("\nAll consultation/surgical ShiftSlots are fully covered!");
            } else {
                log.info("\n=== Unassigned Slots ===");
                solution.getShiftSlots().stream()
                    .filter(s -> s.getStaff() == null)
                    .filter(s -> !s.isAdmin() && !s.isRest())
                    .limit(20)
                    .forEach(slot -> {
                        String locName = slot.getLocationName() != null ? slot.getLocationName() : "Unknown";
                        String period = slot.isFullDay() ? "FULL" : (slot.getPeriodId() == 1 ? "AM" : "PM");
                        String closing = slot.hasClosingRole() ? " [" + slot.getClosingRole().getDisplayName() + "]" : "";
                        log.info("  {} {} {} @ {}{}  - UNASSIGNED",
                            slot.getDate(), period, slot.getNeedType(), locName, closing);
                    });
            }

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
