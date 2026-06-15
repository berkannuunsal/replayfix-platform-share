# ReplayFix Platform

ReplayFix; Jira defect/incident kaydını, log ve trace verisini, kaynak kodu, Confluence/Rovo bilgisini ve uygulama deployment tanımını bir araya getirerek:

1. Incident bağlamını toplar.
2. Geçici ve izole bir Kubernetes namespace'i oluşturur.
3. Uygulamanın problemli image sürümünü geçici PostgreSQL'e bağlar.
4. Request'i tekrar oynatır.
5. Hatanın aynı failure signature ile oluştuğunu doğrular.
6. Regression test önerisi üretir.
7. Minimum patch önerisi üretir.
8. Branch'i Jenkins ile doğrular.
9. Bitbucket pull request'i oluşturur.
10. Jira'ya sonuçları yazar.
11. İnsan onayı olmadan merge veya production deployment yapmaz.

## Teknoloji seçimi

- Java 17
- Spring Boot 3
- PostgreSQL
- Flyway
- Jira REST API
- Loki HTTP API
- Tempo HTTP API
- Confluence REST API
- Rovo için konfigüre edilebilir HTTP adapter
- OpenAI uyumlu kurum içi LLM endpoint'i
- Bitbucket Cloud ve Bitbucket Data Center adapter'ları
- Jenkins REST API
- Kubernetes ve `kubectl`
- Docker Compose
- Helm

## Dış bağımlılıklar nerede?

Şirket sistemlerine ait bütün dış bağımlılıkların ayarları ayrı dosyalardadır:

| Dosya | Amaç |
|---|---|
| `config/external-services.yml` | Jira, Loki, Tempo, Bitbucket, Confluence, Rovo, AI, Jenkins, Kubernetes ve kaynak DB |
| `config/replay-targets.yml` | Replay edilecek uygulamalar, image, repository, DB env isimleri, request ve hata imzası |
| `config/log-parsers.yml` | Düz metin loglardan orderId, traceId, exception ve version çıkarma regex'leri |
| `.env.example` | Gerçek URL/token/parola yerine environment variable listesi |

Gerçek token ve parolaları YAML içine yazmayın. `.env`, Kubernetes Secret veya kurumun secret manager'ını kullanın.

## Proje yapısı

```text
replayfix-platform
├── config
│   ├── external-services.yml
│   ├── replay-targets.yml
│   └── log-parsers.yml
├── deploy/helm/replayfix
├── openapi
├── replay-packages
│   └── sample-null-order
│       ├── schema.sql
│       ├── seed.sql
│       └── request.json
├── scripts
├── src/main/java/com/etiya/replayfix
│   ├── api
│   ├── config
│   ├── domain
│   ├── integration
│   ├── model
│   ├── repository
│   └── service
├── Dockerfile
├── docker-compose.yml
├── Jenkinsfile
└── pom.xml
```

## Lokal dry-run

Dry-run modunda Jira, Loki, Tempo, AI, Jenkins, Bitbucket ve Kubernetes'e gerçek çağrı yapılmaz. Akışın tamamı simüle edilir ve üretilen Kubernetes manifestleri `work/` altında tutulur.

```bash
cp .env.example .env
docker compose up --build
```

Konfigürasyon kontrolü:

```bash
curl http://localhost:8088/api/v1/config/status
```

Demo:

```bash
chmod +x scripts/demo.sh
./scripts/demo.sh
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

## API kullanımı

### Case oluşturma

```bash
curl -X POST http://localhost:8088/api/v1/cases \
  -H 'Content-Type: application/json' \
  -d '{
    "jiraKey": "DRWP-1234",
    "targetKey": "sample-order-service",
    "orderId": "1234567",
    "traceId": "optional-trace-id",
    "incidentTime": "2026-06-12T11:00:00Z",
    "sourceBranch": "main",
    "sourceCommit": "optional-commit-sha",
    "imageTag": "registry.internal.example/order-service:6.1.1.20.0"
  }'
```

### Adım adım çalışma

```text
POST /api/v1/cases/{id}/collect-context
POST /api/v1/cases/{id}/provision
POST /api/v1/cases/{id}/replay
POST /api/v1/cases/{id}/generate-test
POST /api/v1/cases/{id}/generate-patch
POST /api/v1/cases/{id}/publish-validate
DELETE /api/v1/cases/{id}/environment
```

### Tüm akış

```text
POST /api/v1/cases/{id}/run-all
```

## Uygulamanın geçici PostgreSQL'e bağlanması

Her case için aşağıdaki gibi bir namespace oluşturulur:

```text
replayfix-drwp-1234-a1b2c3
```

Namespace içerisinde:

```text
app
replayfix-postgres
replay-runner
replay-request ConfigMap
replayfix-db-secret
```

oluşturulur.

Hedef uygulamanın datasource ayarları `config/replay-targets.yml` içindeki env isimleri üzerinden override edilir:

```yaml
datasource-url-env: SPRING_DATASOURCE_URL
datasource-username-env: SPRING_DATASOURCE_USERNAME
datasource-password-env: SPRING_DATASOURCE_PASSWORD
```

Uygulamaya verilen URL:

```text
jdbc:postgresql://replayfix-postgres:5432/replaydb
```

Böylece production image kullanılsa bile production DB'ye bağlanmaz.

## Birinci gerçek uygulamayı ekleme

`config/replay-targets.yml` içine yeni bir target ekleyin:

```yaml
replayfix:
  targets:
    customer-order-service:
      repository: customer-order-service
      clone-url: ${CUSTOMER_ORDER_CLONE_URL:}
      image: ${CUSTOMER_ORDER_IMAGE:}
      container-port: 8080
      health-path: /actuator/health

      datasource-url-env: SPRING_DATASOURCE_URL
      datasource-username-env: SPRING_DATASOURCE_USERNAME
      datasource-password-env: SPRING_DATASOURCE_PASSWORD

      database:
        name: orderdb
        schema-file: ./replay-packages/DRWP-1234/schema.sql
        seed-file: ./replay-packages/DRWP-1234/seed.sql

      replay:
        method: POST
        path: /api/customer-orders
        request-file: ./replay-packages/DRWP-1234/request.json
        headers:
          Content-Type: application/json
        expected-status: 500
        expected-log-regex: "NullPointerException|ORDER_VALIDATION_ERROR"

      git:
        source-branch: develop
        branch-prefix: replayfix/
        reviewer-users:
          - reviewer-user-name
```

Ardından:

```text
replay-packages/DRWP-1234/schema.sql
replay-packages/DRWP-1234/seed.sql
replay-packages/DRWP-1234/request.json
```

dosyalarını oluşturun.

## Loki sorgusu

İlk sürüm en güçlü anahtarı kullanır:

1. `orderId`
2. `traceId`
3. Jira key

Örnek:

```logql
{app=~".+"} |= "1234567"
```

Düz metin loglardan alan çıkarma kuralları `config/log-parsers.yml` içerisindedir.

## AI response sözleşmesi

Kurum içi model OpenAI `chat/completions` benzeri cevap döndürmelidir.

Root-cause cevabı:

```json
{
  "summary": "Order mapping failed",
  "probableRootCause": "productOfferingId can be null",
  "confidence": 0.92,
  "evidence": [
    "OrderMapper.java:218",
    "traceId=abc-123"
  ],
  "remediationActions": [
    "Add validation",
    "Add regression test"
  ]
}
```

Test ve patch cevabı:

```json
{
  "explanation": "Adds a null-input regression test",
  "files": [
    {
      "path": "src/test/java/.../OrderMapperTest.java",
      "content": "..."
    }
  ]
}
```

## Live-mode güvenlik kapıları

Aşağıdaki ayarlar varsayılan olarak kapalıdır:

```text
REPLAYFIX_MODE=LIVE
REPLAYFIX_ALLOW_GENERATED_CODE_WRITE=true
REPLAYFIX_ALLOW_GIT_PUSH=true
REPLAYFIX_ALLOW_PR_CREATION=true
```

Production DB okuması ayrıca:

```text
REPLAYFIX_ALLOW_PRODUCTION_READ=true
```

gerektirir.

MVP içerisinde AI'a production DB üzerinde serbest SQL çalıştırma yetkisi verilmez. Production veya test verisi gerekiyorsa onaylı SQL extractor'lar ile maskelenmiş replay paketi hazırlanmalıdır.

## Bitbucket seçimi

Bitbucket Cloud:

```text
BITBUCKET_PROVIDER=CLOUD
BITBUCKET_BASE_URL=https://api.bitbucket.org
BITBUCKET_WORKSPACE=...
```

Bitbucket Data Center:

```text
BITBUCKET_PROVIDER=DATA_CENTER
BITBUCKET_BASE_URL=https://bitbucket.internal.example
BITBUCKET_PROJECT_KEY=...
```

Verilen `7.21.23` sürümü Data Center/Server sürüm formatına daha yakındır; gerçek endpoint'e göre config değiştirilmelidir.

## MVP sınırları

Bu teslimat çalışan bir platform iskeletidir. Şirkete özel aşağıdaki sözleşmeler gerçek bilgilerle doldurulmalıdır:

- Rovo request/response JSON formatı
- Bitbucket service account auth yöntemi
- Jira custom field adları
- Loki label adları
- Tempo auth şekli
- Jenkins job parametreleri
- Hedef uygulamanın gerçek datasource env isimleri
- CastleMock proje/endpoint import formatı
- ActiveMQ, WSO2 ve diğer servislerin replay veya mock tanımları
- İlk defect'in schema, seed ve request paketi

## İlk hackathon demosu için önerilen kapsam

```text
1 Spring Boot servis
1 PostgreSQL
1 Jira ticket
1 Bitbucket repository
1 orderId
Loki + varsa Tempo
1 Kubernetes namespace
En fazla 2 CastleMock bağımlılığı
Jenkins doğrulaması
Draft/review-required PR
```

İlk olarak daha önce çözülmüş, deterministik ve tek serviste üretilebilen bir defect seçin.
