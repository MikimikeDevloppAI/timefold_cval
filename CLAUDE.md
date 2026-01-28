# Instructions pour Claude Code

## Environnement Windows

Ce projet tourne sur Windows avec:
- Java 21 via Scoop
- Maven
- Supabase comme backend

## Commandes de Build et Run

### Build le projet Java
```powershell
cd c:\Users\micha\OneDrive\New_db_cval\timefold-solver
.\build.bat
```

### Lancer le solver
```powershell
cd c:\Users\micha\OneDrive\New_db_cval\timefold-solver
.\run.bat 2026-01-26 2026-01-31
```

### Variables d'environnement
Le fichier `.env` est à la racine `c:\Users\micha\OneDrive\New_db_cval\.env` et contient:
- SUPABASE_URL
- SUPABASE_SERVICE_ROLE_KEY

Les scripts `run.bat` et `build.bat` chargent automatiquement ces variables.

## Structure du projet

- `timefold-solver/` - Projet Java Maven avec Timefold Solver
  - `src/main/java/com/scheduler/` - Code source
    - `solver/ScheduleConstraintProvider.java` - Contraintes du solver
    - `domain/` - Entités (Staff, Shift, ClosingAssignment, etc.)
    - `persistence/SupabaseRepository.java` - Accès données
    - `report/HtmlReportGenerator.java` - Génération HTML
  - `target/staff-scheduler-1.0-SNAPSHOT.jar` - JAR exécutable
  - `schedule_output.html` - Rapport généré

## Contraintes actuelles

### HARD (-100h)
- H2: Skill eligibility
- H2b: Site eligibility
- H3: Surgical + distant = interdit
- H4b: Florence Bron pas 2F mardi
- H5b: Lucie Pratillo jamais 2F/3F
- H6/H6b: Staff flexible = jours exacts
- H8a/H8b: Closing staff travaille AM+PM
- H9: 1R ≠ 2F
- H9b: Capacity respectée
- H11: REST = flexible only
- H12: Flexible = journées complètes

### MEDIUM (coverage)
- M1a: Surgical -15000m
- M1b: Consultation -10000m
- M2: Closing non assigné -10000m

### SOFT (qualité)
- S1: Physician preference +12000 à +20000
- S2: Skill preference +2000 à +8000
- S3: Site change -5000
- S5a: Site preference +100 à +500
- S-FAIRNESS: Closing balance = 50 × charge² (charge = nb_1R×10 + nb_2F×13)

## Exécution via Claude Code (IMPORTANT!)

**MÉTHODE RECOMMANDÉE: Bash avec export depuis .env**

### Build
```bash
cd /c/Users/micha/OneDrive/New_db_cval/timefold-solver && mvn clean package -DskipTests
```

### Run (avec dates)
```bash
cd /c/Users/micha/OneDrive/New_db_cval/timefold-solver && export $(cat ../.env | grep -v '^#' | xargs) && java -jar target/staff-scheduler-1.0-SNAPSHOT.jar 2026-01-26 2026-01-31
```

Cette commande:
1. Va dans le dossier timefold-solver
2. Charge les variables depuis ../.env (un niveau au-dessus)
3. Lance le JAR avec les dates

## Notes importantes

- TOUJOURS utiliser les scripts .bat pour build/run (ou les commandes PowerShell ci-dessus pour Claude)
- Le .env est HORS du dossier timefold-solver (un niveau au-dessus)
- Les rapports HTML sont générés dans timefold-solver/
