# Guide de Référence Timefold - Staff Scheduler Médical

## 1. ARCHITECTURE DU MODÈLE

### 1.1 Modèle Inversé (Clé de Performance)

```
APPROCHE CLASSIQUE (lente):
  Shift (fixe) → Staff (variable)
  Problème: Beaucoup de shifts avec peu de candidats = recherche difficile

NOTRE APPROCHE (rapide):
  Staff (fixe) → Shift (variable)
  Avantage: Chaque staff a une liste pré-filtrée de shifts valides
```

### 1.2 Entités de Planning

```java
// ENTITÉ 1: StaffAssignment
@PlanningEntity
public class StaffAssignment {
    private Staff staff;           // FIXE - le staff
    private LocalDate date;        // FIXE - la date
    private int periodId;          // FIXE - 1=AM, 2=PM

    @PlanningVariable(valueRangeProviderRefs = "validShiftRange")
    private Shift shift;           // VARIABLE - le shift assigné

    private List<Shift> validShifts;      // Pré-filtré par skill+site+date
    private StaffAssignment otherHalfDay; // Référence O(1) à l'autre demi-journée
}

// ENTITÉ 2: ClosingAssignment
@PlanningEntity
public class ClosingAssignment {
    private UUID locationId;       // FIXE - la location
    private LocalDate date;        // FIXE - la date
    private ClosingRole role;      // FIXE - 1R, 2F, ou 3F

    @PlanningVariable(valueRangeProviderRefs = "staffRange")
    private Staff staff;           // VARIABLE - le staff assigné
}
```

### 1.3 Types de Shifts

| Type | Description | Pénalité Medium | Capacité |
|------|-------------|-----------------|----------|
| `surgical` | Bloc opératoire | -15000m/position | Défini |
| `consultation` | Consultations | -10000m/position | Défini |
| `admin` | Administratif | 0 (fallback) | 999 |
| `rest` | Repos (flexible) | 0 | 999 |
| `closing` | Fermeture | Via ClosingAssignment | N/A |

---

## 2. PATTERNS TIMEFOLD RECOMMANDÉS

### 2.1 Pattern: Référence O(1) pour AM/PM

**Problème**: Vérifier des conditions entre AM et PM du même jour/staff
**Solution**: Pré-calculer `otherHalfDay` lors du chargement

```java
// MAUVAIS: JOIN coûteux O(n²)
factory.forEach(StaffAssignment.class)
    .filter(a -> a.getPeriodId() == 1)  // AM
    .join(StaffAssignment.class,
        Joiners.equal(a -> a.getStaff().getId(), a -> a.getStaff().getId()),
        Joiners.equal(StaffAssignment::getDate, StaffAssignment::getDate),
        Joiners.filtering((am, pm) -> pm.getPeriodId() == 2))
    // ...

// BON: Référence directe O(1)
factory.forEach(StaffAssignment.class)
    .filter(a -> a.getPeriodId() == 1)  // AM seulement
    .filter(a -> a.getOtherHalfDay() != null)
    .filter(a -> {
        Shift pmShift = a.getOtherHalfDay().getShift();
        // Condition sur pmShift...
    })
```

### 2.2 Pattern: Comptage par groupBy

```java
// Compter les shifts par staff
factory.forEach(StaffAssignment.class)
    .filter(a -> a.getShift() != null && !a.getShift().isRest())
    .groupBy(StaffAssignment::getStaff, ConstraintCollectors.count())
    .filter((staff, count) -> count != expectedCount)
    .penalize(...)

// Compter les jours DISTINCTS par staff
factory.forEach(StaffAssignment.class)
    .filter(a -> a.getShift() != null && !a.getShift().isRest())
    .groupBy(StaffAssignment::getStaff,
             ConstraintCollectors.countDistinct(StaffAssignment::getDate))
    .filter((staff, uniqueDays) -> uniqueDays != target)
    .penalize(...)
```

### 2.3 Pattern: ifNotExists pour Absence

```java
// Pénaliser les shifts sans aucun assignment
factory.forEach(Shift.class)
    .filter(s -> "surgical".equals(s.getNeedType()))
    .ifNotExists(StaffAssignment.class,
        Joiners.equal(s -> s, StaffAssignment::getShift))
    .penalize(HardMediumSoftScore.ofMedium(15000),
        shift -> shift.getQuantityNeeded())
```

### 2.4 Pattern: Pénalité vs Reward

```
REWARD (à éviter pour couverture):
  +200000m pour chaque assignment à surgical
  → Le solver maximise sans fin, Local Search long

PÉNALITÉ (recommandé):
  -15000m pour chaque position MANQUANTE
  → Objectif clair, convergence rapide
```

---

## 3. GESTION DES SHIFTS CONSÉCUTIFS

### 3.1 Principe: Utiliser otherHalfDay + groupBy

Pour les contraintes sur des jours consécutifs, deux approches:

**A. Via otherHalfDay (même jour)**
```java
// Contrainte: AM et PM doivent être cohérents
factory.forEach(StaffAssignment.class)
    .filter(a -> a.getPeriodId() == 1)  // AM seulement
    .filter(a -> a.getOtherHalfDay() != null)
    .filter(a -> /* condition entre AM et PM */)
    .penalize(...)
```

**B. Via groupBy + calcul de dates (jours différents)**
```java
// Contrainte: Pas plus de N jours consécutifs
factory.forEach(StaffAssignment.class)
    .filter(a -> a.getShift() != null && !a.getShift().isRest())
    .filter(a -> a.getPeriodId() == 1)  // Compter 1 fois par jour
    .groupBy(StaffAssignment::getStaff,
             ConstraintCollectors.toList())
    .penalize(HardMediumSoftScore.ofSoft(1000),
        (staff, assignments) -> countMaxConsecutiveDays(assignments))
```

### 3.2 Pattern: Compter les Jours Consécutifs

```java
// Méthode helper pour compter les jours consécutifs max
private int countMaxConsecutiveDays(List<StaffAssignment> assignments) {
    if (assignments.isEmpty()) return 0;

    Set<LocalDate> workDates = assignments.stream()
        .map(StaffAssignment::getDate)
        .collect(Collectors.toSet());

    List<LocalDate> sortedDates = new ArrayList<>(workDates);
    Collections.sort(sortedDates);

    int maxConsecutive = 1;
    int currentConsecutive = 1;

    for (int i = 1; i < sortedDates.size(); i++) {
        if (sortedDates.get(i).equals(sortedDates.get(i-1).plusDays(1))) {
            currentConsecutive++;
            maxConsecutive = Math.max(maxConsecutive, currentConsecutive);
        } else {
            currentConsecutive = 1;
        }
    }

    return maxConsecutive;
}
```

### 3.3 Contrainte: Maximum Jours Consécutifs

```java
/**
 * Pénaliser si un staff travaille plus de MAX_CONSECUTIVE jours d'affilée
 */
Constraint maxConsecutiveWorkDays(ConstraintFactory factory) {
    final int MAX_CONSECUTIVE = 5;

    return factory.forEach(StaffAssignment.class)
        .filter(a -> a.getShift() != null
            && !a.getShift().isRest()
            && !a.getShift().isAdmin()
            && a.getPeriodId() == 1)  // AM seulement pour compter 1x/jour
        .groupBy(StaffAssignment::getStaff,
                 ConstraintCollectors.toList())
        .filter((staff, list) -> countMaxConsecutiveDays(list) > MAX_CONSECUTIVE)
        .penalize(HardMediumSoftScore.ofSoft(2000),
            (staff, list) -> countMaxConsecutiveDays(list) - MAX_CONSECUTIVE)
        .asConstraint("S-PATTERN: Max consecutive work days");
}
```

---

## 4. GESTION DES SECRÉTAIRES FLEXIBLES

### 4.1 Contexte

- Staff avec `hasFlexibleSchedule = true`
- Doit travailler EXACTEMENT `daysPerWeek` jours par semaine
- Semaine = 5 jours ouvrables (lundi-vendredi)
- Les autres jours = REST (repos)
- Exemple: 60% = 3 jours travail + 2 jours REST

### 4.2 Contrainte Actuelle (H6)

```java
// Compter les jours TRAVAILLÉS (non-REST, non-Admin)
Constraint exactDaysFlexible(ConstraintFactory factory) {
    return factory.forEach(StaffAssignment.class)
        .filter(a -> a.getShift() != null
            && !a.getShift().isRest()
            && a.getStaff().isHasFlexibleSchedule()
            && a.getStaff().getDaysPerWeek() != null
            && a.getStaff().getDaysPerWeek() > 0)
        .groupBy(StaffAssignment::getStaff,
                 ConstraintCollectors.countDistinct(StaffAssignment::getDate))
        .penalize(HardMediumSoftScore.ofHard(10000),
            (staff, uniqueDays) -> Math.abs(staff.getDaysPerWeek() - uniqueDays))
        .asConstraint("H6: Exact days for flexible staff");
}
```

### 4.3 Contrainte Améliorée (compte un jour si AM OU PM travaillé)

```java
/**
 * H6 Amélioré: Compter les jours où le staff travaille VRAIMENT
 * Un jour compte si AU MOINS une période (AM ou PM) a du travail réel
 * (pas REST, pas Admin)
 */
Constraint exactDaysFlexibleImproved(ConstraintFactory factory) {
    return factory.forEach(StaffAssignment.class)
        .filter(a -> a.getStaff().isHasFlexibleSchedule()
            && a.getStaff().getDaysPerWeek() != null
            && a.getStaff().getDaysPerWeek() > 0
            && a.getShift() != null
            && a.getPeriodId() == 1)  // AM seulement pour éviter double comptage
        .groupBy(StaffAssignment::getStaff,
                 ConstraintCollectors.countDistinct(am -> {
                     // Le jour compte si AM a du travail réel
                     if (!am.getShift().isRest() && !am.getShift().isAdmin()) {
                         return am.getDate();
                     }
                     // OU si PM a du travail réel
                     StaffAssignment pm = am.getOtherHalfDay();
                     if (pm != null && pm.getShift() != null
                         && !pm.getShift().isRest() && !pm.getShift().isAdmin()) {
                         return am.getDate();
                     }
                     // Sinon le jour ne compte pas
                     return null;
                 }))
        .penalize(HardMediumSoftScore.ofHard(10000),
            (staff, uniqueWorkDays) -> Math.abs(staff.getDaysPerWeek() - uniqueWorkDays))
        .asConstraint("H6: Exact days for flexible staff (improved)");
}
```

### 4.4 Contrainte: Jours REST Complets (H12)

```java
/**
 * H12: Un jour REST doit être COMPLET (AM + PM tous deux REST)
 * Pas de demi-journée REST mélangée avec du travail
 */
Constraint flexibleFullDays(ConstraintFactory factory) {
    return factory.forEach(StaffAssignment.class)
        .filter(a -> a.getShift() != null
            && a.getStaff().isHasFlexibleSchedule()
            && a.getPeriodId() == 1  // AM seulement
            && a.getOtherHalfDay() != null
            && a.getOtherHalfDay().getShift() != null)
        .filter(a -> a.getShift().isRest() != a.getOtherHalfDay().getShift().isRest())
        .penalize(HardMediumSoftScore.ofHard(10000))
        .asConstraint("H12: Flexible staff full REST days");
}
```

---

## 5. STRUCTURE DES SCORES

### 5.1 Hiérarchie Recommandée

```
HARD (-10000h): Violations IMPOSSIBLES
├── H2: Skill eligibility (staff doit avoir le skill)
├── H2b: Site eligibility (staff doit pouvoir aller au site)
├── H3: Surgical-Distant exclusion (pas surgical + site distant même jour)
├── H6: Exact days for flexible (exactement N jours/semaine)
├── H9b: Shift capacity (pas dépasser quantityNeeded)
├── H11: REST only for flexible (seul flexible peut prendre REST)
├── H12: Full REST days (REST = jour complet)
└── H4b/H5b: Règles staff spéciaux (Florence, Lucie)

MEDIUM (-15000m/-10000m): Couverture des shifts
├── M1a: Uncovered surgical (-15000m/position)
├── M1b: Uncovered consultation (-10000m/position)
└── M2: Uncovered closing (-10000m/assignment)

SOFT (±20000s): Qualité et préférences
├── Tier 1: Physician preference (+12000 à +20000)
├── Tier 2: Skill preference (+2000 à +8000)
├── Tier 3: Site change penalty (-5000)
├── Tier 4: Site preference (+1000 à +4000)
└── Fairness: Closing/Porrentruy/Admin balance
```

### 5.2 Valeurs Recommandées

```java
// HARD: Toujours 10000h pour cohérence
HardMediumSoftScore.ofHard(10000)

// MEDIUM: Proportionnel à l'importance
HardMediumSoftScore.ofMedium(15000)  // Surgical (priorité haute)
HardMediumSoftScore.ofMedium(10000)  // Consultation/Closing

// SOFT: Selon les tiers
HardMediumSoftScore.ofSoft(20000)    // Tier 1 max
HardMediumSoftScore.ofSoft(8000)     // Tier 2 max
HardMediumSoftScore.ofSoft(5000)     // Tier 3
HardMediumSoftScore.ofSoft(4000)     // Tier 4 max
```

---

## 6. OPTIMISATIONS DE PERFORMANCE

### 6.1 Pré-filtrage des Shifts (validShifts)

```java
// Dans SupabaseRepository.java lors du chargement
for (StaffAssignment assignment : assignments) {
    List<Shift> validShifts = allShifts.stream()
        .filter(shift -> shift.getDate().equals(assignment.getDate()))
        .filter(shift -> shift.getPeriodId() == assignment.getPeriodId())
        .filter(shift -> assignment.getStaff().hasSkill(shift.getSkillId())
                      || shift.isAdmin() || shift.isRest())
        .filter(shift -> assignment.getStaff().canWorkAtSite(shift.getSiteId())
                      || shift.isAdmin() || shift.isRest())
        .collect(Collectors.toList());

    assignment.setValidShifts(validShifts);
}
```

**Impact**: Réduit l'espace de recherche de ~90 shifts à ~10-20 par assignment

### 6.2 Caches dans Staff

```java
public class Staff {
    // Caches O(1) pour lookups fréquents
    private Map<UUID, Integer> skillPreferenceCache;
    private Map<UUID, Integer> sitePriorityCache;
    private Set<UUID> preferredPhysicianIds;

    public void initializeCaches() {
        skillPreferenceCache = skills.stream()
            .collect(Collectors.toMap(StaffSkill::getSkillId, StaffSkill::getPreference));
        // ...
    }
}
```

### 6.3 Éviter les Calculs Répétés

```java
// MAUVAIS: Calcul répété dans filter
.filter(a -> calculateExpensiveValue(a) > threshold)
.penalize(..., a -> calculateExpensiveValue(a))

// BON: Utiliser une méthode avec cache ou shadow variable
.filter(a -> a.getCachedExpensiveValue() > threshold)
.penalize(..., a -> a.getCachedExpensiveValue())
```

---

## 7. FICHIERS À MODIFIER

| Fichier | Rôle | Modifications Types |
|---------|------|---------------------|
| `ScheduleConstraintProvider.java` | Contraintes | Ajouter/modifier contraintes |
| `Staff.java` | Modèle staff | Ajouter caches, méthodes helper |
| `StaffAssignment.java` | Entité planning | Planning variable, validShifts |
| `SupabaseRepository.java` | Chargement données | Pré-calcul validShifts, otherHalfDay |
| `ScheduleSolution.java` | Container solution | Value ranges, problem facts |

---

## 8. CHECKLIST IMPLÉMENTATION

### Nouvelle Contrainte HARD
- [ ] Définir le cas d'échec clairement
- [ ] Utiliser `penalize(HardMediumSoftScore.ofHard(10000))`
- [ ] Tester avec un cas qui viole la contrainte
- [ ] Vérifier que le score devient négatif en hard

### Nouvelle Contrainte MEDIUM (Couverture)
- [ ] Partir de `Shift.class` (pas StaffAssignment)
- [ ] Utiliser `ifNotExists` pour shifts sans aucun assignment
- [ ] Utiliser `groupBy` + `count` pour shifts partiellement couverts
- [ ] Pénalité proportionnelle au manque

### Nouvelle Contrainte SOFT (Préférence)
- [ ] Définir les tiers de priorité
- [ ] Utiliser `reward` pour les préférences respectées
- [ ] Utiliser `penalize` pour les violations
- [ ] Garder les valeurs dans la plage appropriée (max ~20000)

---

## 9. COMMANDES DE TEST

```bash
# Compiler
cd timefold-solver
mvn clean package -DskipTests

# Exécuter
./run.bat 2026-01-19 2026-01-24

# Vérifier le output
# - Temps total: < 5 secondes (idéal)
# - Score: 0hard/Xmedium/Ysoft
# - Hard=0 signifie toutes contraintes hard respectées
```

---

## 10. SOURCES ET DOCUMENTATION

- [Timefold Constraint Streams](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/constraint-streams)
- [Timefold Performance](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/performance)
- [Timefold Employee Rostering Example](https://github.com/TimefoldAI/timefold-quickstarts)
