package com.etiya.replayfix.service;

import com.etiya.replayfix.config.ReplayFixProperties;
import com.etiya.replayfix.config.ReplayFixProperties.Target;
import com.etiya.replayfix.domain.ReplayCaseEntity;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@Service
public class KubernetesManifestFactory {
    private final ReplayFixProperties properties;
    private final SecureRandom random = new SecureRandom();

    public KubernetesManifestFactory(ReplayFixProperties properties) {
        this.properties = properties;
    }

    public String createEnvironmentManifest(ReplayCaseEntity replayCase, Target target) {
        String namespace = replayCase.getNamespace();
        String password = randomPassword();
        String initSql = readFile(target.getDatabase().getSchemaFile(), "-- no schema configured")
                + "\n"
                + readFile(target.getDatabase().getSeedFile(), "-- no seed configured");
        String customEnvironment = environmentYaml(target.getEnvironment());

        String pullSecret = properties.getIntegrations().getKubernetes().getRegistryPullSecret();
        String imagePullSecrets = pullSecret == null || pullSecret.isBlank()
                ? ""
                : "      imagePullSecrets:\n        - name: " + pullSecret + "\n";

        return """
apiVersion: v1
kind: Namespace
metadata:
  name: %s
  labels:
    app.kubernetes.io/managed-by: replayfix
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: replayfix-quota
  namespace: %s
spec:
  hard:
    requests.cpu: "2"
    requests.memory: 4Gi
    limits.cpu: "4"
    limits.memory: 8Gi
    pods: "12"
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-namespace-internal-ingress
  namespace: %s
spec:
  podSelector: {}
  policyTypes:
    - Ingress
  ingress:
    - from:
        - podSelector: {}
---
apiVersion: v1
kind: Secret
metadata:
  name: replayfix-db-secret
  namespace: %s
type: Opaque
stringData:
  username: replayfix_user
  password: %s
  database: %s
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: replayfix-db-init
  namespace: %s
data:
  001-init.sql: |-
%s
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: replayfix-postgres
  namespace: %s
spec:
  replicas: 1
  selector:
    matchLabels:
      app: replayfix-postgres
  template:
    metadata:
      labels:
        app: replayfix-postgres
    spec:
      containers:
        - name: postgres
          image: %s
          ports:
            - containerPort: 5432
          env:
            - name: POSTGRES_DB
              valueFrom:
                secretKeyRef:
                  name: replayfix-db-secret
                  key: database
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef:
                  name: replayfix-db-secret
                  key: username
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: replayfix-db-secret
                  key: password
          readinessProbe:
            exec:
              command:
                - sh
                - -c
                - pg_isready -U $POSTGRES_USER -d $POSTGRES_DB
            initialDelaySeconds: 5
            periodSeconds: 5
          volumeMounts:
            - name: init
              mountPath: /docker-entrypoint-initdb.d
      volumes:
        - name: init
          configMap:
            name: replayfix-db-init
---
apiVersion: v1
kind: Service
metadata:
  name: replayfix-postgres
  namespace: %s
spec:
  selector:
    app: replayfix-postgres
  ports:
    - name: postgres
      port: 5432
      targetPort: 5432
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: app
  namespace: %s
spec:
  replicas: 1
  selector:
    matchLabels:
      app: target-app
  template:
    metadata:
      labels:
        app: target-app
    spec:
%s      containers:
        - name: app
          image: %s
          ports:
            - containerPort: %d
          env:
            - name: %s
              value: jdbc:postgresql://replayfix-postgres:5432/%s
            - name: %s
              valueFrom:
                secretKeyRef:
                  name: replayfix-db-secret
                  key: username
            - name: %s
              valueFrom:
                secretKeyRef:
                  name: replayfix-db-secret
                  key: password
%s          readinessProbe:
            httpGet:
              path: %s
              port: %d
            initialDelaySeconds: 15
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: app
  namespace: %s
spec:
  selector:
    app: target-app
  ports:
    - name: http
      port: 80
      targetPort: %d
""".formatted(
                namespace,
                namespace,
                namespace,
                namespace,
                password,
                target.getDatabase().getName(),
                namespace,
                indent(initSql, 4),
                namespace,
                properties.getIntegrations().getKubernetes().getPostgresImage(),
                namespace,
                namespace,
                imagePullSecrets,
                target.getImage(),
                target.getContainerPort(),
                target.getDatasourceUrlEnv(),
                target.getDatabase().getName(),
                target.getDatasourceUsernameEnv(),
                target.getDatasourcePasswordEnv(),
                customEnvironment,
                target.getHealthPath(),
                target.getContainerPort(),
                namespace,
                target.getContainerPort()
        );
    }

    public String createReplayJobManifest(ReplayCaseEntity replayCase, Target target) {
        String requestBody = readFile(target.getReplay().getRequestFile(), "{}");
        StringBuilder headers = new StringBuilder();

        for (Map.Entry<String, String> entry : target.getReplay().getHeaders().entrySet()) {
            headers.append(" -H '")
                    .append(shell(entry.getKey() + ": " + entry.getValue()))
                    .append("'");
        }

        return """
apiVersion: v1
kind: ConfigMap
metadata:
  name: replay-request
  namespace: %s
data:
  request.json: |-
%s
---
apiVersion: batch/v1
kind: Job
metadata:
  name: replay-runner
  namespace: %s
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: replay
          image: %s
          command:
            - sh
            - -c
          args:
            - >-
              STATUS=$(curl -sS -o /tmp/body -w '%%{http_code}'
              -X %s%s
              --max-time %d
              --data-binary @/request/request.json
              http://app%s);
              echo REPLAYFIX_HTTP_STATUS=$STATUS;
              cat /tmp/body;
          volumeMounts:
            - name: request
              mountPath: /request
      volumes:
        - name: request
          configMap:
            name: replay-request
""".formatted(
                replayCase.getNamespace(),
                indent(requestBody, 4),
                replayCase.getNamespace(),
                properties.getIntegrations().getKubernetes().getCurlImage(),
                target.getReplay().getMethod(),
                headers,
                target.getReplay().getRequestTimeout().toSeconds(),
                target.getReplay().getPath()
        );
    }

    private String readFile(String path, String fallback) {
        if (path == null || path.isBlank()) return fallback;
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read configured file: " + path, e);
        }
    }

    private String environmentYaml(Map<String, String> environment) {
        if (environment.isEmpty()) return "";
        StringBuilder yaml = new StringBuilder();
        environment.forEach((key, value) -> yaml
                .append("            - name: ").append(key).append("\n")
                .append("              value: \"")
                .append(StringEscapeUtils.escapeJson(value))
                .append("\"\n"));
        return yaml.toString();
    }

    private String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + value.replace("\n", "\n" + prefix);
    }

    private String randomPassword() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String shell(String value) {
        return value.replace("'", "'\\''");
    }
}
