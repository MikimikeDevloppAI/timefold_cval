# Spécification du Staff Scheduler

## Objectif

Assigner des **shifts** à des **staff** pour une période donnée (1 semaine).

---

## Modèle de Données

### Staff (fixe)
- `id`, `fullName`
- `hasFlexibleSchedule` : true = temps partiel (60%, 80%)
- `daysPerWeek` : 3 (60%) ou 4 (80%) - seulement si flexible
- `skills` : compétences avec préférences (P1-P4)
- `sites` : sites avec préférences (P1-P3)

### Shift (fixe)
- `id`, `date`, `periodId` (1=AM, 2=PM)
- `needType` : "surgical", "consultation", "admin", "rest"
- `skillId`, `siteId`, `locationId`
- `quantityNeeded` : nombre de staff requis

### StaffAssignment (entité de planification)
- `staff` : FIXE (un assignment par staff/date/période disponible)
- `shift` : VARIABLE (le solver choisit)
- `validShifts` : liste pré-filtrée des shifts possibles

### ClosingAssignment (entité de planification)
- `location`, `date`, `role` (1R, 2F) : FIXE
- `staff` : VARIABLE (le solver choisit)

---

## Contraintes

### HARD (score = 0 obligatoire)

| ID | Règle | Description |
|----|-------|-------------|
| H1 | Skill | Staff doit avoir la compétence du shift |
| H2 | Site | Staff doit pouvoir travailler sur le site |
| H6 | Flexible Days | Staff flexible = exactement N jours complets |
| H9b | Capacity | Pas plus de staff que quantityNeeded |
| H11 | REST Only Flexible | Seuls les flexibles peuvent avoir REST |

### MEDIUM (couverture à maximiser)

| ID | Règle | Pénalité |
|----|-------|----------|
| M1 | Surgical non couvert | -15000m |
| M2 | Consultation non couvert | -10000m |

### SOFT (préférences à optimiser)

| ID | Règle | Bonus/Pénalité |
|----|-------|----------------|
| S1 | Skill preference | +2000 à +8000 |
| S2 | Site preference | +1000 à +4000 |

---

## Contraintes Désactivées (pour simplifier)

- H3: Surgical-Distant (bloc + Porrentruy interdit)
- H4b, H5b: Staff spéciaux (Florence, Lucie)
- H8, H9: Closing constraints
- S3: Site change penalty
- Fairness constraints

---

## Comportement Attendu

1. **Staff 100%** : Travaille 5 jours (tous les jours ouvrés)
2. **Staff 80%** : Travaille 4 jours + 1 jour REST
3. **Staff 60%** : Travaille 3 jours + 2 jours REST

Un jour de travail = AM non-REST ET PM non-REST (ADMIN compte comme travail)
Un jour REST = AM REST ET PM REST

---

## Score Attendu

- **0hard** : Toutes les contraintes hard respectées
- **-Xmedium** : X = shifts non couverts × pénalité
- **+Ysoft** : Y = bonus de préférences

---

## Fichiers à Créer/Modifier

1. `ScheduleConstraintProvider.java` - Contraintes Timefold
2. `SupabaseRepository.java` - Chargement données + REST shifts
