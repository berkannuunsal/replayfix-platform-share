# ReplayLab Architecture

```text
Jira / Manual API
        |
        v
ReplayLab Orchestrator
        |
        +--> Loki / Tempo
        +--> Confluence / Rovo
        +--> Bitbucket source
        +--> Internal AI
        |
        v
Evidence Store + Audit Store (PostgreSQL)
        |
        v
Temporary Kubernetes Namespace
        |
        +--> Faulty application image
        +--> Temporary PostgreSQL
        +--> CastleMock / optional dependencies
        +--> Replay runner
        |
        v
Failure signature verification
        |
        v
Generated regression test and minimum patch
        |
        v
Git branch -> Jenkins validation -> Bitbucket PR
        |
        v
Mandatory human review
```

## Güvenlik sınırları

- Replay pod'larından production DB'ye varsayılan erişim yoktur.
- Her case ayrı namespace kullanır.
- Geçici PostgreSQL datasource'u application env üzerinden zorla override edilir.
- Token ve şifreler kaynak kodda tutulmaz.
- Evidence verisi kaydedilmeden önce maskeleme uygulanır.
- Evidence kayıtları SHA-256 hash ile izlenebilir.
- AI çıktısı otomatik merge edilmez.
- Git push, PR oluşturma ve generated-code write ayrı policy flag'leridir.
