package com.scheduler.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;

import com.scheduler.domain.ClosingAssignment;
import com.scheduler.domain.Shift;
import com.scheduler.domain.StaffAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contraintes du staff scheduler.
 *
 * MODÈLE REST SHIFTS:
 * - nullable=false sur @PlanningVariable - le solver DOIT assigner un shift
 * - Shifts REST synthétiques pour les jours de repos des staff flexibles
 * - forEach() suffit car shift n'est jamais null (REST est un vrai shift)
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
            // HARD - faisabilité
            nonFlexibleMustWork(factory),   // H11: Staff non-flexible ne peut pas avoir REST
            flexibleMixedDays(factory),     // H12: Flexible ne peut pas avoir de jours mixtes (AM REST ≠ PM REST)
            flexibleMinRestSlots(factory),  // H13: Flexible doit avoir assez de slots REST (évalué pendant CH)
            flexibleExactDays(factory),     // H6: Flexible doit avoir exactement N jours
            skillEligibility(factory),      // H1: Skill requis
            siteEligibility(factory),       // H2: Site requis
            shiftCapacity(factory),         // H9b: Capacité max

            // MEDIUM - couverture
            uncoveredSurgical(factory),
            uncoveredConsultation(factory),

            // SOFT - préférences
            skillPreference(factory),
            sitePreference(factory),
        };
    }

    // =========================================================================
    // HARD CONSTRAINTS
    // =========================================================================

    /**
     * H11: Staff NON-flexible ne peut pas avoir un shift REST.
     * Seuls les staff flexibles peuvent avoir des jours de repos.
     */
    Constraint nonFlexibleMustWork(ConstraintFactory factory) {
        return factory.forEach(StaffAssignment.class)
            .filter(a -> !a.getStaff().isHasFlexibleSchedule())
            .filter(a -> a.getShift().isRest())
            .penalize(HardMediumSoftScore.ofHard(100000))  // TRÈS forte pénalité
            .asConstraint("H11: Non-flexible must work");
    }

    /**
     * H12: Staff flexible ne peut pas avoir de jours mixtes.
     * Si AM est REST, alors PM doit aussi être REST (et vice versa).
     * Évalué pour chaque assignment individuellement.
     */
    Constraint flexibleMixedDays(ConstraintFactory factory) {
        return factory.forEach(StaffAssignment.class)
            .filter(a -> a.getStaff().isHasFlexibleSchedule())
            .filter(a -> a.getOtherHalfDay() != null)
            .filter(a -> a.getOtherHalfDay().getShift() != null)  // L'autre demi-journée doit être assignée
            .filter(a -> a.getShift().isRest() != a.getOtherHalfDay().getShift().isRest())  // XOR = mixte
            .penalize(HardMediumSoftScore.ofHard(50000))  // TRÈS forte pénalité par demi-journée mixte
            .asConstraint("H12: Flexible no mixed days");
    }

    /**
     * H13: Staff flexible doit avoir suffisamment de slots REST.
     * Évalué pour chaque slot individuellement (pas besoin d'attendre AM+PM).
     * Staff 80% (4j/5j) = 2 REST slots (1 jour), Staff 60% (3j/5j) = 4 REST slots (2 jours)
     */
    Constraint flexibleMinRestSlots(ConstraintFactory factory) {
        return factory.forEach(StaffAssignment.class)
            .filter(a -> a.getStaff().isHasFlexibleSchedule())
            .filter(a -> a.getStaff().getDaysPerWeek() != null)
            .groupBy(StaffAssignment::getStaff,
                // Compte les slots REST
                ConstraintCollectors.sum((StaffAssignment a) -> a.getShift().isRest() ? 1 : 0),
                // Compte le total de slots
                ConstraintCollectors.count())
            .filter((staff, restSlots, totalSlots) -> {
                // Staff needs (5 - daysPerWeek) REST days = 2 × (5 - daysPerWeek) REST slots
                int daysPerWeek = staff.getDaysPerWeek();
                int restDaysNeeded = 5 - daysPerWeek; // 80%=1 jour, 60%=2 jours
                int restSlotsNeeded = restDaysNeeded * 2; // AM + PM
                return restSlots < restSlotsNeeded;
            })
            .penalize(HardMediumSoftScore.ofHard(5000), // Pénalité progressive
                (staff, restSlots, totalSlots) -> {
                    int daysPerWeek = staff.getDaysPerWeek();
                    int restSlotsNeeded = (5 - daysPerWeek) * 2;
                    return restSlotsNeeded - restSlots;
                })
            .asConstraint("H13: Flexible min REST slots");
    }

    /**
     * H6: Staff flexible doit travailler exactement daysPerWeek jours.
     *
     * Un jour de TRAVAIL = AM !REST ET PM !REST
     * Un jour REST = AM REST ET PM REST
     * (Les jours mixtes sont gérés par H12)
     *
     * Note: Pendant la construction heuristic, getOtherHalfDay().getShift() peut être null
     */
    Constraint flexibleExactDays(ConstraintFactory factory) {
        return factory.forEach(StaffAssignment.class)
            .filter(a -> a.getStaff().isHasFlexibleSchedule())
            .filter(a -> a.getStaff().getDaysPerWeek() != null)
            .filter(a -> a.getPeriodId() == 1)  // AM seulement pour éviter double comptage
            .filter(a -> a.getOtherHalfDay() != null)
            .filter(a -> a.getOtherHalfDay().getShift() != null)  // Attendre que PM soit assigné
            .groupBy(StaffAssignment::getStaff,
                // Compte les jours de travail complets (AM !REST ET PM !REST)
                ConstraintCollectors.sum((StaffAssignment am) -> {
                    boolean amWork = !am.getShift().isRest();
                    boolean pmWork = !am.getOtherHalfDay().getShift().isRest();
                    return (amWork && pmWork) ? 1 : 0;
                }))
            .filter((staff, workDays) -> workDays != staff.getDaysPerWeek())
            .penalize(HardMediumSoftScore.ofHard(10000),  // TRÈS forte pénalité
                (staff, workDays) -> Math.abs(staff.getDaysPerWeek() - workDays))
            .asConstraint("H6: Flexible exact days");
    }

    /**
     * H1: Staff doit avoir la compétence requise pour le shift.
     * Exclut REST et Admin qui n'ont pas d'exigence de compétence.
     */
    Constraint skillEligibility(ConstraintFactory factory) {
        return factory.forEach(StaffAssignment.class)
            .filter(a -> !a.getShift().isRest())
            .filter(a -> !a.getShift().isAdmin())
            .filter(a -> !a.getStaff().hasSkill(a.getShift().getSkillId()))
            .penalize(HardMediumSoftScore.ofHard(1))
            .asConstraint("H1: Skill eligibility");
    }

    /**
     * H2: Staff doit pouvoir travailler sur le site du shift.
     * Exclut REST et Admin qui n'ont pas de restriction de site.
     */
    Constraint siteEligibility(ConstraintFactory factory) {
        return factory.forEach(StaffAssignment.class)
            .filter(a -> !a.getShift().isRest())
            .filter(a -> !a.getShift().isAdmin())
            .filter(a -> a.getShift().getSiteId() != null)
            .filter(a -> !a.getStaff().canWorkAtSite(a.getShift().getSiteId()))
            .penalize(HardMediumSoftScore.ofHard(1))
            .asConstraint("H2: Site eligibility");
    }

    /**
     * H9b: Pas plus de staff que quantityNeeded par shift.
     * Admin et REST ont quantity=999 donc illimité (exclus du check).
     */
    Constraint shiftCapacity(ConstraintFactory factory) {
        return factory.forEach(StaffAssignment.class)
            .filter(a -> !a.getShift().isRest())
            .filter(a -> !a.getShift().isAdmin())
            .groupBy(StaffAssignment::getShift, ConstraintCollectors.count())
            .filter((shift, count) -> count > shift.getQuantityNeeded())
            .penalize(HardMediumSoftScore.ofHard(1),
                (shift, count) -> count - shift.getQuantityNeeded())
            .asConstraint("H9b: Shift capacity");
    }

    // =========================================================================
    // MEDIUM CONSTRAINTS (couverture)
    // =========================================================================

    /**
     * M1: Pénalise les shifts surgical non couverts.
     */
    Constraint uncoveredSurgical(ConstraintFactory factory) {
        return factory.forEach(Shift.class)
            .filter(s -> "surgical".equals(s.getNeedType()))
            .ifNotExists(StaffAssignment.class,
                Joiners.equal(s -> s, StaffAssignment::getShift))
            .penalize(HardMediumSoftScore.ofMedium(15),
                Shift::getQuantityNeeded)
            .asConstraint("M1: Uncovered surgical");
    }

    /**
     * M2: Pénalise les shifts consultation non couverts.
     */
    Constraint uncoveredConsultation(ConstraintFactory factory) {
        return factory.forEach(Shift.class)
            .filter(s -> "consultation".equals(s.getNeedType()))
            .ifNotExists(StaffAssignment.class,
                Joiners.equal(s -> s, StaffAssignment::getShift))
            .penalize(HardMediumSoftScore.ofMedium(10),
                Shift::getQuantityNeeded)
            .asConstraint("M2: Uncovered consultation");
    }

    // =========================================================================
    // SOFT CONSTRAINTS (préférences)
    // =========================================================================

    /**
     * S1: Bonus si staff a une bonne préférence pour la compétence.
     * Exclut REST et Admin.
     */
    Constraint skillPreference(ConstraintFactory factory) {
        return factory.forEach(StaffAssignment.class)
            .filter(a -> !a.getShift().isRest())
            .filter(a -> !a.getShift().isAdmin())
            .filter(a -> a.getSkillPreference() > 0)
            .reward(HardMediumSoftScore.ofSoft(1),
                a -> 5 - a.getSkillPreference())  // P1=4, P2=3, P3=2, P4=1
            .asConstraint("S1: Skill preference");
    }

    /**
     * S2: Bonus si staff travaille à son site préféré.
     * Exclut REST et Admin.
     */
    Constraint sitePreference(ConstraintFactory factory) {
        return factory.forEach(StaffAssignment.class)
            .filter(a -> !a.getShift().isRest())
            .filter(a -> !a.getShift().isAdmin())
            .filter(a -> a.getShift().getSiteId() != null)
            .filter(a -> a.getStaff().getSitePriority(a.getShift().getSiteId()) > 0)
            .reward(HardMediumSoftScore.ofSoft(1),
                a -> 4 - a.getStaff().getSitePriority(a.getShift().getSiteId()))  // P1=3, P2=2, P3=1
            .asConstraint("S2: Site preference");
    }
}
