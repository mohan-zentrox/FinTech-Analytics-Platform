# Team

Per the BRD/FRD compliance rule, this document (and all repo artifacts)
identifies team members only by their assigned role ID. Real names are never
used in source, commits, docs, or issue trackers for this project.

| Role ID   | Role                              | Primary ownership in this repo                                                                 |
|-----------|-----------------------------------|--------------------------------------------------------------------------------------------------|
| T2-LEAD   | Tech Lead / Backend Engineer      | `core-api` architecture, Spring Security/JWT/RBAC, reconciliation engine, audit logging, CI/CD  |
| T2-FE1    | Frontend Engineer                 | `frontend` (React/TS/Tailwind/Recharts), API client layer, dashboard/explorer/reconciliation UI |
| T2-DATA1  | Financial Data Analyst            | `analytics-service` KPI/aging/burn-rate definitions, fraud/anomaly rule design (scaffolded)      |
| T2-DATA2  | Data Analyst                      | `analytics-service` cash-flow aggregation, CSV import schema/validation rules                    |
| T2-BA1    | Business Analyst                  | FRD/BRD requirements ownership, report template requirements (scaffolded), acceptance criteria   |
| T2-QA1    | QA Automation Engineer            | Test strategy across JUnit/pytest/vitest, CI gating, reconciliation/import edge-case coverage    |

## Working agreements

- Every PR references the FRD section(s) it implements or scaffolds.
- Role IDs above are used in commit trailers / PR descriptions where
  attribution is needed (e.g. `Reviewed-by: T2-QA1`), never personal names
  or emails.
- Scaffolded modules (see README.md "What's implemented vs scaffolded")
  carry `TODO (FRD S...)` comments naming the section to implement against;
  do not remove those references when picking up the work.
