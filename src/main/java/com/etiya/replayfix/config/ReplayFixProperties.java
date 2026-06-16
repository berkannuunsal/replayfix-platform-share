package com.etiya.replayfix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "replayfix")
public class ReplayFixProperties {

    public enum Mode { DRY_RUN, LIVE }

    private Mode mode = Mode.DRY_RUN;
    private String workspaceDir = "./work";
    private String namespacePrefix = "replayfix";
    private Duration cleanupAfter = Duration.ofHours(6);
    private Policy policy = new Policy();
    private Integrations integrations = new Integrations();
    private Notifications notifications = new Notifications();
    private Ai ai = new Ai();
    private Demo demo = new Demo();
    private Map<String, Target> targets = new LinkedHashMap<>();
    private Map<String, LogParser> logParsers = new LinkedHashMap<>();

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    public String getNamespacePrefix() { return namespacePrefix; }
    public void setNamespacePrefix(String namespacePrefix) { this.namespacePrefix = namespacePrefix; }
    public Duration getCleanupAfter() { return cleanupAfter; }
    public void setCleanupAfter(Duration cleanupAfter) { this.cleanupAfter = cleanupAfter; }
    public Policy getPolicy() { return policy; }
    public void setPolicy(Policy policy) { this.policy = policy; }
    public Integrations getIntegrations() { return integrations; }
    public void setIntegrations(Integrations integrations) { this.integrations = integrations; }
    public Notifications getNotifications() { return notifications; }
    public void setNotifications(Notifications notifications) { this.notifications = notifications; }
    public Ai getAi() { return ai; }
    public void setAi(Ai ai) { this.ai = ai; }
    public Demo getDemo() { return demo; }
    public void setDemo(Demo demo) { this.demo = demo; }
    public Map<String, Target> getTargets() { return targets; }
    public void setTargets(Map<String, Target> targets) { this.targets = targets; }
    public Map<String, LogParser> getLogParsers() { return logParsers; }
    public void setLogParsers(Map<String, LogParser> logParsers) { this.logParsers = logParsers; }

    public static class Policy {
        private boolean allowProductionRead;
        private boolean allowGeneratedCodeWrite;
        private boolean allowGitPush;
        private boolean allowPullRequestCreation;
        private boolean requireHumanApproval = true;
        private boolean allowSourceCheckout;
        private int sourceHistoryDepth = 500;
        private boolean allowAiSourceCode;
        private int maxLogLines = 1000;
        private int maxAiInputCharacters = 100000;
        private int aiBundleMaxChars = 120000;
        private int aiBundleMaxLokiChars = 40000;
        private int aiBundleMaxSourceChars = 40000;
        private int aiBundleMaxTempoChars = 15000;
        private int aiBundleMaxJiraChars = 15000;
        private boolean allowTestExecution;
        private boolean allowJiraCommentWrite;
        private String mavenExecutable = "mvn";
        private long localTestTimeoutSeconds = 600;
        private int localTestMaxOutputChars = 100000;
        private double patternTestMinConfidence = 0.65;
        private String kubectlExecutable = "kubectl";
        private int kubernetesTimeoutSeconds = 30;

        public boolean isAllowProductionRead() { return allowProductionRead; }
        public void setAllowProductionRead(boolean value) { this.allowProductionRead = value; }
        public boolean isAllowGeneratedCodeWrite() { return allowGeneratedCodeWrite; }
        public void setAllowGeneratedCodeWrite(boolean value) { this.allowGeneratedCodeWrite = value; }
        public boolean isAllowGitPush() { return allowGitPush; }
        public void setAllowGitPush(boolean value) { this.allowGitPush = value; }
        public boolean isAllowPullRequestCreation() { return allowPullRequestCreation; }
        public void setAllowPullRequestCreation(boolean value) { this.allowPullRequestCreation = value; }
        public boolean isRequireHumanApproval() { return requireHumanApproval; }
        public void setRequireHumanApproval(boolean value) { this.requireHumanApproval = value; }
        public boolean isAllowSourceCheckout() { return allowSourceCheckout; }
        public void setAllowSourceCheckout(boolean value) { this.allowSourceCheckout = value; }
        public int getSourceHistoryDepth() { return sourceHistoryDepth; }
        public void setSourceHistoryDepth(int value) { this.sourceHistoryDepth = value; }
        public boolean isAllowAiSourceCode() { return allowAiSourceCode; }
        public void setAllowAiSourceCode(boolean value) { this.allowAiSourceCode = value; }
        public int getMaxLogLines() { return maxLogLines; }
        public void setMaxLogLines(int value) { this.maxLogLines = value; }
        public int getMaxAiInputCharacters() { return maxAiInputCharacters; }
        public void setMaxAiInputCharacters(int value) { this.maxAiInputCharacters = value; }
        public int getAiBundleMaxChars() { return aiBundleMaxChars; }
        public void setAiBundleMaxChars(int value) { this.aiBundleMaxChars = value; }
        public int getAiBundleMaxLokiChars() { return aiBundleMaxLokiChars; }
        public void setAiBundleMaxLokiChars(int value) { this.aiBundleMaxLokiChars = value; }
        public int getAiBundleMaxSourceChars() { return aiBundleMaxSourceChars; }
        public void setAiBundleMaxSourceChars(int value) { this.aiBundleMaxSourceChars = value; }
        public int getAiBundleMaxTempoChars() { return aiBundleMaxTempoChars; }
        public void setAiBundleMaxTempoChars(int value) { this.aiBundleMaxTempoChars = value; }
        public int getAiBundleMaxJiraChars() { return aiBundleMaxJiraChars; }
        public void setAiBundleMaxJiraChars(int value) { this.aiBundleMaxJiraChars = value; }
        public boolean isAllowTestExecution() { return allowTestExecution; }
        public void setAllowTestExecution(boolean value) { this.allowTestExecution = value; }
        public boolean isAllowJiraCommentWrite() { return allowJiraCommentWrite; }
        public void setAllowJiraCommentWrite(boolean value) { this.allowJiraCommentWrite = value; }
        public String getMavenExecutable() { return mavenExecutable; }
        public void setMavenExecutable(String value) { this.mavenExecutable = value; }
        public long getLocalTestTimeoutSeconds() { return localTestTimeoutSeconds; }
        public void setLocalTestTimeoutSeconds(long value) { this.localTestTimeoutSeconds = value; }
        public int getLocalTestMaxOutputChars() { return localTestMaxOutputChars; }
        public void setLocalTestMaxOutputChars(int value) { this.localTestMaxOutputChars = value; }
        public double getPatternTestMinConfidence() { return patternTestMinConfidence; }
        public void setPatternTestMinConfidence(double value) { this.patternTestMinConfidence = value; }
        public String getKubectlExecutable() { return kubectlExecutable; }
        public void setKubectlExecutable(String value) { this.kubectlExecutable = value; }
        public int getKubernetesTimeoutSeconds() { return kubernetesTimeoutSeconds; }
        public void setKubernetesTimeoutSeconds(int value) { this.kubernetesTimeoutSeconds = value; }
    }

    public static class Integrations {
        private Endpoint jira = new Endpoint();
        private Endpoint loki = new Endpoint();
        private TempoEndpoint tempo = new TempoEndpoint();
        private Bitbucket bitbucket = new Bitbucket();
        private ConfluenceEndpoint confluence = new ConfluenceEndpoint();
        private Endpoint rovo = new Endpoint();
        private Ai ai = new Ai();
        private Jenkins jenkins = new Jenkins();
        private Kubernetes kubernetes = new Kubernetes();
        private SourceDatabase sourceDatabase = new SourceDatabase();
        private JiraWebhookEndpoint jiraWebhook = new JiraWebhookEndpoint();

        public Endpoint getJira() { return jira; }
        public void setJira(Endpoint jira) { this.jira = jira; }
        public Endpoint getLoki() { return loki; }
        public void setLoki(Endpoint loki) { this.loki = loki; }
        public TempoEndpoint getTempo() { return tempo; }
        public void setTempo(TempoEndpoint tempo) { this.tempo = tempo; }
        public Bitbucket getBitbucket() { return bitbucket; }
        public void setBitbucket(Bitbucket bitbucket) { this.bitbucket = bitbucket; }
        public ConfluenceEndpoint getConfluence() { return confluence; }
        public void setConfluence(ConfluenceEndpoint confluence) { this.confluence = confluence; }
        public Endpoint getRovo() { return rovo; }
        public void setRovo(Endpoint rovo) { this.rovo = rovo; }
        public Ai getAi() { return ai; }
        public void setAi(Ai ai) { this.ai = ai; }
        public Jenkins getJenkins() { return jenkins; }
        public void setJenkins(Jenkins jenkins) { this.jenkins = jenkins; }
        public Kubernetes getKubernetes() { return kubernetes; }
        public void setKubernetes(Kubernetes kubernetes) { this.kubernetes = kubernetes; }
        public SourceDatabase getSourceDatabase() { return sourceDatabase; }
        public void setSourceDatabase(SourceDatabase sourceDatabase) { this.sourceDatabase = sourceDatabase; }
        public JiraWebhookEndpoint getJiraWebhook() { return jiraWebhook; }
        public void setJiraWebhook(JiraWebhookEndpoint jiraWebhook) { this.jiraWebhook = jiraWebhook; }
    }

    public static class Endpoint {
        private boolean enabled;
        private String baseUrl = "";
        private String authType = "BEARER";
        private String username = "";
        private String token = "";
        private String apiPath = "";
        private Duration timeout = Duration.ofSeconds(30);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAuthType() { return authType; }
        public void setAuthType(String authType) { this.authType = authType; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getApiPath() { return apiPath; }
        public void setApiPath(String apiPath) { this.apiPath = apiPath; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Bitbucket extends Endpoint {
        private String provider = "DATA_CENTER";
        private String projectKey = "";
        private String workspace = "";
        private String gitExecutable = "git";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }
        public String getGitExecutable() { return gitExecutable; }
        public void setGitExecutable(String gitExecutable) { this.gitExecutable = gitExecutable; }
    }

    public static class Ai extends Endpoint {
        private com.etiya.replayfix.domain.AiProviderType provider = com.etiya.replayfix.domain.AiProviderType.DISABLED;
        private String model = "mock-replayfix-v1";
        private String chatPath = "/v1/chat/completions";
        private double temperature = 0.1;
        private int maxOutputTokens = 4000;
        private int maxInputChars = 120000;
        private int maxOutputChars = 30000;
        private boolean includeSourceCode = false;

        public com.etiya.replayfix.domain.AiProviderType getProvider() { return provider; }
        public void setProvider(com.etiya.replayfix.domain.AiProviderType value) { this.provider = value; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getChatPath() { return chatPath; }
        public void setChatPath(String chatPath) { this.chatPath = chatPath; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int value) { this.maxOutputTokens = value; }
        public int getMaxInputChars() { return maxInputChars; }
        public void setMaxInputChars(int value) { this.maxInputChars = value; }
        public int getMaxOutputChars() { return maxOutputChars; }
        public void setMaxOutputChars(int value) { this.maxOutputChars = value; }
        public boolean isIncludeSourceCode() { return includeSourceCode; }
        public void setIncludeSourceCode(boolean value) { this.includeSourceCode = value; }
    }

    public static class Jenkins extends Endpoint {
        private String jobName = "replayfix-validation";
        private Duration pollInterval = Duration.ofSeconds(10);
        private Duration maxWait = Duration.ofMinutes(20);
        private Map<String, JenkinsApplication> applications =
                new LinkedHashMap<>();

        public String getJobName() { return jobName; }
        public void setJobName(String jobName) { this.jobName = jobName; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration value) { this.pollInterval = value; }
        public Duration getMaxWait() { return maxWait; }
        public void setMaxWait(Duration value) { this.maxWait = value; }
        public Map<String, JenkinsApplication> getApplications() {
            return applications;
        }
        public void setApplications(
                Map<String, JenkinsApplication> applications
        ) {
            this.applications = applications;
        }
    }

    public static class JenkinsApplication {
        private List<String> repositoryAliases = new ArrayList<>();
        private String buildJobUrl = "";
        private String imageJobUrl = "";

        public List<String> getRepositoryAliases() {
            return repositoryAliases;
        }

        public void setRepositoryAliases(
                List<String> repositoryAliases
        ) {
            this.repositoryAliases = repositoryAliases == null
                    ? new ArrayList<>()
                    : repositoryAliases;
        }

        public String getBuildJobUrl() {
            return buildJobUrl;
        }

        public void setBuildJobUrl(String buildJobUrl) {
            this.buildJobUrl = buildJobUrl;
        }

        public String getImageJobUrl() {
            return imageJobUrl;
        }

        public void setImageJobUrl(String imageJobUrl) {
            this.imageJobUrl = imageJobUrl;
        }
    }

    public static class Kubernetes {
        private boolean enabled;
        private String kubectlPath = "kubectl";
        private String context = "";
        private String registryPullSecret = "";
        private String postgresImage = "postgres:15-alpine";
        private String curlImage = "curlimages/curl:8.10.1";
        private String mockImage = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public String getKubectlPath() { return kubectlPath; }
        public void setKubectlPath(String value) { this.kubectlPath = value; }
        public String getContext() { return context; }
        public void setContext(String value) { this.context = value; }
        public String getRegistryPullSecret() { return registryPullSecret; }
        public void setRegistryPullSecret(String value) { this.registryPullSecret = value; }
        public String getPostgresImage() { return postgresImage; }
        public void setPostgresImage(String value) { this.postgresImage = value; }
        public String getCurlImage() { return curlImage; }
        public void setCurlImage(String value) { this.curlImage = value; }
        public String getMockImage() { return mockImage; }
        public void setMockImage(String value) { this.mockImage = value; }
    }

    public static class SourceDatabase {
        private boolean enabled;
        private boolean production;
        private String jdbcUrl = "";
        private String username = "";
        private String password = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public boolean isProduction() { return production; }
        public void setProduction(boolean value) { this.production = value; }
        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String value) { this.jdbcUrl = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { this.username = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { this.password = value; }
    }

    public static class Target {
        private String repository = "";
        private String cloneUrl = "";
        private String localSourcePath = "";
        private String image = "";
        private int containerPort = 8080;
        private String healthPath = "/actuator/health";
        private String buildCommand = "mvn -B clean verify";
        private String datasourceUrlEnv = "SPRING_DATASOURCE_URL";
        private String datasourceUsernameEnv = "SPRING_DATASOURCE_USERNAME";
        private String datasourcePasswordEnv = "SPRING_DATASOURCE_PASSWORD";
        private Database database = new Database();
        private Replay replay = new Replay();
        private Git git = new Git();
        private Map<String, String> environment = new LinkedHashMap<>();
        private List<String> dependencies = new ArrayList<>();

        public String getRepository() { return repository; }
        public void setRepository(String value) { this.repository = value; }
        public String getCloneUrl() { return cloneUrl; }
        public void setCloneUrl(String value) { this.cloneUrl = value; }
        public String getLocalSourcePath() { return localSourcePath; }
        public void setLocalSourcePath(String value) { this.localSourcePath = value; }
        public String getImage() { return image; }
        public void setImage(String value) { this.image = value; }
        public int getContainerPort() { return containerPort; }
        public void setContainerPort(int value) { this.containerPort = value; }
        public String getHealthPath() { return healthPath; }
        public void setHealthPath(String value) { this.healthPath = value; }
        public String getBuildCommand() { return buildCommand; }
        public void setBuildCommand(String value) { this.buildCommand = value; }
        public String getDatasourceUrlEnv() { return datasourceUrlEnv; }
        public void setDatasourceUrlEnv(String value) { this.datasourceUrlEnv = value; }
        public String getDatasourceUsernameEnv() { return datasourceUsernameEnv; }
        public void setDatasourceUsernameEnv(String value) { this.datasourceUsernameEnv = value; }
        public String getDatasourcePasswordEnv() { return datasourcePasswordEnv; }
        public void setDatasourcePasswordEnv(String value) { this.datasourcePasswordEnv = value; }
        public Database getDatabase() { return database; }
        public void setDatabase(Database value) { this.database = value; }
        public Replay getReplay() { return replay; }
        public void setReplay(Replay value) { this.replay = value; }
        public Git getGit() { return git; }
        public void setGit(Git value) { this.git = value; }
        public Map<String, String> getEnvironment() { return environment; }
        public void setEnvironment(Map<String, String> value) { this.environment = value; }
        public List<String> getDependencies() { return dependencies; }
        public void setDependencies(List<String> value) { this.dependencies = value; }
    }

    public static class Database {
        private String name = "replaydb";
        private String schemaFile = "";
        private String seedFile = "";
        private boolean persistent;
        private String storageSize = "2Gi";

        public String getName() { return name; }
        public void setName(String value) { this.name = value; }
        public String getSchemaFile() { return schemaFile; }
        public void setSchemaFile(String value) { this.schemaFile = value; }
        public String getSeedFile() { return seedFile; }
        public void setSeedFile(String value) { this.seedFile = value; }
        public boolean isPersistent() { return persistent; }
        public void setPersistent(boolean value) { this.persistent = value; }
        public String getStorageSize() { return storageSize; }
        public void setStorageSize(String value) { this.storageSize = value; }
    }

    public static class Replay {
        private String method = "POST";
        private String path = "/";
        private String requestFile = "";
        private Map<String, String> headers = new LinkedHashMap<>();
        private int expectedStatus = 500;
        private String expectedBodyRegex = ".*";
        private String expectedLogRegex = "(ERROR|Exception)";
        private Duration requestTimeout = Duration.ofSeconds(30);

        public String getMethod() { return method; }
        public void setMethod(String value) { this.method = value; }
        public String getPath() { return path; }
        public void setPath(String value) { this.path = value; }
        public String getRequestFile() { return requestFile; }
        public void setRequestFile(String value) { this.requestFile = value; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> value) { this.headers = value; }
        public int getExpectedStatus() { return expectedStatus; }
        public void setExpectedStatus(int value) { this.expectedStatus = value; }
        public String getExpectedBodyRegex() { return expectedBodyRegex; }
        public void setExpectedBodyRegex(String value) { this.expectedBodyRegex = value; }
        public String getExpectedLogRegex() { return expectedLogRegex; }
        public void setExpectedLogRegex(String value) { this.expectedLogRegex = value; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration value) { this.requestTimeout = value; }
    }

    public static class Git {
        private String sourceBranch = "main";
        private String branchPrefix = "replayfix/";
        private List<String> reviewerUsers = new ArrayList<>();

        public String getSourceBranch() { return sourceBranch; }
        public void setSourceBranch(String value) { this.sourceBranch = value; }
        public String getBranchPrefix() { return branchPrefix; }
        public void setBranchPrefix(String value) { this.branchPrefix = value; }
        public List<String> getReviewerUsers() { return reviewerUsers; }
        public void setReviewerUsers(List<String> value) { this.reviewerUsers = value; }
    }

    public static class TempoEndpoint extends Endpoint {
        private String accessMode = "GRAFANA_PROXY";
        private String datasourceUid = "";
        private int maxTracesPerCase = 5;
        private int maxSpansPerTrace = 5000;
        private int maxResponseChars = 2000000;

        public String getAccessMode() { return accessMode; }
        public void setAccessMode(String value) { this.accessMode = value; }
        public String getDatasourceUid() { return datasourceUid; }
        public void setDatasourceUid(String value) { this.datasourceUid = value; }
        public int getMaxTracesPerCase() { return maxTracesPerCase; }
        public void setMaxTracesPerCase(int value) { this.maxTracesPerCase = value; }
        public int getMaxSpansPerTrace() { return maxSpansPerTrace; }
        public void setMaxSpansPerTrace(int value) { this.maxSpansPerTrace = value; }
        public int getMaxResponseChars() { return maxResponseChars; }
        public void setMaxResponseChars(int value) { this.maxResponseChars = value; }
    }

    public static class ConfluenceEndpoint extends Endpoint {
        private int maxSearchResults = 15;
        private int maxPagesPerCase = 5;
        private int maxPageChars = 30000;
        private int maxTotalChars = 80000;
        private String allowedSpaceKeys = "";

        public int getMaxSearchResults() { return maxSearchResults; }
        public void setMaxSearchResults(int value) { this.maxSearchResults = value; }
        public int getMaxPagesPerCase() { return maxPagesPerCase; }
        public void setMaxPagesPerCase(int value) { this.maxPagesPerCase = value; }
        public int getMaxPageChars() { return maxPageChars; }
        public void setMaxPageChars(int value) { this.maxPageChars = value; }
        public int getMaxTotalChars() { return maxTotalChars; }
        public void setMaxTotalChars(int value) { this.maxTotalChars = value; }
        public String getAllowedSpaceKeys() { return allowedSpaceKeys; }
        public void setAllowedSpaceKeys(String value) { this.allowedSpaceKeys = value; }
    }

    public static class LogParser {
        private List<String> orderIdPatterns = new ArrayList<>();
        private List<String> traceIdPatterns = new ArrayList<>();
        private List<String> exceptionPatterns = new ArrayList<>();
        private List<String> versionPatterns = new ArrayList<>();

        public List<String> getOrderIdPatterns() { return orderIdPatterns; }
        public void setOrderIdPatterns(List<String> value) { this.orderIdPatterns = value; }
        public List<String> getTraceIdPatterns() { return traceIdPatterns; }
        public void setTraceIdPatterns(List<String> value) { this.traceIdPatterns = value; }
        public List<String> getExceptionPatterns() { return exceptionPatterns; }
        public void setExceptionPatterns(List<String> value) { this.exceptionPatterns = value; }
        public List<String> getVersionPatterns() { return versionPatterns; }
        public void setVersionPatterns(List<String> value) { this.versionPatterns = value; }
    }

    public static class JiraWebhookEndpoint {
        private boolean enabled = false;
        private String secret = "";
        private List<String> allowedEventTypes = List.of("jira:issue_created", "jira:issue_updated");
        private String allowedProjectKeys = "";
        private String allowedIssueTypes = "";
        private String allowedStatuses = "";
        private int maxBodyChars = 1000000;
        private int replayWindowSeconds = 300;
        private boolean autoPreviewEnabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public String getSecret() { return secret; }
        public void setSecret(String value) { this.secret = value; }
        public List<String> getAllowedEventTypes() { return allowedEventTypes; }
        public void setAllowedEventTypes(List<String> value) { this.allowedEventTypes = value; }
        public String getAllowedProjectKeys() { return allowedProjectKeys; }
        public void setAllowedProjectKeys(String value) { this.allowedProjectKeys = value; }
        public String getAllowedIssueTypes() { return allowedIssueTypes; }
        public void setAllowedIssueTypes(String value) { this.allowedIssueTypes = value; }
        public String getAllowedStatuses() { return allowedStatuses; }
        public void setAllowedStatuses(String value) { this.allowedStatuses = value; }
        public int getMaxBodyChars() { return maxBodyChars; }
        public void setMaxBodyChars(int value) { this.maxBodyChars = value; }
        public int getReplayWindowSeconds() { return replayWindowSeconds; }
        public void setReplayWindowSeconds(int value) { this.replayWindowSeconds = value; }
        public boolean isAutoPreviewEnabled() { return autoPreviewEnabled; }
        public void setAutoPreviewEnabled(boolean value) { this.autoPreviewEnabled = value; }
    }

    public static class Notifications {
        private Webhook webhook = new Webhook();

        public Webhook getWebhook() { return webhook; }
        public void setWebhook(Webhook value) { this.webhook = value; }

        public static class Webhook {
            private boolean enabled = false;
            private String url;
            private String secret;
            private int connectTimeoutSeconds = 10;
            private int readTimeoutSeconds = 20;
            private int maxAttempts = 3;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean value) { this.enabled = value; }
            public String getUrl() { return url; }
            public void setUrl(String value) { this.url = value; }
            public String getSecret() { return secret; }
            public void setSecret(String value) { this.secret = value; }
            public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
            public void setConnectTimeoutSeconds(int value) { this.connectTimeoutSeconds = value; }
            public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
            public void setReadTimeoutSeconds(int value) { this.readTimeoutSeconds = value; }
            public int getMaxAttempts() { return maxAttempts; }
            public void setMaxAttempts(int value) { this.maxAttempts = value; }
        }
    }

    public static class Demo {
        private boolean enabled = false;
        private boolean allowReset = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public boolean isAllowReset() { return allowReset; }
        public void setAllowReset(boolean value) { this.allowReset = value; }
    }
}
