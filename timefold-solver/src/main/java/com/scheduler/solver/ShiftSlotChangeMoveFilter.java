package com.scheduler.solver;

import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.ChangeMove;
import ai.timefold.solver.core.impl.heuristic.move.Move;

import com.scheduler.domain.ScheduleSolution;
import com.scheduler.domain.ShiftSlot;
import com.scheduler.domain.Staff;

/**
 * Filtre les moves pour ShiftSlot.
 *
 * Rejette les assignations où le staff:
 * - N'a pas la compétence requise (sauf admin/rest)
 * - Ne peut pas travailler sur ce site (sauf admin/rest)
 * - N'est pas disponible ce jour/période
 *
 * Ceci élimine le besoin des contraintes HS1, HS2, HS3
 * et réduit considérablement l'espace de recherche.
 */
public class ShiftSlotChangeMoveFilter implements SelectionFilter<ScheduleSolution, Move<ScheduleSolution>> {

    @Override
    public boolean accept(ScoreDirector<ScheduleSolution> scoreDirector, Move<ScheduleSolution> move) {
        // Cast direct au lieu de reflection (beaucoup plus rapide)
        if (!(move instanceof ChangeMove<?> changeMove)) {
            return true; // Pas un ChangeMove, accepter
        }

        Object entity = changeMove.getEntity();
        if (!(entity instanceof ShiftSlot slot)) {
            return true; // Pas un ShiftSlot, accepter
        }

        Object toPlanningValue = changeMove.getToPlanningValue();
        if (toPlanningValue == null) {
            return true; // Désassignation, toujours OK (allowsUnassigned=true)
        }

        if (!(toPlanningValue instanceof Staff staff)) {
            return true; // Pas un Staff, accepter
        }

        return isEligible(slot, staff);
    }

    /**
     * Vérifie si un staff est éligible pour un slot.
     */
    private boolean isEligible(ShiftSlot slot, Staff staff) {
        if (slot.getShift() == null) {
            return true; // Pas de shift, accepter
        }

        // Admin with designated staff: only that specific staff member
        if (slot.isAdmin()) {
            Staff designated = slot.getDesignatedStaff();
            if (designated != null) {
                return staff.getId().equals(designated.getId());
            }
            return checkAvailability(slot, staff);
        }

        // REST slots should not exist with new model (repos implicite)
        // But keep check for safety - reject any REST assignment
        if (slot.isRest()) {
            return false;
        }

        // Normal + Closing shifts: vérifier skill, site et disponibilité
        // Note: closing slots are full-day (fullDaySlot=true)

        // 1. Skill check
        if (!staff.hasSkill(slot.getSkillId())) {
            return false;
        }

        // 2. Site check
        if (!staff.canWorkAtSite(slot.getSiteId())) {
            return false;
        }

        // 3. Availability check (handles full-day slots)
        return checkAvailability(slot, staff);
    }

    /**
     * Check staff availability for the slot's period.
     * For full-day slots (fullDaySlot=true or periodId=0), staff must be available BOTH AM and PM.
     */
    private boolean checkAvailability(ShiftSlot slot, Staff staff) {
        if (slot.getDate() == null) {
            return true;
        }
        int dayOfWeek = slot.getDate().getDayOfWeek().getValue();

        // Full day (fullDaySlot=true or periodId=0): must be available AM AND PM
        if (slot.isFullDay()) {
            return staff.isAvailable(dayOfWeek, 1) && staff.isAvailable(dayOfWeek, 2);
        }

        // Regular period (1=AM, 2=PM)
        return staff.isAvailable(dayOfWeek, slot.getPeriodId());
    }
}
