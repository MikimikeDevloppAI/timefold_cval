# Contraintes du Scheduler - Documentation

## Modèle de données

### Entités de planification (PlanningEntity)
1. **StaffAssignment** : staff + date + période → **shift** (variable)
2. **ClosingAssignment** : location + date + rôle → **staff** (variable)

### Problème
- ~26 staff
- ~181 StaffAssignments (slots disponibles)
- ~16 ClosingAssignments (1R/2F à remplir)
- ~90 shifts (consultation, surgical, admin, rest)

---

## HARD CONSTRAINTS (Faisabilité - doit être 0)

### H1: Skill Eligibility
- **Règle** : Le staff doit avoir la compétence requise pour le shift
- **Exclusions** : Admin et REST (n'importe qui peut faire)
- **Implémentation** : `staff.hasSkill(shift.skillId)`

### H2: Site Eligibility
- **Règle** : Le staff doit pouvoir travailler sur le site du shift
- **Exclusions** : Admin (pas de site), REST
- **Implémentation** : `staff.canWorkAtSite(shift.siteId)`

### H3: Surgical-Distant Exclusion
- **Règle** : Pas de bloc opératoire + Porrentruy/Vieille Ville le même jour
- **Raison** : Impossible de faire le trajet entre les deux

### H6: Exact Days for Flexible Staff
- **Règle** : Staff flexible doit travailler exactement `days_per_week` jours
- **Pénalité** : -10000h par jour de différence (surplus ou manque)

### H8: Closing Staff Must Work at Location
- **Règle** : Le staff assigné à un closing doit travailler **toute la journée** (AM + PM) à cette location
- **Raison** : Le closing est une responsabilité additionnelle, pas un shift séparé
- **Pénalité** : -10000h si manque AM ou PM

### H9: Different Closing Staff
- **Règle** : 1R et 2F au même jour/location doivent être des personnes différentes
- **Raison** : Deux personnes nécessaires pour fermer

### H11: REST Only for Flexible Staff
- **Règle** : Seuls les staff flexibles peuvent avoir des shifts REST
- **Raison** : REST = jours non travaillés pour atteindre le quota

### H12: Flexible Full Days
- **Règle** : Staff flexible ne peut pas avoir REST le matin et travail l'après-midi (ou inverse)
- **Raison** : Les jours sont complets ou non travaillés

### H9b: Shift Capacity
- **Règle** : Pas plus de staff que `quantityNeeded` par shift
- **Note** : Admin a `quantity=999` (illimité)

### Règles spéciales
- **H4b** : Florence Bron ne peut pas être 2F le mardi
- **H5b** : Lucie Pratillo ne peut pas être 2F ou 3F

---

## MEDIUM CONSTRAINTS (Couverture - maximiser)

**Approche REWARDS** : On donne des points positifs pour chaque assignation utile.
Le solver maximise le score, donc il préfère couvrir les shifts plutôt que mettre en admin.

### M1a: Reward Surgical Assignment
- **Règle** : +200000 medium par assignation à un shift surgical
- **Raison** : Chirurgie = priorité maximale

### M1b: Reward Consultation Assignment
- **Règle** : +150000 medium par assignation à un shift consultation
- **Raison** : Consultation = importante

### M2: Reward Closing Assignment
- **Règle** : +100000 medium par closing avec staff assigné
- **Raison** : Closing doit être couvert

### Admin
- **Règle** : 0 medium (pas de reward)
- **Raison** : Admin = fallback, capacité illimitée

**Conséquence** : Le solver préférera toujours :
- Surgical (+200k) > Consultation (+150k) > Closing (+100k) > Admin (0)

---

## SOFT CONSTRAINTS (Qualité - optimiser)

### Tier 1: Physician Preference (+12000 à +20000)
- **Règle** : Bonus si staff travaille avec son médecin préféré
- P1 = +20000, P2 = +16000, P3 = +12000

### Tier 2: Skill Preference (+2000 à +8000)
- **Règle** : Bonus si staff a une bonne préférence pour la compétence
- P1 = +8000, P2 = +6000, P3 = +4000, P4 = +2000

### Tier 3: Site Change Penalty (-5000)
- **Règle** : Pénalité si staff change de site entre AM et PM
- **Raison** : Éviter les trajets inutiles

### Tier 4: Site Preference (+1000 à +4000)
- **Règle** : Bonus si staff travaille à son site préféré
- P1 = +4000, P2 = +2000, P3 = +1000

### Fairness: Closing Balance
- **Règle** : Pénalité quadratique pour concentration de closing
- Formule : `100 × charge²` où charge = nb_1R×10 + nb_2F×12
- **Effet** : Répartit les closing sur plusieurs staff

### Fairness: Porrentruy Balance
- **Règle** : Pénalité cubique pour trop de jours à Porrentruy (staff P2/P3)
- Formule : `((jours-1) × 10)³`
- **Effet** : Répartit Porrentruy équitablement

### Fairness: Admin Balance
- **Règle** : Rendements décroissants pour admin
- Formule : `sqrt(count) × 100`
- **Effet** : Répartit l'admin entre plusieurs staff

---

## Exemple concret : Julianne Kunz

**Situation** : Jeudi 22 PM, shift "Aide Gastroentérologie" non couvert (1/2)

### Si Julianne va en Admin :
- HARD : 0 (OK)
- MEDIUM : +0 (admin = pas de reward)
- SOFT : +0 (admin = pas de preference)
- **Total : 0**

### Si Julianne couvre le shift consultation :
- HARD : 0 (OK, elle a la compétence)
- MEDIUM : +150000 (consultation reward)
- SOFT : +8000 (skill P1) + +4000 (site P1)
- **Total : +162000**

**Le solver choisit** : Consultation (+162000 > 0) ✓

---

## Résumé des poids

| Niveau | Contrainte | Poids |
|--------|-----------|-------|
| HARD | Toutes violations | -10000h |
| MEDIUM | Surgical reward | +200000m |
| MEDIUM | Consultation reward | +150000m |
| MEDIUM | Closing reward | +100000m |
| MEDIUM | Admin reward | 0 |
| SOFT | Physician P1 | +20000s |
| SOFT | Skill P1 | +8000s |
| SOFT | Site P1 | +4000s |
| SOFT | Site change | -5000s |
