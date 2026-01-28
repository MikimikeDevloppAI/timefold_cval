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

        // Admin/Rest: seulement vérifier la disponibilité
        if (slot.isAdmin() || slot.isRest()) {
            return checkAvailability(slot, staff);
        }

        // Closing shifts: vérifier skill, site et disponibilité (comme shifts normaux)
        // Note: closing slots peuvent avoir periodId=0 (full day)

        // 1. Skill check (closing uses the skill from the location's consultation shifts)
        if (!staff.hasSkill(slot.getSkillId())) {
            return false;
        }

        // 2. Site check
        if (!staff.canWorkAtSite(slot.getSiteId())) {
            return false;
        }

        // 3. Availability check (handles periodId=0 for full day)
        return checkAvailability(slot, staff);
    }

    /**
     * Check staff availability for the slot's period.
     * For periodId=0 (full day), staff must be available BOTH AM and PM.
     */
    private boolean checkAvailability(ShiftSlot slot, Staff staff) {
        if (slot.getDate() == null) {
            return true;
        }
        int dayOfWeek = slot.getDate().getDayOfWeek().getValue();
        int periodId = slot.getPeriodId();

        // Full day (periodId=0): must be available AM AND PM
        if (periodId == 0) {
            return staff.isAvailable(dayOfWeek, 1) && staff.isAvailable(dayOfWeek, 2);
        }

        // Regular period (1=AM, 2=PM)
        return staff.isAvailable(dayOfWeek, periodId);
    }
}
