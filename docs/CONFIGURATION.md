# Configuration Checklist

## External services

`config/external-services.yml` dosyasındaki her servis için:

- `enabled`
- `base-url`
- `auth-type`
- `username`
- `token`
- `timeout`

doldurulur.

## Target application

`config/replay-targets.yml` içerisinde:

- Repository slug
- Clone URL
- Problemli image
- Container port
- Health path
- Datasource env isimleri
- DB schema ve seed dosyaları
- Replay method/path/body
- Beklenen HTTP status
- Beklenen log regex
- Source branch
- Reviewer listesi

tanımlanır.

## Secret yönetimi

Yerel geliştirme:

```text
.env
```

Kubernetes:

```text
Secret veya external secret manager
```

CI:

```text
Jenkins credential binding
```

YAML dosyasına gerçek parola/token yazılmamalıdır.
