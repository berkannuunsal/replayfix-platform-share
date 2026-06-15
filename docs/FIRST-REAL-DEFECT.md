# First Real Defect Package

Aşağıdaki bilgileri hazırlayın:

```text
Jira key:
Target key:
Repository:
Image tag:
Source commit:
Order ID:
Trace ID:
Incident time:
Endpoint:
Request payload:
Expected response:
Actual response:
Exception:
Database tables:
External dependencies:
Previously applied fix:
```

Replay package:

```text
replay-packages/<jira-key>/
├── schema.sql
├── seed.sql
└── request.json
```

İlk vaka için tercih:

- Tek repository
- Tek Spring Boot service
- Tek PostgreSQL
- Deterministik request
- Daha önce çözülmüş defect
- En fazla iki mocked dependency
