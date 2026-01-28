package com.scheduler.solver;

import ai.timefold.solver.core.api.domain.variable.VariableListener;
import ai.timefold.solver.core.api.score.director.ScoreDirector;
import com.scheduler.domain.ScheduleSolution;
import com.scheduler.domain.ShiftSlot;
import com.scheduler.domain.Staff;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Shadow variable listener that calculates the number of distinct days
 * a staff member works based on their ShiftSlot assignments.
 *
 * This enables efficient constraint checking for flexible staff
 * who have a maximum number of work days per week.
 */
public class WorkDayCountListener implements VariableListener<ScheduleSolution, ShiftSlot> {

    @Override
    public void beforeEntityAdded(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        // No-op
    }

    @Override
    public void afterEntityAdded(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        updateWorkDayCount(scoreDirector, slot.getStaff());
    }

    @Override
    public void beforeVariableChanged(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        // No-op
    }

    @Override
    public void afterVariableChanged(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        updateWorkDayCount(scoreDirector, slot.getStaff());
    }

    @Override
    public void beforeEntityRemoved(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        // No-op
    }

    @Override
    public void afterEntityRemoved(ScoreDirector<ScheduleSolution> scoreDirector, ShiftSlot slot) {
        updateWorkDayCount(scoreDirector, slot.getStaff());
    }

    /**
     * Updates the staffWorkDayCount shadow variable for all slots assigned to this staff.
     */
    private void updateWorkDayCount(ScoreDirector<ScheduleSolution> scoreDirector, Staff staff) {
        if (staff == null) {
            return;
        }

        ScheduleSolution solution = scoreDirector.getWorkingSolution();

        // Count distinct days where this staff works
        Set<LocalDate> workDays = new HashSet<>();
        for (ShiftSlot slot : solution.getShiftSlots()) {
            if (staff.equals(slot.getStaff()) && slot.getDate() != null) {
                workDays.add(slot.getDate());
            }
        }
        Integer newCount = workDays.size();

        // Update all slots for this staff
        for (ShiftSlot slot : solution.getShiftSlots()) {
            if (staff.equals(slot.getStaff())) {
                if (!Objects.equals(newCount, slot.getStaffWorkDayCount())) {
                    scoreDirector.beforeVariableChanged(slot, "staffWorkDayCount");
                    slot.setStaffWorkDayCount(newCount);
                    scoreDirector.afterVariableChanged(slot, "staffWorkDayCount");
                }
            }
        }
    }
}
