# Contraintes d'Optimisation - Staff Scheduler

## Vue d'ensemble

Ce système utilise **Timefold Solver** pour planifier automatiquement les affectations du personnel médical. Le solver utilise un score à trois niveaux **HardMediumSoftScore** avec priorité lexicographique:

1. **HARD** - Contraintes obligatoires (violations = solution invalide)
2. **MEDIUM** - Couverture des shifts (maximiser les shifts remplis)
3. **SOFT** - Préférences et qualité (optimiser l'équité et le confort)

---

## Modèle de données

### Entités principales

| Entité | Type | Description |
|--------|------|-------------|
| `ShiftSlot` | **PlanningEntity (NEW)** | 1 slot = 1 unité de couverture (variable = staff, allowsUnassigned=true) |
| `StaffAssignment` | PlanningEntity (legacy) | Affectation d'un staff à un shift (variable = shift) |
| `ClosingAssignment` | PlanningEntity | Affectation des responsabilités de fermeture 1R/2F/3F (variable = staff) |
| `Staff` | ProblemFact | Membre du personnel avec skills, sites, disponibilités |
| `Shift` | ProblemFact | Besoin à couvrir (location, date, période, skill) |

### Nouveau modèle ShiftSlot (Recommandé)

Le modèle ShiftSlot remplace `quantityNeeded` par des slots individuels:
- Si un shift a besoin de 3 personnes → 3 ShiftSlots sont créés
- La capacité devient **structurelle** (pas de contrainte H9b nécessaire)
- `allowsUnassigned=true` permet les slots non couverts en cas de sur-contrainte

**Avantages:**
- 1 slot = 1 décision (plus clair)
- Score Analysis plus lisible
- Pas de contrainte de capacité explicite

### Types de shifts

- **consultation** - Consultations médicales
- **surgical** - Interventions chirurgicales
- **admin** - Demi-journées administratives
- **rest** - Repos (pour staff flexible uniquement)

### Rôles de fermeture (Closing)

- **1R** - Première responsabilité de fermeture
- **2F** - Deuxième responsabilité de fermeture (plus lourde que 1R)
- **3F** - Troisième responsabilité de fermeture

---

## Contraintes ShiftSlot (NOUVEAU MODÈLE)

### HARD (Faisabilité)

| Code | Nom | Description | Pénalité |
|------|-----|-------------|----------|
| HS1 | Slot skill eligibility | Staff assigné doit avoir le skill requis | -1h |
| HS2 | Slot site eligibility | Staff assigné doit pouvoir travailler au site | -1h |
| HS3 | Slot staff availability | Staff doit être disponible ce jour/période | -100h |
| HS4 | Slot no double booking | Staff ne peut pas être assigné à 2 slots en même temps | -100h |

### MEDIUM (Couverture)

| Code | Nom | Description | Bonus/Pénalité |
|------|-----|-------------|----------------|
| MS1 | Slot covered surgical | Slot chirurgical couvert | +200m |
| MS2 | Slot covered consultation | Slot consultation couvert | +100m |
| MS3 | Slot unassigned penalty | Slot non assigné | -1500m (surgical) / -1000m (consultation) |

### SOFT (Préférences)

| Code | Nom | Description | Bonus |
|------|-----|-------------|-------|
| SS1 | Slot physician preference | Staff travaille avec médecin préféré | +100s (P1) / +60s (P2) / +30s (P3) |
| SS2 | Slot skill preference | Staff travaille avec skill préféré | +80s (P1) / +60s (P2) / +40s (P3) / +20s (P4) |
| SS3 | Slot location continuity | Staff reste au même lieu AM→PM | +50s |

---

## Contraintes Legacy (StaffAssignment)

### HARD (obligatoires)

| Code | Nom | Description | Pénalité |
|------|-----|-------------|----------|
| H1 | Skill eligibility | Staff doit avoir la compétence requise | -1h |
| H2 | Site eligibility | Staff doit pouvoir travailler sur le site | -1h |
| H9b | Capacity | Pas plus de staff que la capacité du shift | -1h |
| H11 | REST = flexible only | Seuls les staff flexibles peuvent avoir des REST | -100000h |
| H12 | Flexible full days | Staff flexible = journées complètes | -50000h |
| H-CLOSING-1 | Closing must be assigned | 1R/2F doivent avoir un staff | -10000h |
| H-CLOSING-2a/2b | Closing staff must work | Staff doit travailler AM+PM au lieu | -10000h |
| H-CLOSING-3 | 1R ≠ 2F | 1R et 2F doivent être différents | -10000h |

### MEDIUM (couverture)

| Code | Nom | Description | Bonus/Pénalité |
|------|-----|-------------|----------------|
| M1 | Covered surgical | Shift chirurgical couvert | +200m |
| M2 | Covered consultation | Shift consultation couvert | +100m |
| M3 | Partially uncovered | Shift partiellement couvert | -1500m (surgical) / -1000m (consultation) |
| M4 | Empty shift | Shift complètement vide | Même pénalité × qty |
| M-FLEX | Flexible target days | Staff flexible avec mauvais nb jours | -5000m par jour d'écart |
| M-REST | Flexible REST incentive | REST pour staff flexible | +500m |

### SOFT (préférences)

| Code | Nom | Description | Bonus |
|------|-----|-------------|-------|
| S1 | Physician preference | Staff avec médecin préféré | +100s (P1) / +60s (P2) / +30s (P3) |
| S2 | Skill preference | Staff avec skill préféré | +80s / +60s / +40s / +20s |
| S3 | Location continuity | Même lieu AM→PM | +10s |
| S4 | Site change penalty | Changement de site AM→PM | -20s |
| S-WORKLOAD | Workload fairness | Équité closing + Porrentruy | Quadratique (charge²/10) |

---

## S-WORKLOAD: Formule détaillée

Cette contrainte combine deux aspects en une pénalité unique:

### 1. Charge Closing
- **1R** = 10 points de charge
- **2F** = 13 points de charge (1.3× plus lourd que 1R)
- **3F** = 0 (exclu du calcul)

### 2. Charge Porrentruy
Pour les staff qui n'ont PAS Porrentruy en préférence 1:
- 1 jour à Porrentruy = 0 points
- 2 jours = 10 points
- 3 jours = 20 points
- Formule: `(jours - 1) × 10` si jours > 1

### 3. Pénalité combinée
```
charge_totale = charge_closing + charge_porrentruy
pénalité = (charge_totale²) / 10
```

---

## Configuration du solver

```java
// Phases de résolution (4 phases)
1. Construction Heuristic (ShiftSlot) - First Fit
2. Construction Heuristic (StaffAssignment) - First Fit
3. Construction Heuristic (ClosingAssignment) - First Fit
4. Local Search - Late Acceptance avec:
   - Change moves (ShiftSlot)
   - Change moves (StaffAssignment)
   - Swap moves (StaffAssignment)
   - Change moves (ClosingAssignment)

// Terminaison
- Temps max: 30 secondes
- Sans amélioration: 15 secondes
```

---

## Commandes utiles

### Build
```bash
cd /c/Users/micha/OneDrive/New_db_cval/timefold-solver && mvn clean package -DskipTests
```

### Exécution
```bash
cd /c/Users/micha/OneDrive/New_db_cval/timefold-solver && export $(cat ../.env | grep -v '^#' | xargs) && java -jar target/staff-scheduler-1.0-SNAPSHOT.jar 2026-01-19 2026-01-24
```

---

## Fichiers principaux

| Fichier | Contenu |
|---------|---------|
| `ShiftSlot.java` | **NOUVEAU** - Entité slot (1 slot = 1 unité de couverture) |
| `ScheduleConstraintProvider.java` | Définition de toutes les contraintes |
| `App.java` | Configuration du solver et orchestration |
| `StaffAssignment.java` | Entité d'affectation staff→shift (legacy) |
| `ClosingAssignment.java` | Entité d'affectation closing→staff |
| `Staff.java` | Données du personnel |
| `Shift.java` | Besoins à couvrir |
| `SupabaseRepository.java` | Chargement données + création des slots |
