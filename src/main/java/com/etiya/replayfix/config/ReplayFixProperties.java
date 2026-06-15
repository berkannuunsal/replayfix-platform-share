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
    }

    public static class Integrations {
        private Endpoint jira = new Endpoint();
        private Endpoint loki = new Endpoint();
        private Endpoint tempo = new Endpoint();
        private Bitbucket bitbucket = new Bitbucket();
        private Endpoint confluence = new Endpoint();
        private Endpoint rovo = new Endpoint();
        private Ai ai = new Ai();
        private Jenkins jenkins = new Jenkins();
        private Kubernetes kubernetes = new Kubernetes();
        private SourceDatabase sourceDatabase = new SourceDatabase();

        public Endpoint getJira() { return jira; }
        public void setJira(Endpoint jira) { this.jira = jira; }
        public Endpoint getLoki() { return loki; }
        public void setLoki(Endpoint loki) { this.loki = loki; }
        public Endpoint getTempo() { return tempo; }
        public void setTempo(Endpoint tempo) { this.tempo = tempo; }
        public Bitbucket getBitbucket() { return bitbucket; }
        public void setBitbucket(Bitbucket bitbucket) { this.bitbucket = bitbucket; }
        public Endpoint getConfluence() { return confluence; }
        public void setConfluence(Endpoint confluence) { this.confluence = confluence; }
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
        private String model = "internal-code-model";
        private String chatPath = "/v1/chat/completions";
        private double temperature = 0.1;
        private int maxOutputTokens = 4000;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getChatPath() { return chatPath; }
        public void setChatPath(String chatPath) { this.chatPath = chatPath; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int value) { this.maxOutputTokens = value; }
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
        private String buildJobUrl = "";
        private String imageJobUrl = "";

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
}
