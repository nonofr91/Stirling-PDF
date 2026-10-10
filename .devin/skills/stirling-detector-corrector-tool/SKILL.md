---
name: stirling-detector-corrector-tool
description: >-
  Créer ou étendre un outil détecteur/correcteur Stirling-PDF (famille preflight) :
  codes de détection stables, fixups sélectionnables, paramètres fixupParams namespacés,
  rapport d'étape report.<ns>.*, et catalogue frontend dont dérivent l'UI pipeline.
  À utiliser dès qu'une tâche touche preflight, fixups, report.preflight.* ou un
  nouvel outil qui détecte et/ou corrige.
license: MIT
triggers:
  - user
  - model
---

# Detector/Corrector Tool — recettes Stirling-PDF

La référence normative est `devGuide/prepress-tool-contract.md` (règles R1–R6).
Ce skill donne les recettes concrètes par type de tâche, avec les fichiers exacts.

## Modèle mental

4 vocabulaires, 2 sources de vérité :

| Vocabulaire | Backend (valide) | Frontend (affiche) |
|---|---|---|
| Détections | `PreflightCheck` (enum, `app/core/.../service/preflight/`) | `PREFLIGHT_DETECTIONS` (`src/core/data/preflightCatalog.ts`) |
| Corrections | `PreflightFixer.Code` + `FIXUP_PARAMS` | `PREFLIGHT_FIXUPS` (même fichier) |
| Champs rapport | `@ReportField` sur le record `PrintPreflightReport.Preflight` + `@ToolReport` par endpoint producteur | `toolReports.ts` **généré** (`x-stirling-report`), exposé via `reportCatalog.ts` |
| Params correctifs | `FIXUP_PARAMS` map code→clés acceptées | `FixupDescriptor.params` |

Codes **append-only**, jamais renommés ni supprimés (des pipelines stockés les
référencent). Un code se retire en le dépréciant, pas en l'effaçant.

## Recette 1 — Ajouter une détection

1. `PreflightCheck.java` : nouvelle entrée `(Category.X, Severity.Y[, renderPass])`.
   `renderPass: true` = coûte un rendu de page → la passerelle `needsRenderPass()`
   permet de l'individualiser dans `disabledChecks`.
2. Émission **obligatoirement via l'enum** : `new Finding(PreflightCheck.X.code(), ...)`.
   Jamais de littéral — c'est ce qui garantit `disabledChecks` et le catalogue valides.
3. `finding.<CODE>` dans les bundles i18n backend (message localisé du finding).
4. `preflightCatalog.ts` : entrée `PREFLIGHT_DETECTIONS` (même ordre que l'enum) —
   rend `disabledChecks`, les sélecteurs de codes et `reportFieldByPath` cohérents
   sans autre changement frontend.
5. `en-GB`/`en-US`/`fr-FR` : si le code apparaît dans des libellés UI.

Vérif : `./gradlew :stirling-pdf:compileJava` + le test catalogue
(`src/core/data/preflightCatalog.test.ts` couvre unicité, UPPER_SNAKE, références).

## Recette 2 — Ajouter une correction (fixup)

1. `PreflightFixer.Code` : nouvelle entrée.
2. Implémentation dans le bon moteur (ordre d'exécution fixe, contract R2) :
   - `document` → méthode privée dans `PreflightFixer.apply` (dictionnaire/ressources) ;
   - `stream` → ajouter à `STREAM_FIXUPS` + le rewrite token dans `PreflightStreamFixer` ;
   - `ghostscript` → entrée dans `GS_FIXUP_FINDINGS` + passe dans `PreflightGhostscriptFixer`.
3. Paramètres éventuels : ajouter le code dans `FIXUP_PARAMS` (clés acceptées),
   les accesseurs `stringParam`/`floatParam`, et une branche `validateParamValue`
   pour valider les valeurs tôt (400, pas un défaut silencieux).
4. `preflightCatalog.ts` : entrée `PREFLIGHT_FIXUPS` avec `engine`,
   `addressesChecks` (codes déclarés), `params` (spec `enum`/`number` + défaut),
   `destructive` si la correction retire du contenu ou de la sémantique.
   → `FixupParamsEditor` rend le réglage tout seul, pas de composant dédié.
5. i18n : `printPreflight.fixups.codes.<CODE>` ×3 locales ;
   `printPreflight.fixupParams.<CODE>.<key>.{label,help}` si paramétré.
6. Tests : `PreflightFixerTest` (appliqué + skipped dans le tool report).

Piège : `fixups` vide = « tout ce qui s'applique », `NONE` = rien, liste explicite
= inconditionnel. `skippedFixups` ne se remplit que sur liste explicite.

## Recette 3 — Ajouter un paramètre à une correction existante

1. `FIXUP_PARAMS[CODE]` += la clé ; `validateParamValue` += la validation (type/bornes).
2. Consommer via `stringParam`/`floatParam` avec le défaut documenté.
3. `FixupDescriptor.params` dans le catalogue (kind, options ou bornes, default).
4. i18n `printPreflight.fixupParams.<CODE>.<key>.{label,help[,options.*]}` ×3.

Règle d'or (R3) : ne crée un paramètre namespacé QUE si aucun champ plat ne porte
déjà la même quantité physique. `requiredBleedMm` dimensionne à la fois la
détection `BLEED_INSUFFICIENT` et la correction `EXTEND_BLEED` — il reste plat.

## Recette 4 — Ajouter un champ au rapport d'étape

1. Record `Preflight` dans `PrintPreflightReport.java` : nouveau composant
   annoté `@ReportField` (`kind` ENUM/COUNT/CODE_LIST, `vocabulary` = l'enum
   Java ou `values` littéraux, `producedBy` ANALYSIS/FIX, `labelKey` +
   `labelDefault`) + le peupler dans `of`/`afterFix`.
2. Regen : `./gradlew :stirling-pdf:copySwaggerDoc` puis le générateur
   (voir Recette 5.6) — le champ apparaît dans `toolReports.ts`.
   Aucune édition de `reportCatalog.ts` : les descripteurs sont dérivés.
   → `RoutingConditionEditor` l'offre automatiquement, activé selon le
   producteur en amont (`reportAvailability` par namespace) ;
   `reportFactsSatisfied(condition, availability)` couvre les
   `producedBy:"fix"` dans les blockers du builder.
3. i18n `portal.pipelines.builder.routing.matchPreflight<Name>` ×3.
4. Reste compact : header HTTP → verdicts, compteurs, listes de codes ; jamais
   les findings complets.

Un champ `producedBy: FIX` n'apparaît dans `x-stirling-report` d'un endpoint
que si celui-ci déclare `@ToolReport(fix = true)` — un endpoint analysis ne
peut pas offrir des champs qu'il n'émet jamais.

## Recette 5 — Enregistrer une nouvelle famille d'outils (checklist R6)

1. Enums backend détections+corrections, émissions via `enum.code()`.
2. Record rapport `@ReportNamespace("<ns>")` + `@ReportField` par champ ;
   chaque endpoint producteur → `@ToolReport(TheReport.class, fix = …)` +
   `@ToolIO` (contrat fichier) + header `X-Stirling-Tool-Report` `{<ns>: {...}}`.
   Les variantes JSON-only ne déclarent pas `@ToolReport`.
3. Regen : `./gradlew :stirling-pdf:copySwaggerDoc` puis (depuis `frontend/`)
   `npx tsx editor/scripts/generate-tool-api-types.mts --spec ../SwaggerDoc.json
   --output editor/src/core/types/toolApiTypes.ts --io-output editor/src/core/types/toolIO.ts
   --report-output editor/src/core/types/toolReports.ts` puis `oxfmt` sur les
   trois fichiers. La namespace arrive dans `TOOL_REPORTS` → gates, routage et
   blockers dérivent sans toucher `RoutingConditionEditor`/`PipelineBuilder`.
4. Catalogue détections/fixups (nouveau fichier `<family>Catalog.ts` sur le modèle
   de `preflightCatalog.ts`).
5. i18n ×3, tests intégrité catalogue + validation params + présence des champs.

## Pièges mesurés

- `PipelineStep.parameters` ne porte pas de maps imbriquées : l'exécuteur poste
  des champs de formulaire → `fixupParams` est une **chaîne JSON** côté request,
  un objet imbriqué côté profil (le merge profil→request le re-sérialise).
- `objectToFormData` refuse les objets : sérialiser `fixupParams` en JSON
  **avant** (`preflightStepToApiParams`, `buildFormData`).
- Les variantes JSON-only (`print-preflight`, `fix-preview`) ne produisent pas
  de fichier → jamais producteurs de `report.preflight.*` pour le routage.
- Les champs `producedBy:"fix"` n'existent qu'après un step fix : un gate sur
  `pre*`/`fixupsApplied`/`fixupsSkipped` sans fix en amont est bloqué au save.
- Le catalogue détections/fixups est un miroir déclaratif : codes et params
  s'ajoutent **des deux côtés dans la même PR** — `preflightCatalog.test.ts`
  attrape les dérives internes (les `values` générés y sont comparés aux ids
  du catalogue).
- Les **champs de rapport** ne sont PAS un miroir : ils sont générés depuis
  `@ReportField` → `x-stirling-report` → `toolReports.ts`. Ne jamais écrire
  un descripteur de rapport à la main — annoter le record et régénérer.

## Vérifications

- `./gradlew :stirling-pdf:test --tests "*Preflight*"` (+ `spotlessApply`).
- `npx tsc --noEmit --project editor/src/{core,proprietary,portal}/tsconfig.json` (depuis `frontend/`).
- `npx vitest run src/core/data/preflightCatalog.test.ts` (depuis `frontend/editor/`).
