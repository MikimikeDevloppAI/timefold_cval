package com.scheduler.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;

import com.scheduler.domain.ClosingRole;
import com.scheduler.domain.ShiftSlot;
import com.scheduler.domain.Staff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Contraintes du staff scheduler.
 *
 * MODÈLE:
 * - ShiftSlot: 1 slot = 1 unit of coverage (staff @PlanningVariable)
 * - Closing roles are attributes on Shift (closingRoleEnum), carried by ShiftSlot
 *
 * HARD: Faisabilité (doit être 0)
 * MEDIUM: Couverture des shifts
 * SOFT: Préférences
 */
public class ScheduleConstraintProvider implements ConstraintProvider {

    private static final Logger log = LoggerFactory.getLogger(ScheduleConstraintProvider.class);

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        log.info("=== ScheduleConstraintProvider.defineConstraints() ===");
        return new Constraint[] {
            // =====================================================================
            // HARD - Feasibility
            // =====================================================================
            slotNoDoubleBooking(factory),      // HS4: Staff can't work two slots at same time
            staffAvailability(factory),        // H-AVAIL: Staff must be available for assigned slot
            staffSkillEligibility(factory),    // H-SKILL: Staff must have required skill
            staffSiteEligibility(factory),     // H-SITE: Staff must be able to work at site

            // =====================================================================
            // HARD - Closing constraints
            // =====================================================================
            closing1rDifferentFrom2f(factory),  // H-CLOSING: 1R ≠ 2F (same location/date)

            // =====================================================================
            // HARD - Staff must work if available (non-flexible)
            // =====================================================================
            staffMustBeAssigned(factory),            // H-STAFF-ASSIGNED: Non-flexible staff must work

            // =====================================================================
            // MEDIUM - Coverage
            // =====================================================================
            flexibleCorrectWorkDays(factory),        // M-FLEX-WORK: Flexible exact work days
            unassignedSurgicalPenalty(factory),      // M-UNASSIGNED-SURGICAL
            unassignedConsultationPenalty(factory),  // M-UNASSIGNED-CONSULTATION
            unassignedClosingPenalty(factory),       // M-CLOSING-UNASSIGNED

            // =====================================================================
            // SOFT - Preferences (Priorité 1: médecin + skill)
            // =====================================================================
            slotPhysicianPreference(factory),   // SS1: Bonus for preferred physician
            slotSkillPreference(factory),       // SS2: Bonus for preferred skill

            // =====================================================================
            // SOFT - Continuity (Priorité 2: site changes)
            // =====================================================================
            slotLocationContinuity(factory),    // SS3: Bonus for same location AM/PM
            slotSiteChangePenalty(factory),     // SS4: Pénalité changement de site

            // =====================================================================
            // SOFT - Fairness (Priorité 3) + Flexible reward
            // =====================================================================
            flexibleWorkDayReward(factory),     // S-FLEX-2: Encourage flexible assignment
            workloadFairness(factory),          // S-WORKLOAD: Closing + Porrentruy combinés
        };
    }

    // =========================================================================
    // HARD CONSTRAINTS
    // =========================================================================

    /**
     * HS4: Staff can't be assigned to two slots at the same time (same date/period).
     * Full-day slots (periodId=0 or fullDaySlot=true) conflict with both AM(1) and PM(2).
     */
    Constraint slotNoDoubleBooking(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> !slot.isAdmin()) // Admin slots are never double-booked (1:1 per staff)
            .join(ShiftSlot.class,
                Joiners.equal(slot -> slot.getStaff().getId(), slot -> slot.getStaff().getId()),
                Joiners.equal(ShiftSlot::getDate, ShiftSlot::getDate),
                Joiners.lessThan(ShiftSlot::getId, ShiftSlot::getId),
                Joiners.filtering((a, b) -> !b.isAdmin() && periodsConflict(a, b)))
            .penalize(HardMediumSoftScore.ofHard(100))
            .asConstraint("HS4: Slot no double booking");
    }

    /**
     * Check if two slots' periods conflict.
     * Full-day (period 0 or fullDaySlot) conflicts with any period.
     * Same period conflicts.
     */
    private static boolean periodsConflict(ShiftSlot a, ShiftSlot b) {
        if (a.isFullDay() || b.isFullDay()) return true;
        return a.getPeriodId() == b.getPeriodId();
    }

    /**
     * H-AVAIL: Staff must be available for the assigned slot's day/period.
     * For full-day slots (closing, REST), staff must be available both AM and PM.
     * This catches invalid assignments that bypass the move filter (e.g., via SwapMove).
     */
    Constraint staffAvailability(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> slot.getDate() != null)
            .filter(slot -> !isStaffAvailableForSlot(slot))
            .penalize(HardMediumSoftScore.ofHard(10000))
            .asConstraint("H-AVAIL: Staff must be available");
    }

    /**
     * Check if staff is available for the slot's period(s).
     */
    private static boolean isStaffAvailableForSlot(ShiftSlot slot) {
        if (slot.getStaff() == null || slot.getDate() == null) return true;
        int dayOfWeek = slot.getDate().getDayOfWeek().getValue();

        // Full-day slots require AM AND PM availability
        if (slot.isFullDay()) {
            return slot.getStaff().isAvailable(dayOfWeek, 1) &&
                   slot.getStaff().isAvailable(dayOfWeek, 2);
        }

        // Regular slots require availability for their period
        return slot.getStaff().isAvailable(dayOfWeek, slot.getPeriodId());
    }

    /**
     * H-SKILL: Staff must have the required skill for work slots (not admin/REST).
     */
    Constraint staffSkillEligibility(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> !slot.isAdmin() && !slot.isRest())
            .filter(slot -> slot.getSkillId() != null)
            .filter(slot -> !slot.getStaff().hasSkill(slot.getSkillId()))
            .penalize(HardMediumSoftScore.ofHard(10000))
            .asConstraint("H-SKILL: Staff must have required skill");
    }

    /**
     * H-SITE: Staff must be able to work at the slot's site (not admin/REST).
     */
    Constraint staffSiteEligibility(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> !slot.isAdmin() && !slot.isRest())
            .filter(slot -> slot.getSiteId() != null)
            .filter(slot -> !slot.getStaff().canWorkAtSite(slot.getSiteId()))
            .penalize(HardMediumSoftScore.ofHard(10000))
            .asConstraint("H-SITE: Staff must be able to work at site");
    }

    /**
     * H-CLOSING: 1R et 2F doivent être des personnes différentes (même location/date).
     */
    Constraint closing1rDifferentFrom2f(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> slot.getClosingRole() == ClosingRole.ROLE_1R)
            .join(ShiftSlot.class,
                Joiners.equal(ShiftSlot::getLocationId, ShiftSlot::getLocationId),
                Joiners.equal(ShiftSlot::getDate, ShiftSlot::getDate),
                Joiners.filtering((s1, s2) ->
                    s2.getStaff() != null &&
                    s2.getClosingRole() == ClosingRole.ROLE_2F &&
                    s1.getStaff().getId().equals(s2.getStaff().getId())))
            .penalize(HardMediumSoftScore.ofHard(10000))
            .asConstraint("H-CLOSING: 1R != 2F");
    }

    /**
     * H-STAFF-ASSIGNED: NON-FLEXIBLE staff must be assigned to exactly one slot per period.
     * Admin slots are 1:1 per non-flexible staff - if admin slot is unassigned, staff must have work.
     *
     * NOTE: Flexible staff use REST slots instead. Their assignment is handled by M-FLEX-REST/WORK.
     */
    Constraint staffMustBeAssigned(ConstraintFactory factory) {
        // Only applies to non-flexible staff (they have admin slots)
        // Penalize unassigned admin slots when staff has NO work assignment that period
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.isAdmin())
            .filter(slot -> slot.getStaff() == null) // Admin slot not filled
            .filter(slot -> slot.getDesignatedStaff() != null)
            // Check if this staff has ANY work assignment at the same date/period
            .ifNotExists(ShiftSlot.class,
                Joiners.filtering((adminSlot, otherSlot) ->
                    otherSlot.getStaff() != null &&
                    !otherSlot.isAdmin() && // Must be a work slot, not another admin
                    adminSlot.getDesignatedStaff().getId().equals(otherSlot.getStaff().getId()) &&
                    adminSlot.getDate().equals(otherSlot.getDate()) &&
                    (otherSlot.isFullDay() || adminSlot.getPeriodId() == otherSlot.getPeriodId())
                ))
            .penalize(HardMediumSoftScore.ofHard(1000))
            .asConstraint("H-STAFF-ASSIGNED: Non-flexible staff must be assigned");
    }

    // NOTE: flexibleMustBeAssigned() removed - REST is now implicit (no assignment = rest)
    // NOTE: flexibleCorrectRestDays() removed - REST is now implicit (no assignment = rest)

    // =========================================================================
    // MEDIUM CONSTRAINTS - Coverage
    // =========================================================================

    /**
     * M-FLEX-WORK: Flexible staff should work exactly daysPerWeek days.
     * Penalize if they work more or fewer days than their target.
     */
    Constraint flexibleCorrectWorkDays(ConstraintFactory factory) {
        return factory.forEach(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> slot.getStaff().isHasFlexibleSchedule())
            .filter(slot -> !slot.isAdmin() && !slot.isRest())
            .groupBy(ShiftSlot::getStaff,
                     ConstraintCollectors.toSet(ShiftSlot::getDate))
            .filter((staff, dates) -> {
                Integer daysPerWeek = staff.getDaysPerWeek();
                return daysPerWeek != null && dates.size() != daysPerWeek;
            })
            .penalize(HardMediumSoftScore.ofMedium(5000),
                      (staff, dates) -> Math.abs(dates.size() - staff.getDaysPerWeek()))
            .asConstraint("M-FLEX-WORK: Flexible exact work days");
    }

    /**
     * M-UNASSIGNED-SURGICAL: Pénalité pour chaque slot chirurgical non couvert.
     */
    Constraint unassignedSurgicalPenalty(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() == null)
            .filter(ShiftSlot::isSurgical)
            .penalize(HardMediumSoftScore.ofMedium(15000))
            .asConstraint("M-UNASSIGNED-SURGICAL: Uncovered surgical slot");
    }

    /**
     * M-UNASSIGNED-CONSULTATION: Pénalité pour chaque slot consultation non couvert.
     */
    Constraint unassignedConsultationPenalty(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() == null)
            .filter(ShiftSlot::isConsultation)
            .filter(slot -> !slot.hasClosingRole()) // Closing slots have their own penalty
            .penalize(HardMediumSoftScore.ofMedium(10000))
            .asConstraint("M-UNASSIGNED-CONSULTATION: Uncovered consultation slot");
    }

    /**
     * M-CLOSING-UNASSIGNED: Penalty for each unassigned closing slot.
     */
    Constraint unassignedClosingPenalty(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() == null)
            .filter(ShiftSlot::hasClosingRole)
            .penalize(HardMediumSoftScore.ofMedium(10000))
            .asConstraint("M-CLOSING-UNASSIGNED: Unassigned closing responsibility");
    }

    // =========================================================================
    // SOFT CONSTRAINTS - Preferences (Priorité 1)
    // =========================================================================

    /**
     * SS1: Bonus if staff works with a preferred physician.
     */
    Constraint slotPhysicianPreference(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> slot.getShift() != null)
            .filter(slot -> slot.getShift().getPhysicianIds() != null && !slot.getShift().getPhysicianIds().isEmpty())
            .reward(HardMediumSoftScore.ofSoft(1),
                slot -> {
                    Set<UUID> shiftPhysicians = slot.getShift().getPhysicianIds();
                    int bestPriority = 0;
                    for (UUID physicianId : shiftPhysicians) {
                        int priority = slot.getStaff().getPhysicianPriority(physicianId);
                        if (priority > 0 && (bestPriority == 0 || priority < bestPriority)) {
                            bestPriority = priority;
                        }
                    }
                    // P1=100000, P2=60000, P3=30000 (Priorité 1 - la plus haute)
                    if (bestPriority == 1) return 100000;
                    if (bestPriority == 2) return 60000;
                    if (bestPriority == 3) return 30000;
                    return 0;
                })
            .asConstraint("SS1: Slot physician preference");
    }

    /**
     * SS2: Bonus if staff works with a preferred skill.
     */
    Constraint slotSkillPreference(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .reward(HardMediumSoftScore.ofSoft(1),
                slot -> {
                    int skillPref = slot.getSkillPreference(); // 1-4, 0 if none
                    // P1=80000, P2=60000, P3=40000, P4=20000 (Priorité 1 - la plus haute)
                    if (skillPref == 1) return 80000;
                    if (skillPref == 2) return 60000;
                    if (skillPref == 3) return 40000;
                    if (skillPref == 4) return 20000;
                    return 0;
                })
            .asConstraint("SS2: Slot skill preference");
    }

    // =========================================================================
    // SOFT CONSTRAINTS - Continuity (Priorité 2)
    // =========================================================================

    /**
     * SS3: Bonus if staff stays at the same location between AM and PM.
     */
    Constraint slotLocationContinuity(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> slot.getPeriodId() == 1)  // AM only to avoid double counting
            .filter(slot -> slot.getLocationId() != null)
            .join(ShiftSlot.class,
                Joiners.equal(slot -> slot.getStaff().getId(), slot -> slot.getStaff().getId()),
                Joiners.equal(ShiftSlot::getDate, ShiftSlot::getDate),
                Joiners.filtering((am, pm) -> pm.getPeriodId() == 2))
            .filter((am, pm) -> pm.getStaff() != null)
            .filter((am, pm) -> pm.getLocationId() != null)
            .filter((am, pm) -> am.getLocationId().equals(pm.getLocationId()))
            .reward(HardMediumSoftScore.ofSoft(5000))  // Priorité 2
            .asConstraint("SS3: Slot location continuity");
    }

    /**
     * SS4: Pénalité si staff change de site entre AM et PM.
     */
    Constraint slotSiteChangePenalty(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> slot.getPeriodId() == 1)  // AM only
            .filter(slot -> slot.getSiteId() != null)
            .join(ShiftSlot.class,
                Joiners.equal(slot -> slot.getStaff().getId(), slot -> slot.getStaff().getId()),
                Joiners.equal(ShiftSlot::getDate, ShiftSlot::getDate),
                Joiners.filtering((am, pm) -> pm.getPeriodId() == 2))
            .filter((am, pm) -> pm.getStaff() != null)
            .filter((am, pm) -> pm.getSiteId() != null)
            .filter((am, pm) -> !am.getSiteId().equals(pm.getSiteId()))
            .penalize(HardMediumSoftScore.ofSoft(5000))  // Priorité 2
            .asConstraint("SS4: Slot site change penalty");
    }

    // =========================================================================
    // SOFT CONSTRAINTS - Fairness (Priorité 3) + Flexible
    // =========================================================================

    /**
     * S-FLEX-2: Reward pour chaque jour travaillé par un flexible.
     */
    Constraint flexibleWorkDayReward(ConstraintFactory factory) {
        return factory.forEach(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> slot.getStaff().isHasFlexibleSchedule())
            .filter(slot -> !slot.isAdmin() && !slot.isRest())
            .groupBy(ShiftSlot::getStaff,
                     ConstraintCollectors.toSet(ShiftSlot::getDate))
            .reward(HardMediumSoftScore.ofSoft(2000),
                    (staff, dates) -> dates.size())
            .asConstraint("S-FLEX-2: Flexible work day reward");
    }

    /**
     * S-WORKLOAD: Charge de travail combinée (closing + Porrentruy).
     *
     * Closing charge (from ShiftSlot.closingRole):
     *   - 1R = 10 points
     *   - 2F = 13 points
     *
     * Porrentruy charge (si pas pref 1):
     *   - 10 points par jour au-delà de 1 jour
     */
    Constraint workloadFairness(ConstraintFactory factory) {
        // Stream 1: Closing charges per staff from ShiftSlot.closingRole
        var closingCharges = factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(ShiftSlot::hasClosingRole)
            .filter(slot -> slot.getClosingRole() != ClosingRole.ROLE_3F)
            .groupBy(ShiftSlot::getStaff,
                ConstraintCollectors.sum(slot -> {
                    ClosingRole role = slot.getClosingRole();
                    if (role == ClosingRole.ROLE_1R) return 10;
                    if (role == ClosingRole.ROLE_2F) return 13;
                    return 0;
                }));

        // Stream 2: Porrentruy charges per staff
        var porrentruyCharges = factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> !slot.isAdmin() && !slot.isRest())
            .filter(slot -> "Porrentruy".equalsIgnoreCase(slot.getSiteName()))
            .filter(slot -> slot.getStaff().getSitePriority(slot.getSiteId()) != 1)
            .groupBy(ShiftSlot::getStaff,
                ConstraintCollectors.toSet(ShiftSlot::getDate))
            .groupBy((staff, dates) -> staff,
                ConstraintCollectors.sum((staff, dates) ->
                    dates.size() > 1 ? (dates.size() - 1) * 10 : 0));

        // Combine both streams and apply quadratic penalty (÷10 - Priorité 3)
        return closingCharges.concat(porrentruyCharges)
            .groupBy((staff, charge) -> staff,
                ConstraintCollectors.sum((staff, charge) -> charge))
            .penalize(HardMediumSoftScore.ofSoft(1),
                (staff, totalCharge) -> (totalCharge * totalCharge) / 10)
            .asConstraint("S-WORKLOAD: Fairness");
    }
}
