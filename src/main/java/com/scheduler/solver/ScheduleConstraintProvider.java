package com.scheduler.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;

import com.scheduler.domain.ClosingAssignment;
import com.scheduler.domain.ClosingRole;
import com.scheduler.domain.ShiftSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

/**
 * Contraintes du staff scheduler.
 *
 * MODÈLE:
 * - ShiftSlot: 1 slot = 1 unit of coverage (staff @PlanningVariable)
 * - ClosingAssignment: 1 per location/date/role (staff @PlanningVariable)
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
            // SHIFT SLOT CONSTRAINTS
            // =====================================================================

            // HARD - ShiftSlot feasibility
            // HS1, HS2, HS3: SUPPRIMÉS - filtrés via ShiftSlotChangeMoveFilter
            slotNoDoubleBooking(factory),      // HS4: Staff can't work two slots at same time

            // MEDIUM/SOFT - Flexible staff constraints (using groupBy)
            flexibleCorrectDaysOff(factory),   // M-FLEX-1: Flexible max work days
            flexibleWorkDayReward(factory),    // S-FLEX-2: Encourage flexible assignment

            // MEDIUM - ShiftSlot coverage (simple negative penalties)
            unassignedSurgicalPenalty(factory),     // M-UNASSIGNED-SURGICAL
            unassignedConsultationPenalty(factory), // M-UNASSIGNED-CONSULTATION

            // SOFT - ShiftSlot preferences
            slotPhysicianPreference(factory),  // SS1: Bonus for preferred physician
            slotSkillPreference(factory),      // SS2: Bonus for preferred skill
            slotLocationContinuity(factory),   // SS3: Bonus for same location AM/PM

            // =====================================================================
            // CLOSING CONSTRAINTS (ClosingAssignment entity)
            // =====================================================================

            closingStaffMustWorkFullDayAM(factory),  // H-CLOSING-FULLDAY-AM: Must work AM at location
            closingStaffMustWorkFullDayPM(factory),  // H-CLOSING-FULLDAY-PM: Must work PM at location
            closing1rDifferentFrom2f(factory),       // H-CLOSING: 1R ≠ 2F (HARD)
            unassignedClosingPenalty(factory),       // M-CLOSING-UNASSIGNED: Penalty for unassigned

            // SOFT - workload fairness
            slotSiteChangePenalty(factory),      // SS4: Pénalité changement de site
            workloadFairness(factory),           // S-WORKLOAD: Closing + Porrentruy combinés
        };
    }

    // =========================================================================
    // SHIFT SLOT CONSTRAINTS - HARD (NEW MODEL)
    // =========================================================================

    // NOTE: HS1 (skill eligibility), HS2 (site eligibility), HS3 (staff availability)
    // ont été SUPPRIMÉS car filtrés en amont via ShiftSlotChangeMoveFilter.
    // Le filtre rejette les moves invalides AVANT que le solver les considère,
    // réduisant ainsi l'espace de recherche et accélérant le solving.

    /**
     * HS4: Staff can't be assigned to two slots at the same time (same date/period).
     * This prevents double-booking.
     */
    Constraint slotNoDoubleBooking(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .join(ShiftSlot.class,
                Joiners.equal(slot -> slot.getStaff().getId(), slot -> slot.getStaff().getId()),
                Joiners.equal(ShiftSlot::getDate, ShiftSlot::getDate),
                Joiners.equal(ShiftSlot::getPeriodId, ShiftSlot::getPeriodId),
                Joiners.lessThan(ShiftSlot::getId, ShiftSlot::getId))  // Avoid counting twice
            .penalize(HardMediumSoftScore.ofHard(100))
            .asConstraint("HS4: Slot no double booking");
    }

    /**
     * M-FLEX-1: Flexible staff - ne doit pas dépasser daysPerWeek jours travaillés.
     *
     * Un jour travaillé = au moins 1 slot assigné (AM ou PM ou les deux).
     * Utilise groupBy avec toSet pour collecter les dates uniques par staff.
     *
     * Ex: daysPerWeek=3 → peut travailler max 3 jours (seulement AM ou AM+PM OK)
     */
    Constraint flexibleCorrectDaysOff(ConstraintFactory factory) {
        return factory.forEach(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> slot.getStaff().isHasFlexibleSchedule())
            .filter(slot -> !slot.isAdmin() && !slot.isRest())
            // Grouper par staff, collecter les dates uniques
            .groupBy(ShiftSlot::getStaff,
                     ConstraintCollectors.toSet(ShiftSlot::getDate))
            // Vérifier si jours travaillés > daysPerWeek
            .filter((staff, dates) -> {
                Integer daysPerWeek = staff.getDaysPerWeek();
                return daysPerWeek != null && dates.size() > daysPerWeek;
            })
            // Pénalité proportionnelle au dépassement
            .penalize(HardMediumSoftScore.ofMedium(5000),
                      (staff, dates) -> dates.size() - staff.getDaysPerWeek())
            .asConstraint("M-FLEX-1: Flexible max work days");
    }

    /**
     * S-FLEX-2: Reward pour chaque jour travaillé par un flexible.
     * Encourage le solver à assigner les flexibles (jusqu'à daysPerWeek).
     * Un reward par jour travaillé (pas par slot).
     */
    Constraint flexibleWorkDayReward(ConstraintFactory factory) {
        return factory.forEach(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> slot.getStaff().isHasFlexibleSchedule())
            .filter(slot -> !slot.isAdmin() && !slot.isRest())
            // Grouper par staff, collecter les dates uniques
            .groupBy(ShiftSlot::getStaff,
                     ConstraintCollectors.toSet(ShiftSlot::getDate))
            // Reward pour chaque jour travaillé (taille du set)
            .reward(HardMediumSoftScore.ofSoft(2000),
                    (staff, dates) -> dates.size())
            .asConstraint("S-FLEX-2: Flexible work day reward");
    }

    // =========================================================================
    // SHIFT SLOT CONSTRAINTS - MEDIUM (Coverage - simple negative penalties)
    // =========================================================================

    /**
     * M-UNASSIGNED-SURGICAL: Pénalité pour chaque slot chirurgical non couvert.
     */
    Constraint unassignedSurgicalPenalty(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() == null)
            .filter(ShiftSlot::isSurgical)
            .penalize(HardMediumSoftScore.ofMedium(1500))
            .asConstraint("M-UNASSIGNED-SURGICAL: Uncovered surgical slot");
    }

    /**
     * M-UNASSIGNED-CONSULTATION: Pénalité pour chaque slot consultation non couvert.
     */
    Constraint unassignedConsultationPenalty(ConstraintFactory factory) {
        return factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() == null)
            .filter(ShiftSlot::isConsultation)
            .penalize(HardMediumSoftScore.ofMedium(1000))
            .asConstraint("M-UNASSIGNED-CONSULTATION: Uncovered consultation slot");
    }

    // NOTE: unassignedClosingPenalty removed - closing is now via closingRole variable
    // Missing closing roles are penalized via exactly1RPerLocationDate/exactly2FPerLocationDate

    // =========================================================================
    // SHIFT SLOT CONSTRAINTS - SOFT (Preferences)
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
                    // P1=100, P2=60, P3=30
                    if (bestPriority == 1) return 100;
                    if (bestPriority == 2) return 60;
                    if (bestPriority == 3) return 30;
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
                    // P1=80, P2=60, P3=40, P4=20, none=0
                    if (skillPref == 1) return 80;
                    if (skillPref == 2) return 60;
                    if (skillPref == 3) return 40;
                    if (skillPref == 4) return 20;
                    return 0;
                })
            .asConstraint("SS2: Slot skill preference");
    }

    /**
     * SS3: Bonus if staff stays at the same location between AM and PM.
     * Encourages continuity to avoid travel between locations.
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
            .reward(HardMediumSoftScore.ofSoft(50))  // Bonus for same location
            .asConstraint("SS3: Slot location continuity");
    }

    // =========================================================================
    // CLOSING CONSTRAINTS (ClosingAssignment entity)
    // =========================================================================

    /**
     * H-CLOSING-FULLDAY-AM: Staff with closing must work the AM shift at that location.
     * Combined with H-CLOSING-FULLDAY-PM, this ensures closing staff works the FULL day.
     */
    Constraint closingStaffMustWorkFullDayAM(ConstraintFactory factory) {
        return factory.forEach(ClosingAssignment.class)
            .filter(ca -> ca.getStaff() != null)
            .ifNotExists(ShiftSlot.class,
                Joiners.equal(ca -> ca.getStaff().getId(), slot -> slot.getStaff() != null ? slot.getStaff().getId() : null),
                Joiners.equal(ClosingAssignment::getLocationId, ShiftSlot::getLocationId),
                Joiners.equal(ClosingAssignment::getDate, ShiftSlot::getDate),
                Joiners.equal(ca -> 1, ShiftSlot::getPeriodId))  // AM = periodId 1
            .penalize(HardMediumSoftScore.ofHard(10000))
            .asConstraint("H-CLOSING-FULLDAY-AM: Closing staff must work AM");
    }

    /**
     * H-CLOSING-FULLDAY-PM: Staff with closing must work the PM shift at that location.
     * Combined with H-CLOSING-FULLDAY-AM, this ensures closing staff works the FULL day.
     */
    Constraint closingStaffMustWorkFullDayPM(ConstraintFactory factory) {
        return factory.forEach(ClosingAssignment.class)
            .filter(ca -> ca.getStaff() != null)
            .ifNotExists(ShiftSlot.class,
                Joiners.equal(ca -> ca.getStaff().getId(), slot -> slot.getStaff() != null ? slot.getStaff().getId() : null),
                Joiners.equal(ClosingAssignment::getLocationId, ShiftSlot::getLocationId),
                Joiners.equal(ClosingAssignment::getDate, ShiftSlot::getDate),
                Joiners.equal(ca -> 2, ShiftSlot::getPeriodId))  // PM = periodId 2
            .penalize(HardMediumSoftScore.ofHard(10000))
            .asConstraint("H-CLOSING-FULLDAY-PM: Closing staff must work PM");
    }

    /**
     * H-CLOSING: 1R et 2F doivent être des personnes différentes (même location/date).
     * Uses ClosingAssignment entity.
     */
    Constraint closing1rDifferentFrom2f(ConstraintFactory factory) {
        return factory.forEach(ClosingAssignment.class)
            .filter(ca -> ca.getStaff() != null)
            .filter(ca -> ca.getRole() == ClosingRole.ROLE_1R)
            .join(ClosingAssignment.class,
                Joiners.equal(ClosingAssignment::getLocationId),
                Joiners.equal(ClosingAssignment::getDate),
                Joiners.filtering((ca1, ca2) ->
                    ca2.getRole() == ClosingRole.ROLE_2F &&
                    ca2.getStaff() != null &&
                    ca1.getStaff().getId().equals(ca2.getStaff().getId())))
            .penalize(HardMediumSoftScore.ofHard(10000))
            .asConstraint("H-CLOSING: 1R != 2F");
    }

    /**
     * M-CLOSING-UNASSIGNED: Penalty for each unassigned ClosingAssignment.
     */
    Constraint unassignedClosingPenalty(ConstraintFactory factory) {
        return factory.forEach(ClosingAssignment.class)
            .filter(ca -> ca.getStaff() == null)
            .penalize(HardMediumSoftScore.ofMedium(2000))
            .asConstraint("M-CLOSING-UNASSIGNED: Unassigned closing responsibility");
    }

    // =========================================================================
    // ADDITIONAL SOFT CONSTRAINTS
    // =========================================================================

    /**
     * SS4: Pénalité si staff change de site entre AM et PM.
     * Uses ShiftSlot instead of StaffAssignment.
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
            .penalize(HardMediumSoftScore.ofSoft(20))
            .asConstraint("SS4: Slot site change penalty");
    }

    /**
     * S-WORKLOAD: Charge de travail combinée (closing + Porrentruy).
     *
     * Formule: Pénalité = charge² où charge = closingCharge + porrentruyCharge
     *
     * Closing charge (from ClosingAssignment):
     *   - 1R = 10 points
     *   - 2F = 13 points (1.3× plus lourd que 1R)
     *
     * Porrentruy charge (si pas pref 1):
     *   - 10 points par jour au-delà de 1 jour
     *   - 1 jour = 0, 2 jours = 10, 3 jours = 20, etc.
     */
    Constraint workloadFairness(ConstraintFactory factory) {
        // Stream 1: Closing charges per staff (10×1R + 13×2F) from ClosingAssignment
        var closingCharges = factory.forEach(ClosingAssignment.class)
            .filter(ca -> ca.getStaff() != null)
            .filter(ca -> ca.getRole() != ClosingRole.ROLE_3F)
            .groupBy(ClosingAssignment::getStaff,
                ConstraintCollectors.sum(ca -> {
                    ClosingRole role = ca.getRole();
                    if (role == ClosingRole.ROLE_1R) return 10;
                    if (role == ClosingRole.ROLE_2F) return 13;
                    return 0;
                }));

        // Stream 2: Porrentruy charges per staff (10×(days-1) if days>1 and not pref 1)
        var porrentruyCharges = factory.forEachIncludingUnassigned(ShiftSlot.class)
            .filter(slot -> slot.getStaff() != null)
            .filter(slot -> "Porrentruy".equalsIgnoreCase(slot.getSiteName()))
            .filter(slot -> slot.getStaff().getSitePriority(slot.getSiteId()) != 1)
            .groupBy(ShiftSlot::getStaff,
                ConstraintCollectors.toSet(ShiftSlot::getDate))
            .groupBy((staff, dates) -> staff,
                ConstraintCollectors.sum((staff, dates) ->
                    dates.size() > 1 ? (dates.size() - 1) * 10 : 0));

        // Combine both streams and apply quadratic penalty (÷10 pour réduire l'agressivité)
        return closingCharges.concat(porrentruyCharges)
            .groupBy((staff, charge) -> staff,
                ConstraintCollectors.sum((staff, charge) -> charge))
            .penalize(HardMediumSoftScore.ofSoft(1),
                (staff, totalCharge) -> (totalCharge * totalCharge) / 10)
            .asConstraint("S-WORKLOAD: Fairness");
    }
}
