package com.scheduler.report;

import com.scheduler.domain.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Generates an HTML report visualizing the staff schedule.
 * Modern dashboard with charts and clear visualizations.
 */
public class HtmlReportGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.FRENCH);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM");
    private static final UUID PORRENTRUY_SITE_ID = UUID.fromString("627a8ef9-02f9-42b7-8438-ed230dddb87c");

    /**
     * Generate an HTML report from the solved schedule.
     */
    public static void generateReport(ScheduleSolution solution,
                                       LocalDate startDate,
                                       LocalDate endDate,
                                       String outputPath) throws IOException {
        StringBuilder html = new StringBuilder();

        // Header with Chart.js
        html.append("<!DOCTYPE html>\n<html lang=\"fr\">\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<title>Planning Staff - ").append(startDate).append(" au ").append(endDate).append("</title>\n");
        html.append("<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n");
        html.append("<style>\n").append(getCSS()).append("\n</style>\n");
        html.append("</head>\n<body>\n");

        // Header
        html.append("<header class=\"header\">\n");
        html.append("<h1>📊 Dashboard Planning Staff</h1>\n");
        html.append("<p class=\"subtitle\">").append(startDate).append(" → ").append(endDate).append("</p>\n");
        html.append("</header>\n");

        // Score banner
        html.append(buildScoreBanner(solution));

        // Dashboard with charts
        html.append("<div class=\"dashboard\">\n");
        html.append(buildChartsSection(solution, startDate, endDate));
        html.append("</div>\n");

        // Site view (physicians and staff AM/PM)
        html.append(buildSiteView(solution, startDate, endDate));

        // Calendar grid
        html.append("<div class=\"section\">\n");
        html.append("<h2>📅 Calendrier Détaillé</h2>\n");
        html.append(buildCalendarGrid(solution, startDate, endDate));
        html.append("</div>\n");

        // Closing assignments section
        html.append(buildClosingSection(solution));

        // Uncovered shifts section
        html.append(buildUncoveredShifts(solution));

        // Legend
        html.append(buildLegend());

        // Chart.js scripts
        html.append(buildChartScripts(solution, startDate, endDate));

        html.append("</body>\n</html>");

        // Write to file with UTF-8 encoding
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(outputPath), StandardCharsets.UTF_8)) {
            writer.write(html.toString());
        }
    }

    private static String buildScoreBanner(ScheduleSolution solution) {
        StringBuilder sb = new StringBuilder();

        // Parse score
        String scoreStr = solution.getScore() != null ? solution.getScore().toString() : "N/A";
        String scoreClass = "good";
        if (scoreStr.contains("-") && scoreStr.startsWith("0hard")) {
            scoreClass = "warning";
        } else if (scoreStr.contains("hard/-") || (scoreStr.contains("-") && !scoreStr.startsWith("0hard"))) {
            scoreClass = "bad";
        }

        long totalSlots = solution.getShiftSlots().size();
        long assignedCount = solution.getShiftSlots().stream()
            .filter(s -> s.getStaff() != null)
            .count();
        // Count closing assignments with staff (from ClosingAssignment entity)
        long closingAssigned = solution.getClosingAssignments().stream()
            .filter(ca -> ca.getStaff() != null)
            .count();

        sb.append("<div class=\"score-banner ").append(scoreClass).append("\">\n");
        sb.append("<div class=\"score-main\">\n");
        sb.append("<span class=\"score-label\">Score Global</span>\n");
        sb.append("<span class=\"score-value\">").append(scoreStr).append("</span>\n");
        sb.append("</div>\n");
        sb.append("<div class=\"score-stats\">\n");
        sb.append("<div class=\"stat-item\"><span class=\"stat-number\">").append(solution.getStaffList().size()).append("</span><span class=\"stat-label\">Staff</span></div>\n");
        sb.append("<div class=\"stat-item\"><span class=\"stat-number\">").append(assignedCount).append("</span><span class=\"stat-label\">Assignations</span></div>\n");
        sb.append("<div class=\"stat-item\"><span class=\"stat-number\">").append(closingAssigned).append("</span><span class=\"stat-label\">Closing</span></div>\n");
        sb.append("</div>\n");
        sb.append("</div>\n");

        return sb.toString();
    }

    private static String buildChartsSection(ScheduleSolution solution, LocalDate startDate, LocalDate endDate) {
        StringBuilder sb = new StringBuilder();

        // Row 1: Closing & Admin distribution
        sb.append("<div class=\"charts-row\">\n");

        // Closing chart
        sb.append("<div class=\"chart-card\">\n");
        sb.append("<h3>🔐 Répartition Closing (1R/2F)</h3>\n");
        sb.append("<canvas id=\"closingChart\"></canvas>\n");
        sb.append("</div>\n");

        // Admin chart
        sb.append("<div class=\"chart-card\">\n");
        sb.append("<h3>📋 Répartition Admin</h3>\n");
        sb.append("<canvas id=\"adminChart\"></canvas>\n");
        sb.append("</div>\n");

        sb.append("</div>\n");

        // Row 2: Porrentruy & Sites distribution
        sb.append("<div class=\"charts-row\">\n");

        // Porrentruy chart
        sb.append("<div class=\"chart-card\">\n");
        sb.append("<h3>🏔️ Jours à Porrentruy</h3>\n");
        sb.append("<canvas id=\"porrentruyChart\"></canvas>\n");
        sb.append("</div>\n");

        // Sites chart
        sb.append("<div class=\"chart-card\">\n");
        sb.append("<h3>🏥 Répartition par Site</h3>\n");
        sb.append("<canvas id=\"sitesChart\"></canvas>\n");
        sb.append("</div>\n");

        sb.append("</div>\n");

        // Row 3: Staff workload table
        sb.append("<div class=\"chart-card full-width\">\n");
        sb.append("<h3>👥 Charge de Travail par Staff</h3>\n");
        sb.append(buildWorkloadTable(solution));
        sb.append("</div>\n");

        return sb.toString();
    }

    private static String buildWorkloadTable(ScheduleSolution solution) {
        StringBuilder sb = new StringBuilder();

        // Calculate stats per staff
        Map<Staff, Map<String, Integer>> staffStats = new LinkedHashMap<>();

        for (Staff staff : solution.getStaffList()) {
            staffStats.put(staff, new HashMap<>());
            staffStats.get(staff).put("consultation", 0);
            staffStats.get(staff).put("surgical", 0);
            staffStats.get(staff).put("admin", 0);
            staffStats.get(staff).put("porrentruy", 0);
            staffStats.get(staff).put("closing", 0);
        }

        // Count assignments from ShiftSlots
        Set<String> porrentruyDays = new HashSet<>();
        for (ShiftSlot slot : solution.getShiftSlots()) {
            if (slot.getStaff() == null || slot.getShift() == null) continue;
            Map<String, Integer> stats = staffStats.get(slot.getStaff());
            if (stats == null) continue;

            Shift shift = slot.getShift();
            if (shift.isAdmin()) {
                stats.merge("admin", 1, Integer::sum);
            } else if ("surgical".equals(shift.getNeedType())) {
                stats.merge("surgical", 1, Integer::sum);
            } else if (!shift.isRest()) {
                stats.merge("consultation", 1, Integer::sum);
            }

            if (PORRENTRUY_SITE_ID.equals(shift.getSiteId())) {
                String key = slot.getStaff().getId() + "|" + slot.getDate();
                if (!porrentruyDays.contains(key)) {
                    porrentruyDays.add(key);
                    stats.merge("porrentruy", 1, Integer::sum);
                }
            }
        }

        // Count closing from ClosingAssignment entity
        for (ClosingAssignment ca : solution.getClosingAssignments()) {
            if (ca.getStaff() == null) continue;
            Map<String, Integer> stats = staffStats.get(ca.getStaff());
            if (stats != null) {
                stats.merge("closing", 1, Integer::sum);
            }
        }

        sb.append("<table class=\"workload-table\">\n");
        sb.append("<thead><tr>");
        sb.append("<th>Staff</th>");
        sb.append("<th>Consultation</th>");
        sb.append("<th>Chirurgie</th>");
        sb.append("<th>Admin</th>");
        sb.append("<th>Porrentruy</th>");
        sb.append("<th>Closing</th>");
        sb.append("<th>Total</th>");
        sb.append("</tr></thead>\n<tbody>\n");

        // Sort by total workload
        List<Map.Entry<Staff, Map<String, Integer>>> sorted = new ArrayList<>(staffStats.entrySet());
        sorted.sort((a, b) -> {
            int totalA = a.getValue().values().stream().mapToInt(i -> i).sum();
            int totalB = b.getValue().values().stream().mapToInt(i -> i).sum();
            return Integer.compare(totalB, totalA);
        });

        for (Map.Entry<Staff, Map<String, Integer>> entry : sorted) {
            Staff staff = entry.getKey();
            Map<String, Integer> stats = entry.getValue();
            int total = stats.values().stream().mapToInt(i -> i).sum();

            if (total == 0) continue; // Skip staff with no assignments

            sb.append("<tr>");
            sb.append("<td class=\"staff-name\">").append(escapeHtml(staff.getFullName())).append("</td>");
            sb.append("<td class=\"num consultation-cell\">").append(formatNum(stats.get("consultation"))).append("</td>");
            sb.append("<td class=\"num surgical-cell\">").append(formatNum(stats.get("surgical"))).append("</td>");
            sb.append("<td class=\"num admin-cell\">").append(formatNum(stats.get("admin"))).append("</td>");
            sb.append("<td class=\"num porrentruy-cell\">").append(formatNum(stats.get("porrentruy"))).append("</td>");
            sb.append("<td class=\"num closing-cell\">").append(formatNum(stats.get("closing"))).append("</td>");
            sb.append("<td class=\"num total-cell\">").append(total).append("</td>");
            sb.append("</tr>\n");
        }

        sb.append("</tbody></table>\n");
        return sb.toString();
    }

    private static String formatNum(Integer num) {
        return num != null && num > 0 ? String.valueOf(num) : "-";
    }

    private static String buildChartScripts(ScheduleSolution solution, LocalDate startDate, LocalDate endDate) {
        StringBuilder sb = new StringBuilder();
        sb.append("<script>\n");

        // Collect data for charts
        // Closing data (from ClosingAssignment entity)
        Map<String, int[]> closingByStaff = new LinkedHashMap<>(); // [1R count, 2F count]
        for (ClosingAssignment ca : solution.getClosingAssignments()) {
            ClosingRole role = ca.getRole();
            if (role != null && ca.getStaff() != null) {
                String name = ca.getStaff().getFullName();
                closingByStaff.computeIfAbsent(name, k -> new int[2]);
                if (role == ClosingRole.ROLE_1R) {
                    closingByStaff.get(name)[0]++;
                } else if (role == ClosingRole.ROLE_2F) {
                    closingByStaff.get(name)[1]++;
                }
            }
        }

        // Admin data
        Map<String, Long> adminByStaff = solution.getShiftSlots().stream()
            .filter(s -> s.getStaff() != null && s.getShift() != null && s.getShift().isAdmin())
            .collect(Collectors.groupingBy(
                s -> s.getStaff().getFullName(),
                Collectors.counting()));

        // Porrentruy data (count DAYS, not half-days)
        Map<String, Set<LocalDate>> porrentruyDaysByStaff = new LinkedHashMap<>();
        for (ShiftSlot slot : solution.getShiftSlots()) {
            if (slot.getStaff() != null && slot.getShift() != null && PORRENTRUY_SITE_ID.equals(slot.getShift().getSiteId())) {
                String name = slot.getStaff().getFullName();
                porrentruyDaysByStaff.computeIfAbsent(name, k -> new HashSet<>()).add(slot.getDate());
            }
        }
        Map<String, Integer> porrentruyByStaff = new LinkedHashMap<>();
        porrentruyDaysByStaff.forEach((name, days) -> porrentruyByStaff.put(name, days.size()));

        // Sites data
        Map<String, Long> assignmentsBySite = solution.getShiftSlots().stream()
            .filter(s -> s.getStaff() != null && s.getShift() != null && !s.getShift().isRest() && !s.getShift().isAdmin())
            .filter(s -> s.getShift().getSiteName() != null)
            .collect(Collectors.groupingBy(
                s -> s.getShift().getSiteName(),
                Collectors.counting()));

        // Closing Chart (Horizontal Bar)
        sb.append("new Chart(document.getElementById('closingChart'), {\n");
        sb.append("  type: 'bar',\n");
        sb.append("  data: {\n");
        sb.append("    labels: [").append(closingByStaff.keySet().stream().map(s -> "'" + escapeJs(s) + "'").collect(Collectors.joining(","))).append("],\n");
        sb.append("    datasets: [{\n");
        sb.append("      label: '1R',\n");
        sb.append("      data: [").append(closingByStaff.values().stream().map(arr -> String.valueOf(arr[0])).collect(Collectors.joining(","))).append("],\n");
        sb.append("      backgroundColor: '#42a5f5'\n");
        sb.append("    }, {\n");
        sb.append("      label: '2F',\n");
        sb.append("      data: [").append(closingByStaff.values().stream().map(arr -> String.valueOf(arr[1])).collect(Collectors.joining(","))).append("],\n");
        sb.append("      backgroundColor: '#ff7043'\n");
        sb.append("    }]\n");
        sb.append("  },\n");
        sb.append("  options: { indexAxis: 'y', responsive: true, plugins: { legend: { position: 'top' }}}\n");
        sb.append("});\n\n");

        // Admin Chart (Horizontal Bar)
        List<Map.Entry<String, Long>> adminSorted = adminByStaff.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .collect(Collectors.toList());
        sb.append("new Chart(document.getElementById('adminChart'), {\n");
        sb.append("  type: 'bar',\n");
        sb.append("  data: {\n");
        sb.append("    labels: [").append(adminSorted.stream().map(e -> "'" + escapeJs(e.getKey()) + "'").collect(Collectors.joining(","))).append("],\n");
        sb.append("    datasets: [{\n");
        sb.append("      label: 'Demi-journées Admin',\n");
        sb.append("      data: [").append(adminSorted.stream().map(e -> String.valueOf(e.getValue())).collect(Collectors.joining(","))).append("],\n");
        sb.append("      backgroundColor: '#78909c'\n");
        sb.append("    }]\n");
        sb.append("  },\n");
        sb.append("  options: { indexAxis: 'y', responsive: true, plugins: { legend: { display: false }}}\n");
        sb.append("});\n\n");

        // Porrentruy Chart (Horizontal Bar)
        List<Map.Entry<String, Integer>> porrentruySorted = porrentruyByStaff.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());
        sb.append("new Chart(document.getElementById('porrentruyChart'), {\n");
        sb.append("  type: 'bar',\n");
        sb.append("  data: {\n");
        sb.append("    labels: [").append(porrentruySorted.stream().map(e -> "'" + escapeJs(e.getKey()) + "'").collect(Collectors.joining(","))).append("],\n");
        sb.append("    datasets: [{\n");
        sb.append("      label: 'Jours à Porrentruy',\n");
        sb.append("      data: [").append(porrentruySorted.stream().map(e -> String.valueOf(e.getValue())).collect(Collectors.joining(","))).append("],\n");
        sb.append("      backgroundColor: '#66bb6a'\n");
        sb.append("    }]\n");
        sb.append("  },\n");
        sb.append("  options: { indexAxis: 'y', responsive: true, plugins: { legend: { display: false }}}\n");
        sb.append("});\n\n");

        // Sites Chart (Doughnut)
        String[] siteColors = {"#42a5f5", "#66bb6a", "#ff7043", "#ab47bc", "#ffca28", "#26a69a"};
        int colorIdx = 0;
        List<String> siteColorsList = new ArrayList<>();
        for (int i = 0; i < assignmentsBySite.size(); i++) {
            siteColorsList.add("'" + siteColors[i % siteColors.length] + "'");
        }
        sb.append("new Chart(document.getElementById('sitesChart'), {\n");
        sb.append("  type: 'doughnut',\n");
        sb.append("  data: {\n");
        sb.append("    labels: [").append(assignmentsBySite.keySet().stream().map(s -> "'" + escapeJs(s) + "'").collect(Collectors.joining(","))).append("],\n");
        sb.append("    datasets: [{\n");
        sb.append("      data: [").append(assignmentsBySite.values().stream().map(String::valueOf).collect(Collectors.joining(","))).append("],\n");
        sb.append("      backgroundColor: [").append(String.join(",", siteColorsList)).append("]\n");
        sb.append("    }]\n");
        sb.append("  },\n");
        sb.append("  options: { responsive: true, plugins: { legend: { position: 'right' }}}\n");
        sb.append("});\n");

        sb.append("</script>\n");
        return sb.toString();
    }

    private static String buildCalendarGrid(ScheduleSolution solution,
                                             LocalDate startDate,
                                             LocalDate endDate) {
        StringBuilder grid = new StringBuilder();

        // Group slots by staff
        Map<Staff, List<ShiftSlot>> byStaff = solution.getShiftSlots().stream()
            .filter(s -> s.getStaff() != null)
            .collect(Collectors.groupingBy(ShiftSlot::getStaff));

        // Build closing map: staff+location+date -> ClosingRole
        Map<String, ClosingRole> closingMap = new HashMap<>();
        for (ClosingAssignment ca : solution.getClosingAssignments()) {
            if (ca.getStaff() != null) {
                String key = ca.getStaff().getId() + "|" + ca.getLocationId() + "|" + ca.getDate();
                closingMap.put(key, ca.getRole());
            }
        }

        // Collect workdays
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() < 6) {
                dates.add(d);
            }
        }

        grid.append("<div class=\"calendar-wrapper\">\n");
        grid.append("<table class=\"calendar\">\n<thead>\n<tr>\n");
        grid.append("<th class=\"staff-col\">Staff</th>\n");

        for (LocalDate date : dates) {
            String dayName = date.format(DATE_FORMATTER);
            grid.append("<th>").append(dayName).append("</th>\n");
        }
        grid.append("</tr>\n</thead>\n<tbody>\n");

        // Sort staff by name
        List<Staff> sortedStaff = new ArrayList<>(byStaff.keySet());
        sortedStaff.sort(Comparator.comparing(Staff::getLastName)
                                   .thenComparing(Staff::getFirstName));

        for (Staff staff : sortedStaff) {
            grid.append("<tr>\n");
            grid.append("<td class=\"staff-name\">").append(escapeHtml(staff.getFullName())).append("</td>\n");

            Map<String, ShiftSlot> slotMap = byStaff.get(staff).stream()
                .collect(Collectors.toMap(
                    s -> s.getDate() + "|" + s.getPeriodId(),
                    s -> s,
                    (s1, s2) -> s1));

            for (LocalDate date : dates) {
                grid.append("<td class=\"day-cell\">\n");

                // Check for full-day closing slot (periodId=0)
                ShiftSlot fullDay = slotMap.get(date + "|0");
                if (fullDay != null) {
                    // Full-day closing slot spans both AM and PM
                    grid.append(formatSlot(fullDay, "closing-fullday", closingMap));
                } else {
                    // AM slot
                    ShiftSlot am = slotMap.get(date + "|1");
                    grid.append(formatSlot(am, "am", closingMap));

                    // PM slot
                    ShiftSlot pm = slotMap.get(date + "|2");
                    grid.append(formatSlot(pm, "pm", closingMap));
                }

                grid.append("</td>\n");
            }
            grid.append("</tr>\n");
        }

        grid.append("</tbody>\n</table>\n</div>\n");
        return grid.toString();
    }

    private static String formatSlot(ShiftSlot slot, String periodClass, Map<String, ClosingRole> closingMap) {
        if (slot == null || slot.getShift() == null) {
            return "<div class=\"slot " + periodClass + " empty\">-</div>\n";
        }

        Shift shift = slot.getShift();
        String typeClass = getTypeClass(shift);
        String label = getLabel(shift);
        String tooltip = getTooltip(shift);

        // Check if this staff has a closing role at this location/date
        String closingBadge = "";
        if (slot.getStaff() != null && slot.getLocationId() != null) {
            String closingKey = slot.getStaff().getId() + "|" + slot.getLocationId() + "|" + slot.getDate();
            ClosingRole closingRole = closingMap.get(closingKey);
            if (closingRole != null) {
                closingBadge = " <span class=\"closing-badge\">[" + closingRole.getDisplayName() + "]</span>";
            }
        }

        return "<div class=\"slot " + periodClass + " " + typeClass + "\" title=\"" +
               escapeHtml(tooltip) + "\">" + escapeHtml(label) + closingBadge + "</div>\n";
    }

    private static String getTypeClass(Shift shift) {
        if (shift.isRest()) return "rest";
        if (shift.isAdmin()) return "admin";
        if (shift.isClosingShift()) return "closing";
        if ("surgical".equals(shift.getNeedType())) return "surgical";
        return "consultation";
    }

    private static String getLabel(Shift shift) {
        if (shift.isRest()) return "REPOS";
        if (shift.isAdmin()) return "Admin";
        if (shift.isClosingShift()) {
            // For full-day closing shifts, show "CLOSING" + location
            String loc = shift.getLocationName() != null ? truncate(shift.getLocationName(), 10) : "";
            return "CLOSING @ " + loc;
        }

        StringBuilder sb = new StringBuilder();
        if (shift.getSkillName() != null) {
            sb.append(truncate(shift.getSkillName(), 12));
        }
        if (shift.getLocationName() != null) {
            if (sb.length() > 0) sb.append(" @ ");
            sb.append(truncate(shift.getLocationName(), 10));
        }
        return sb.toString();
    }

    private static String getTooltip(Shift shift) {
        if (shift.isRest()) return "Jour de repos";
        if (shift.isAdmin()) return "Administration";

        StringBuilder tooltip = new StringBuilder();
        if (shift.getSkillName() != null) tooltip.append(shift.getSkillName());
        if (shift.getLocationName() != null) {
            if (tooltip.length() > 0) tooltip.append(" @ ");
            tooltip.append(shift.getLocationName());
        }
        if (shift.getSiteName() != null) {
            tooltip.append(" (").append(shift.getSiteName()).append(")");
        }
        return tooltip.toString();
    }

    private static String buildSiteView(ScheduleSolution solution, LocalDate startDate, LocalDate endDate) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"section\">\n");
        sb.append("<h2>🏥 Vue par Site</h2>\n");

        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() < 6) dates.add(d);
        }

        Map<String, Map<LocalDate, Map<Integer, List<String>>>> siteStaffMap = new LinkedHashMap<>();
        Map<String, Map<LocalDate, Map<Integer, Set<String>>>> sitePhysicianMap = new LinkedHashMap<>();

        for (ShiftSlot slot : solution.getShiftSlots()) {
            if (slot.getStaff() == null || slot.getShift() == null || slot.getShift().isRest() || slot.getShift().isAdmin()) continue;

            Shift shift = slot.getShift();
            String siteName = shift.getSiteName() != null ? shift.getSiteName() : "Autre";
            LocalDate date = slot.getDate();
            int period = slot.getPeriodId();

            siteStaffMap.computeIfAbsent(siteName, k -> new LinkedHashMap<>())
                .computeIfAbsent(date, k -> new LinkedHashMap<>())
                .computeIfAbsent(period, k -> new ArrayList<>())
                .add(slot.getStaff().getFullName());

            if (shift.getPhysicianNames() != null && !shift.getPhysicianNames().isEmpty()) {
                String[] physicians = shift.getPhysicianNames().split(",\\s*");
                for (String physician : physicians) {
                    sitePhysicianMap.computeIfAbsent(siteName, k -> new LinkedHashMap<>())
                        .computeIfAbsent(date, k -> new LinkedHashMap<>())
                        .computeIfAbsent(period, k -> new LinkedHashSet<>())
                        .add(physician.trim());
                }
            }
        }

        for (String siteName : siteStaffMap.keySet()) {
            sb.append("<div class=\"site-card\">\n");
            sb.append("<h3>").append(escapeHtml(siteName)).append("</h3>\n");
            sb.append("<table class=\"site-table\">\n");
            sb.append("<tr><th>Date</th><th>Médecins</th><th>Staff Matin</th><th>Staff Après-midi</th></tr>\n");

            Map<LocalDate, Map<Integer, List<String>>> dateMap = siteStaffMap.get(siteName);
            Map<LocalDate, Map<Integer, Set<String>>> datePhysMap = sitePhysicianMap.getOrDefault(siteName, Collections.emptyMap());

            for (LocalDate date : dates) {
                if (!dateMap.containsKey(date)) continue;

                Map<Integer, List<String>> periodMap = dateMap.get(date);
                Map<Integer, Set<String>> periodPhysMap = datePhysMap.getOrDefault(date, Collections.emptyMap());

                List<String> staffAm = periodMap.getOrDefault(1, Collections.emptyList());
                List<String> staffPm = periodMap.getOrDefault(2, Collections.emptyList());
                Set<String> allPhysicians = new LinkedHashSet<>();
                allPhysicians.addAll(periodPhysMap.getOrDefault(1, Collections.emptySet()));
                allPhysicians.addAll(periodPhysMap.getOrDefault(2, Collections.emptySet()));

                sb.append("<tr>\n");
                sb.append("<td class=\"date-cell\">").append(date.format(DATE_FORMATTER)).append("</td>\n");
                sb.append("<td class=\"physician-cell\">").append(escapeHtml(String.join(", ", allPhysicians))).append("</td>\n");
                sb.append("<td class=\"staff-am\">").append(escapeHtml(String.join(", ", staffAm))).append("</td>\n");
                sb.append("<td class=\"staff-pm\">").append(escapeHtml(String.join(", ", staffPm))).append("</td>\n");
                sb.append("</tr>\n");
            }

            sb.append("</table>\n</div>\n");
        }

        sb.append("</div>\n");
        return sb.toString();
    }

    private static String buildClosingSection(ScheduleSolution solution) {
        StringBuilder sb = new StringBuilder();

        // Get closing assignments (ClosingAssignment entity)
        List<ClosingAssignment> closingAssignments = solution.getClosingAssignments();
        if (closingAssignments.isEmpty()) return "";

        sb.append("<div class=\"section\">\n");
        sb.append("<h2>🔐 Responsabilités Closing</h2>\n");

        Map<LocalDate, List<ClosingAssignment>> byDate = closingAssignments.stream()
            .sorted(Comparator.comparing(ClosingAssignment::getDate)
                .thenComparing(ca -> ca.getLocationName() != null ? ca.getLocationName() : "")
                .thenComparing(ca -> ca.getRole() != null ? ca.getRole().ordinal() : 99))
            .collect(Collectors.groupingBy(ClosingAssignment::getDate, LinkedHashMap::new, Collectors.toList()));

        sb.append("<table class=\"closing-table\">\n");
        sb.append("<tr><th>Date</th><th>Location</th><th>Rôle</th><th>Staff Assigné</th></tr>\n");

        for (Map.Entry<LocalDate, List<ClosingAssignment>> entry : byDate.entrySet()) {
            LocalDate date = entry.getKey();
            for (ClosingAssignment ca : entry.getValue()) {
                String statusClass = ca.getStaff() != null ? "assigned" : "unassigned";
                String staffName = ca.getStaff() != null ? ca.getStaff().getFullName() : "NON ASSIGNE";
                ClosingRole role = ca.getRole();
                String roleDisplay = role != null ? role.getDisplayName() : "?";

                sb.append("<tr class=\"").append(statusClass).append("\">\n");
                sb.append("<td>").append(date.format(DATE_FORMATTER)).append("</td>\n");
                sb.append("<td>").append(escapeHtml(ca.getLocationName())).append("</td>\n");
                sb.append("<td><span class=\"role-badge ").append(roleDisplay.toLowerCase()).append("\">")
                  .append(roleDisplay).append("</span></td>\n");
                sb.append("<td>").append(escapeHtml(staffName)).append("</td>\n");
                sb.append("</tr>\n");
            }
        }

        sb.append("</table>\n</div>\n");
        return sb.toString();
    }

    private static String buildUncoveredShifts(ScheduleSolution solution) {
        StringBuilder sb = new StringBuilder();

        // With ShiftSlot model: count assigned slots per shift
        Map<Shift, Integer> shiftCoverage = new HashMap<>();
        for (Shift shift : solution.getShifts()) {
            shiftCoverage.put(shift, 0);
        }
        for (ShiftSlot slot : solution.getShiftSlots()) {
            if (slot.getStaff() != null && slot.getShift() != null) {
                shiftCoverage.merge(slot.getShift(), 1, Integer::sum);
            }
        }

        List<Map.Entry<Shift, Integer>> uncovered = shiftCoverage.entrySet().stream()
            .filter(e -> !e.getKey().isRest())
            .filter(e -> !e.getKey().isAdmin())
            .filter(e -> e.getValue() < e.getKey().getQuantityNeeded())
            .sorted(Comparator.comparing((Map.Entry<Shift, Integer> e) -> e.getKey().getDate())
                .thenComparing(e -> e.getKey().getPeriodId()))
            .collect(Collectors.toList());

        sb.append("<div class=\"section\">\n");
        if (uncovered.isEmpty()) {
            sb.append("<div class=\"success-box\">\n");
            sb.append("<h2>✅ Couverture Complète</h2>\n");
            sb.append("<p>Tous les shifts sont couverts!</p>\n");
            sb.append("</div>\n");
        } else {
            sb.append("<div class=\"warning-box\">\n");
            sb.append("<h2>⚠️ Shifts Non Couverts (").append(uncovered.size()).append(")</h2>\n");
            sb.append("<table class=\"uncovered-table\">\n");
            sb.append("<tr><th>Date</th><th>Période</th><th>Type</th><th>Location</th><th>Skill</th><th>Couverture</th></tr>\n");

            for (Map.Entry<Shift, Integer> entry : uncovered) {
                Shift shift = entry.getKey();
                int covered = entry.getValue();

                sb.append("<tr>\n");
                sb.append("<td>").append(shift.getDate().format(DATE_FORMATTER)).append("</td>\n");
                sb.append("<td>").append(shift.getPeriodId() == 1 ? "Matin" : "Après-midi").append("</td>\n");
                sb.append("<td>").append(shift.getNeedType()).append("</td>\n");
                sb.append("<td>").append(escapeHtml(shift.getLocationName())).append("</td>\n");
                sb.append("<td>").append(escapeHtml(shift.getSkillName())).append("</td>\n");
                sb.append("<td class=\"coverage-bad\">").append(covered).append("/").append(shift.getQuantityNeeded()).append("</td>\n");
                sb.append("</tr>\n");
            }

            sb.append("</table>\n</div>\n");
        }
        sb.append("</div>\n");

        return sb.toString();
    }

    private static String buildLegend() {
        return """
            <div class="section legend">
                <h2>📖 Légende</h2>
                <div class="legend-items">
                    <span class="legend-item consultation">Consultation</span>
                    <span class="legend-item surgical">Chirurgie</span>
                    <span class="legend-item admin">Admin</span>
                    <span class="legend-item closing">Closing</span>
                    <span class="legend-item rest">Repos</span>
                </div>
            </div>
            """;
    }

    private static String getCSS() {
        return """
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body {
                font-family: 'Segoe UI', -apple-system, BlinkMacSystemFont, Roboto, sans-serif;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                min-height: 100vh;
                padding: 20px;
            }

            .header {
                text-align: center;
                color: white;
                padding: 30px 20px;
            }
            .header h1 {
                font-size: 2.5em;
                font-weight: 600;
                text-shadow: 2px 2px 4px rgba(0,0,0,0.2);
            }
            .subtitle {
                font-size: 1.2em;
                opacity: 0.9;
                margin-top: 10px;
            }

            .score-banner {
                background: white;
                border-radius: 16px;
                padding: 25px 30px;
                margin: 20px auto;
                max-width: 900px;
                display: flex;
                justify-content: space-between;
                align-items: center;
                box-shadow: 0 10px 40px rgba(0,0,0,0.15);
            }
            .score-banner.good { border-left: 6px solid #4caf50; }
            .score-banner.warning { border-left: 6px solid #ff9800; }
            .score-banner.bad { border-left: 6px solid #f44336; }
            .score-main { text-align: left; }
            .score-label { display: block; color: #666; font-size: 0.9em; text-transform: uppercase; letter-spacing: 1px; }
            .score-value { font-family: 'Courier New', monospace; font-size: 1.4em; font-weight: bold; color: #333; }
            .score-stats { display: flex; gap: 30px; }
            .stat-item { text-align: center; }
            .stat-number { display: block; font-size: 1.8em; font-weight: bold; color: #1a237e; }
            .stat-label { display: block; font-size: 0.8em; color: #666; text-transform: uppercase; }

            .dashboard { max-width: 1400px; margin: 0 auto; }
            .charts-row {
                display: grid;
                grid-template-columns: repeat(2, 1fr);
                gap: 20px;
                margin-bottom: 20px;
            }
            .chart-card {
                background: white;
                border-radius: 12px;
                padding: 20px;
                box-shadow: 0 4px 20px rgba(0,0,0,0.1);
            }
            .chart-card.full-width {
                grid-column: span 2;
            }
            .chart-card h3 {
                color: #333;
                margin-bottom: 15px;
                font-size: 1.1em;
                border-bottom: 2px solid #eee;
                padding-bottom: 10px;
            }

            .workload-table {
                width: 100%;
                border-collapse: collapse;
                font-size: 0.9em;
            }
            .workload-table th {
                background: #f5f5f5;
                padding: 12px 8px;
                text-align: left;
                font-weight: 600;
                color: #333;
                border-bottom: 2px solid #ddd;
            }
            .workload-table td {
                padding: 10px 8px;
                border-bottom: 1px solid #eee;
            }
            .workload-table .staff-name { font-weight: 500; }
            .workload-table .num { text-align: center; font-family: monospace; }
            .workload-table .consultation-cell { background: #e3f2fd; }
            .workload-table .surgical-cell { background: #fff3e0; }
            .workload-table .admin-cell { background: #f5f5f5; }
            .workload-table .porrentruy-cell { background: #e8f5e9; }
            .workload-table .closing-cell { background: #ffebee; }
            .workload-table .total-cell { background: #e8eaf6; font-weight: bold; }

            .section {
                background: white;
                border-radius: 12px;
                padding: 25px;
                margin: 20px auto;
                max-width: 1400px;
                box-shadow: 0 4px 20px rgba(0,0,0,0.1);
            }
            .section h2 {
                color: #1a237e;
                margin-bottom: 20px;
                font-size: 1.3em;
                border-bottom: 2px solid #e8eaf6;
                padding-bottom: 10px;
            }

            .site-card {
                background: #fafafa;
                border-radius: 8px;
                padding: 15px;
                margin-bottom: 15px;
            }
            .site-card h3 {
                color: #1a237e;
                margin-bottom: 10px;
                font-size: 1.1em;
            }
            .site-table {
                width: 100%;
                border-collapse: collapse;
            }
            .site-table th, .site-table td {
                padding: 10px 12px;
                text-align: left;
                border-bottom: 1px solid #e0e0e0;
            }
            .site-table th { background: #e8eaf6; font-weight: 500; font-size: 0.9em; }
            .date-cell { font-weight: 500; }
            .physician-cell { color: #1565c0; font-weight: 500; }
            .staff-am { background: #e3f2fd; }
            .staff-pm { background: #fff3e0; }

            .calendar-wrapper { overflow-x: auto; }
            .calendar {
                border-collapse: collapse;
                width: 100%;
                min-width: 800px;
            }
            .calendar th {
                background: #1a237e;
                color: white;
                padding: 12px 8px;
                text-align: center;
                font-weight: 500;
                font-size: 0.85em;
                position: sticky;
                top: 0;
            }
            .calendar th.staff-col { text-align: left; min-width: 140px; }
            .calendar td {
                border: 1px solid #e0e0e0;
                padding: 4px;
                vertical-align: top;
            }
            .calendar .staff-name {
                font-weight: 500;
                background: #fafafa;
                padding: 8px !important;
            }
            .day-cell { min-width: 110px; }

            .slot {
                padding: 4px 6px;
                margin: 2px 0;
                border-radius: 4px;
                font-size: 0.72em;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }
            .slot.am { border-left: 3px solid #2196F3; }
            .slot.pm { border-left: 3px solid #FF9800; }
            .slot.closing-fullday { border-left: 3px solid #c62828; background: #ffebee; color: #c62828; font-weight: 600; }
            .consultation { background: #e3f2fd; color: #1565c0; }
            .surgical { background: #fff3e0; color: #e65100; }
            .admin { background: #f5f5f5; color: #757575; }
            .closing { background: #ffebee; color: #c62828; font-weight: 600; }
            .rest { background: #e8f5e9; color: #2e7d32; }
            .empty { background: #fafafa; color: #bdbdbd; text-align: center; }
            .closing-badge {
                background: #c62828;
                color: white;
                padding: 1px 4px;
                border-radius: 3px;
                font-size: 0.8em;
                font-weight: bold;
            }

            .closing-table {
                width: 100%;
                border-collapse: collapse;
            }
            .closing-table th, .closing-table td {
                padding: 10px 12px;
                text-align: left;
                border-bottom: 1px solid #e0e0e0;
            }
            .closing-table th { background: #f5f5f5; font-weight: 500; }
            .closing-table tr.unassigned { background: #fff3e0; }
            .closing-table tr.unassigned td { color: #e65100; }
            .role-badge {
                display: inline-block;
                padding: 4px 10px;
                border-radius: 12px;
                font-weight: 600;
                font-size: 0.85em;
            }
            .role-badge.1r { background: #e3f2fd; color: #1565c0; }
            .role-badge.2f { background: #fff3e0; color: #e65100; }
            .role-badge.3f { background: #f3e5f5; color: #7b1fa2; }

            .success-box {
                background: #e8f5e9;
                border-left: 4px solid #4caf50;
                padding: 20px;
                border-radius: 8px;
            }
            .success-box h2 { color: #2e7d32; border: none; }
            .warning-box {
                background: #fff3e0;
                border-left: 4px solid #ff9800;
                padding: 20px;
                border-radius: 8px;
            }
            .warning-box h2 { color: #e65100; border: none; }

            .uncovered-table {
                width: 100%;
                border-collapse: collapse;
                margin-top: 15px;
            }
            .uncovered-table th, .uncovered-table td {
                padding: 8px 12px;
                text-align: left;
                border-bottom: 1px solid #ffcc80;
            }
            .uncovered-table th { background: #ffe0b2; font-weight: 500; }
            .coverage-bad { color: #d32f2f; font-weight: bold; font-family: monospace; }

            .legend {
                background: #f5f5f5;
            }
            .legend-items {
                display: flex;
                flex-wrap: wrap;
                gap: 10px;
            }
            .legend-item {
                padding: 8px 16px;
                border-radius: 20px;
                font-size: 0.9em;
                font-weight: 500;
            }

            @media (max-width: 900px) {
                .charts-row { grid-template-columns: 1fr; }
                .chart-card.full-width { grid-column: span 1; }
                .score-banner { flex-direction: column; gap: 20px; text-align: center; }
                .score-stats { justify-content: center; }
            }

            @media print {
                body { background: white; }
                .section { box-shadow: none; border: 1px solid #ddd; }
            }
            """;
    }

    private static String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() <= maxLength ? str : str.substring(0, maxLength - 1) + "…";
    }

    private static String escapeHtml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;");
    }

    private static String escapeJs(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("'", "\\'")
                  .replace("\"", "\\\"");
    }
}
