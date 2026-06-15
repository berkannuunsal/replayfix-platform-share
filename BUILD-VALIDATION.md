# Build and Validation Notes

## Completed checks

- 74 project files were generated.
- All non-template YAML files were parsed successfully.
- JSON files were parsed successfully.
- `pom.xml` was parsed successfully as XML.
- Shell scripts passed `bash -n`.
- Java sources were checked with `javac -proc:none`; no Java parser/syntax errors were detected before unresolved external-library errors.
- Security-sensitive defaults are disabled:
  - generated code write
  - Git push
  - pull request creation
  - production database reads
- The default application mode is `DRY_RUN`.

## Maven build limitation in the generation environment

A full Maven package/test run could not finish because this execution environment could not resolve `repo.maven.apache.org`, so Spring Boot dependencies could not be downloaded.

Observed error:

```text
Unknown host repo.maven.apache.org: Temporary failure in name resolution
```

Run the following in an environment with access to your company Maven mirror or Maven Central:

```bash
mvn -B clean verify
```

For an internal Nexus/Artifactory installation, configure the mirror in Maven `settings.xml`.
