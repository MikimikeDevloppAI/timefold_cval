package com.scheduler.solver;

import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SwapMove;
import ai.timefold.solver.core.impl.heuristic.move.Move;

import com.scheduler.domain.ScheduleSolution;
import com.scheduler.domain.ShiftSlot;
import com.scheduler.domain.Staff;

/**
 * Filtre les SwapMoves pour éviter les échanges invalides.
 *
 * Rejette un swap si APRÈS le swap:
 * - Un staff serait assigné à un slot où il n'est pas éligible (skill/site/disponibilité)
 * - Un non-flexible serait sur un slot admin d'un autre staff
 */
public class ShiftSlotSwapMoveFilter implements SelectionFilter<ScheduleSolution, Move<ScheduleSolution>> {

    @Override
    public boolean accept(ScoreDirector<ScheduleSolution> scoreDirector, Move<ScheduleSolution> move) {
        if (!(move instanceof SwapMove<?> swapMove)) {
            return true;
        }

        Object leftEntity = swapMove.getLeftEntity();
        Object rightEntity = swapMove.getRightEntity();

        if (!(leftEntity instanceof ShiftSlot leftSlot) || !(rightEntity instanceof ShiftSlot rightSlot)) {
            return true;
        }

        Staff leftStaff = leftSlot.getStaff();
        Staff rightStaff = rightSlot.getStaff();

        // After swap: leftSlot gets rightStaff, rightSlot gets leftStaff
        // Check if rightStaff is eligible for leftSlot
        if (rightStaff != null && !isEligible(leftSlot, rightStaff)) {
            return false;
        }

        // Check if leftStaff is eligible for rightSlot
        if (leftStaff != null && !isEligible(rightSlot, leftStaff)) {
            return false;
        }

        return true;
    }

    /**
     * Vérifie si un staff est éligible pour un slot.
     */
    private boolean isEligible(ShiftSlot slot, Staff staff) {
        if (slot.getShift() == null) return true;

        // Admin slots: only designated staff
        if (slot.isAdmin()) {
            Staff designated = slot.getDesignatedStaff();
            if (designated != null) {
                return staff.getId().equals(designated.getId());
            }
            return checkAvailability(slot, staff);
        }

        // REST slots should not exist with the new model (repos implicite)
        // But keep check for safety - reject any REST assignment
        if (slot.isRest()) {
            return false;
        }

        // Normal + Closing shifts: check skill, site, availability

        // 1. Skill check
        if (slot.getSkillId() != null && !staff.hasSkill(slot.getSkillId())) {
            return false;
        }

        // 2. Site check
        if (slot.getSiteId() != null && !staff.canWorkAtSite(slot.getSiteId())) {
            return false;
        }

        // 3. Availability check
        return checkAvailability(slot, staff);
    }

    /**
     * Check staff availability for the slot's period.
     * For full-day slots, staff must be available BOTH AM and PM.
     */
    private boolean checkAvailability(ShiftSlot slot, Staff staff) {
        if (slot.getDate() == null) return true;
        int dayOfWeek = slot.getDate().getDayOfWeek().getValue();

        // Full day (fullDaySlot=true or periodId=0): must be available AM AND PM
        if (slot.isFullDay()) {
            return staff.isAvailable(dayOfWeek, 1) && staff.isAvailable(dayOfWeek, 2);
        }

        // Regular period (1=AM, 2=PM)
        return staff.isAvailable(dayOfWeek, slot.getPeriodId());
    }
}
