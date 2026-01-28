package com.scheduler.domain;

import java.util.Comparator;

/**
 * Comparateur de difficulté pour StaffAssignment.
 *
 * OBJECTIF: Traiter les assignations dans un ordre qui favorise H6 (flexible exact days).
 *
 * Tri par:
 * 1. Staff flexibles EN DERNIER (après les non-flexibles) - car ils ont plus de choix (REST possible)
 * 2. Par date croissante
 * 3. AM (1) avant PM (2) pour le même jour
 * 4. Par staff ID pour grouper les assignations du même staff
 *
 * Ainsi le CH traitera d'abord tous les non-flexibles (qui n'ont pas le choix),
 * puis les flexibles par jour (AM puis PM du même jour ensemble).
 */
public class StaffAssignmentDifficultyComparator implements Comparator<StaffAssignment> {

    @Override
    public int compare(StaffAssignment a, StaffAssignment b) {
        // 1. Non-flexible first (they have no REST option, so more constrained)
        boolean aFlex = a.getStaff().isHasFlexibleSchedule();
        boolean bFlex = b.getStaff().isHasFlexibleSchedule();
        if (aFlex != bFlex) {
            return Boolean.compare(aFlex, bFlex); // false (non-flex) before true (flex)
        }

        // 2. For flexible staff: group by staff to treat AM+PM together
        if (aFlex && bFlex) {
            int staffCompare = a.getStaff().getId().compareTo(b.getStaff().getId());
            if (staffCompare != 0) return staffCompare;
        }

        // 3. By date
        int dateCompare = a.getDate().compareTo(b.getDate());
        if (dateCompare != 0) return dateCompare;

        // 4. AM (1) before PM (2)
        int periodCompare = Integer.compare(a.getPeriodId(), b.getPeriodId());
        if (periodCompare != 0) return periodCompare;

        // 5. Final tie-breaker: fewer valid shifts = more difficult (should be treated first)
        int aValid = a.getValidShifts() != null ? a.getValidShifts().size() : 0;
        int bValid = b.getValidShifts() != null ? b.getValidShifts().size() : 0;
        return Integer.compare(aValid, bValid);
    }
}
