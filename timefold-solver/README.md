# Staff Scheduler - Timefold Solver

Optimisation du planning du personnel médical avec Timefold.

## Prérequis

- Java 17+
- Maven 3.8+

## Configuration

Les variables d'environnement sont lues depuis le fichier `.env` parent :
- `SUPABASE_URL` - URL de l'API Supabase
- `SUPABASE_SERVICE_ROLE_KEY` - Clé de service Supabase

## Build

```bash
mvn clean package
```

## Exécution

```bash
# Windows
run.bat

# Avec dates spécifiques
run.bat 2026-01-20 2026-01-26
```

Ou directement :

```bash
java -jar target/staff-scheduler-1.0-SNAPSHOT.jar 2026-01-20 2026-01-26
```

## Structure

```
src/main/java/com/scheduler/
├── domain/
│   ├── Staff.java              # Membre du personnel
│   ├── StaffSkill.java         # Compétence avec préférence
│   ├── StaffSite.java          # Site avec priorité
│   ├── Location.java           # Lieu de consultation
│   ├── Shift.java              # Créneau à pourvoir
│   ├── Absence.java            # Absence
│   ├── StaffAssignment.java    # @PlanningEntity - Affectation
│   └── ScheduleSolution.java   # @PlanningSolution
├── solver/
│   └── ScheduleConstraintProvider.java  # Contraintes
├── persistence/
│   └── SupabaseRepository.java # Accès données Supabase
└── App.java                    # Point d'entrée
```

## Contraintes Implémentées

### Hard Constraints (-100h)
- H1: Conflit de temps (1 staff = 1 shift max par période)
- H2: Éligibilité skill (staff doit avoir la compétence)
- H2b: Éligibilité site (staff doit avoir le site en préférence)
- H3: Exclusion Bloc-Site distant
- H4: Florence Bron pas 2F mardi
- H5: Lucie Pratillo pas 2F/3F
- H7: Absence
- H8: Continuité closing (1R/2F même personne)

### Medium Constraints (-1000m)
- M1: Couverture manquante

### Soft Constraints
- S1: Préférence skill (P1 > P2 > P3 > P4)

## Tables Supabase utilisées

### Lecture
- `staff_members` + `users` - Personnel
- `staff_skills` - Compétences avec préférences
- `staff_sites` - Sites avec priorités
- `locations` + `sites` - Lieux
- `user_absences` - Absences
- `v_staff_needs` - Vue des besoins calculés

### Écriture
- `staff_assignments` - Résultat de l'optimisation
