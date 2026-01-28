package com.scheduler.solver;

import ai.timefold.solver.core.api.domain.variable.VariableListener;
import ai.timefold.solver.core.api.score.director.ScoreDirector;
import com.scheduler.domain.ScheduleSolution;
import com.scheduler.domain.ShiftSlot;
import com.scheduler.domain.Staff;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Shadow variable listener that calculates whether a staff member
 * is working a full day (both AM and PM) on a given date.
 *
 * This enables efficient constraint checking for flexible staff
 * who must work either full days or not at all.
 */
public class FullDayWorkListener implements VariableListener<ScheduleSolution, ShiftSlot> {

    @Override
    public void beforeEntityAdded(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        // No-op
    }

    @Override
    public void afterEntityAdded(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        updateFullDayWork(scoreDirector, slot.getStaff(), slot.getDate());
    }

    @Override
    public void beforeVariableChanged(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        // No-op
    }

    @Override
    public void afterVariableChanged(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        updateFullDayWork(scoreDirector, slot.getStaff(), slot.getDate());
    }

    @Override
    public void beforeEntityRemoved(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        // No-op
    }

    @Override
    public void afterEntityRemoved(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        updateFullDayWork(scoreDirector, slot.getStaff(), slot.getDate());
    }

    /**
     * Updates the isWorkingFullDay shadow variable for all slots of this staff on this date.
     */
    private void updateFullDayWork(ScoreDirector<ScheduleSolution> scoreDirector, Staff staff, LocalDate date) {
        if (staff == null || date == null) {
            return;
        }

        ScheduleSolution solution = scoreDirector.getWorkingSolution();

        // Check if staff works both AM and PM on this date
        boolean hasAM = false;
        boolean hasPM = false;
        for (ShiftSlot slot : solution.getShiftSlots()) {
            if (staff.equals(slot.getStaff()) && date.equals(slot.getDate())) {
                if (slot.getPeriodId() == 1) {
                    hasAM = true;
                }
                if (slot.getPeriodId() == 2) {
                    hasPM = true;
                }
            }
        }
        Boolean newValue = hasAM && hasPM;

        // Update all slots for this staff/date
        for (ShiftSlot slot : solution.getShiftSlots()) {
            if (staff.equals(slot.getStaff()) && date.equals(slot.getDate())) {
                if (!Objects.equals(newValue, slot.getIsWorkingFullDay())) {
                    scoreDirector.beforeVariableChanged(slot, "isWorkingFullDay");
                    slot.setIsWorkingFullDay(newValue);
                    scoreDirector.afterVariableChanged(slot, "isWorkingFullDay");
                }
            }
        }
    }
}
