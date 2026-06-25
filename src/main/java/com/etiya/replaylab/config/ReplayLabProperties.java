package com.etiya.replaylab.config;

import com.etiya.replaylab.domain.AiProviderType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "replaylab")
public class ReplayLabProperties {

    public enum Mode { DRY_RUN, LIVE }

    private Mode mode = Mode.DRY_RUN;
    private String workspaceDir = "./work";
    private String namespacePrefix = "replaylab";
    private Duration cleanupAfter = Duration.ofHours(6);
    private Policy policy = new Policy();
    private Integrations integrations = new Integrations();
    private Notifications notifications = new Notifications();
    private Ai ai = new Ai();
    private Llm llm = new Llm();
    private ArgoCd argocd = new ArgoCd();
    private RealActions realActions = new RealActions();
    private JenkinsSettings jenkins = new JenkinsSettings();
    private Demo demo = new Demo();
    private DbEvidence dbEvidence = new DbEvidence();
    private Map<String, Target> targets = new LinkedHashMap<>();
    private Map<String, ReplayComponent> components = new LinkedHashMap<>();
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
    public Llm getLlm() { return llm; }
    public void setLlm(Llm value) {
        this.llm = value == null ? new Llm() : value;
    }
    public ArgoCd getArgocd() { return argocd; }
    public void setArgocd(ArgoCd value) { this.argocd = value; }
    public RealActions getRealActions() { return realActions; }
    public void setRealActions(RealActions value) {
        this.realActions = value == null ? new RealActions() : value;
    }
    public JenkinsSettings getJenkins() { return jenkins; }
    public void setJenkins(JenkinsSettings value) {
        this.jenkins = value == null ? new JenkinsSettings() : value;
    }
    public Demo getDemo() { return demo; }
    public void setDemo(Demo demo) { this.demo = demo; }
    public DbEvidence getDbEvidence() { return dbEvidence; }
    public void setDbEvidence(DbEvidence value) { this.dbEvidence = value; }
    public Map<String, Target> getTargets() { return targets; }
    public void setTargets(Map<String, Target> targets) { this.targets = targets; }
    public Map<String, ReplayComponent> getComponents() { return components; }
    public void setComponents(Map<String, ReplayComponent> value) {
        this.components = value == null ? new LinkedHashMap<>() : value;
    }
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
        private BranchStrategy branchStrategy = new BranchStrategy();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
        public String getWorkspace() { return workspace; }
        public void setWorkspace(String workspace) { this.workspace = workspace; }
        public String getGitExecutable() { return gitExecutable; }
        public void setGitExecutable(String gitExecutable) { this.gitExecutable = gitExecutable; }
        public BranchStrategy getBranchStrategy() { return branchStrategy; }
        public void setBranchStrategy(BranchStrategy value) {
            this.branchStrategy = value == null ? new BranchStrategy() : value;
        }
    }

    public static class Ai extends Endpoint {
        private com.etiya.replaylab.domain.AiProviderType provider = com.etiya.replaylab.domain.AiProviderType.DISABLED;
        private String model = "mock-replaylab-v1";
        private String chatPath = "/v1/chat/completions";
        private double temperature = 0.1;
        private int maxOutputTokens = 4000;
        private int maxInputChars = 120000;
        private int maxOutputChars = 30000;
        private boolean includeSourceCode = false;
        private Company company = new Company();

        public com.etiya.replaylab.domain.AiProviderType getProvider() { return provider; }
        public void setProvider(com.etiya.replaylab.domain.AiProviderType value) { this.provider = value; }
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
        public Company getCompany() { return company; }
        public void setCompany(Company value) { this.company = value; }
    }

    public static class ArgoCd {
        private boolean enabled;
        private String baseUrl = "";
        private String tokenEnvVarName = "";
        private boolean tokenConfigured;
        private String project = "";
        private boolean realProvisioningEnabled;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { this.baseUrl = value; }
        public String getTokenEnvVarName() { return tokenEnvVarName; }
        public void setTokenEnvVarName(String value) { this.tokenEnvVarName = value; }
        public boolean isTokenConfigured() { return tokenConfigured; }
        public void setTokenConfigured(boolean value) { this.tokenConfigured = value; }
        public String getProject() { return project; }
        public void setProject(String value) { this.project = value; }
        public boolean isRealProvisioningEnabled() { return realProvisioningEnabled; }
        public void setRealProvisioningEnabled(boolean value) { this.realProvisioningEnabled = value; }
    }

    public static class JenkinsSettings {
        private JenkinsValidation validation = new JenkinsValidation();

        public JenkinsValidation getValidation() { return validation; }
        public void setValidation(JenkinsValidation value) {
            this.validation = value == null ? new JenkinsValidation() : value;
        }
    }

    public static class JenkinsValidation {
        private JenkinsValidationTarget backend = new JenkinsValidationTarget(
                "MODERNIZATION.BACKEND_BUILD_12"
        );
        private JenkinsValidationTarget frontend = new JenkinsValidationTarget("");

        public JenkinsValidationTarget getBackend() { return backend; }
        public void setBackend(JenkinsValidationTarget value) {
            this.backend = value == null
                    ? new JenkinsValidationTarget("MODERNIZATION.BACKEND_BUILD_12")
                    : value;
        }
        public JenkinsValidationTarget getFrontend() { return frontend; }
        public void setFrontend(JenkinsValidationTarget value) {
            this.frontend = value == null ? new JenkinsValidationTarget("") : value;
        }
    }

    public static class JenkinsValidationTarget {
        private String defaultJobName = "";

        public JenkinsValidationTarget() {
        }

        public JenkinsValidationTarget(String defaultJobName) {
            this.defaultJobName = defaultJobName == null ? "" : defaultJobName;
        }

        public String getDefaultJobName() { return defaultJobName; }
        public void setDefaultJobName(String value) {
            this.defaultJobName = value == null ? "" : value;
        }
    }

    public static class BranchStrategy {
        private String sourceBaseBranch = "master";
        private String integrationBranchPattern = "Integration/{environment}/{defectKey}";
        private String bugfixBranchPattern = "bugfix/{defectKey}";
        private Map<String, String> environmentTargets = new LinkedHashMap<>();

        public String getSourceBaseBranch() { return sourceBaseBranch; }
        public void setSourceBaseBranch(String value) { this.sourceBaseBranch = value; }
        public String getIntegrationBranchPattern() { return integrationBranchPattern; }
        public void setIntegrationBranchPattern(String value) { this.integrationBranchPattern = value; }
        public String getBugfixBranchPattern() { return bugfixBranchPattern; }
        public void setBugfixBranchPattern(String value) { this.bugfixBranchPattern = value; }
        public Map<String, String> getEnvironmentTargets() { return environmentTargets; }
        public void setEnvironmentTargets(Map<String, String> value) {
            this.environmentTargets = value == null ? new LinkedHashMap<>() : value;
        }
    }

    public static class RealActions {
        private boolean enabled;
        private boolean jiraCreateEnabled;
        private boolean bitbucketBranchCreateEnabled;
        private boolean bitbucketMergeEnabled;
        private boolean bitbucketPrCreateEnabled;
        private boolean bitbucketPushEnabled;
        private boolean jenkinsValidationTriggerEnabled;
        private boolean requireConfirmation = true;
        private boolean requireGuardrailsAccepted = true;
        private String draftPrTitlePrefix = "[DRAFT] ReplayLab";
        private String defaultDevelopmentBaseBranch = "master";
        private String defaultEnvironmentTargetBranch = "test2";
        private String bugfixBranchPrefix = "bugfix/";
        private String integrationBranchPrefix = "integration/test2/";
        private List<String> protectedBranches = new ArrayList<>(
                List.of("master", "test1", "test2", "preprod", "prod")
        );
        private String jiraSubTaskIssueType = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public boolean isJiraCreateEnabled() { return jiraCreateEnabled; }
        public void setJiraCreateEnabled(boolean value) { this.jiraCreateEnabled = value; }
        public boolean isBitbucketBranchCreateEnabled() { return bitbucketBranchCreateEnabled; }
        public void setBitbucketBranchCreateEnabled(boolean value) { this.bitbucketBranchCreateEnabled = value; }
        public boolean isBitbucketMergeEnabled() { return bitbucketMergeEnabled; }
        public void setBitbucketMergeEnabled(boolean value) { this.bitbucketMergeEnabled = value; }
        public boolean isBitbucketPrCreateEnabled() { return bitbucketPrCreateEnabled; }
        public void setBitbucketPrCreateEnabled(boolean value) { this.bitbucketPrCreateEnabled = value; }
        public boolean isBitbucketPushEnabled() { return bitbucketPushEnabled; }
        public void setBitbucketPushEnabled(boolean value) { this.bitbucketPushEnabled = value; }
        public boolean isJenkinsValidationTriggerEnabled() { return jenkinsValidationTriggerEnabled; }
        public void setJenkinsValidationTriggerEnabled(boolean value) {
            this.jenkinsValidationTriggerEnabled = value;
        }
        public boolean isRequireConfirmation() { return requireConfirmation; }
        public void setRequireConfirmation(boolean value) { this.requireConfirmation = value; }
        public boolean isRequireGuardrailsAccepted() { return requireGuardrailsAccepted; }
        public void setRequireGuardrailsAccepted(boolean value) { this.requireGuardrailsAccepted = value; }
        public String getDraftPrTitlePrefix() { return draftPrTitlePrefix; }
        public void setDraftPrTitlePrefix(String value) { this.draftPrTitlePrefix = value; }
        public String getDefaultDevelopmentBaseBranch() { return defaultDevelopmentBaseBranch; }
        public void setDefaultDevelopmentBaseBranch(String value) { this.defaultDevelopmentBaseBranch = value; }
        public String getDefaultEnvironmentTargetBranch() { return defaultEnvironmentTargetBranch; }
        public void setDefaultEnvironmentTargetBranch(String value) { this.defaultEnvironmentTargetBranch = value; }
        public String getBugfixBranchPrefix() { return bugfixBranchPrefix; }
        public void setBugfixBranchPrefix(String value) { this.bugfixBranchPrefix = value; }
        public String getIntegrationBranchPrefix() { return integrationBranchPrefix; }
        public void setIntegrationBranchPrefix(String value) { this.integrationBranchPrefix = value; }
        public List<String> getProtectedBranches() { return protectedBranches; }
        public void setProtectedBranches(List<String> value) {
            this.protectedBranches = value == null
                    ? new ArrayList<>()
                    : value;
        }
        public String getJiraSubTaskIssueType() { return jiraSubTaskIssueType; }
        public void setJiraSubTaskIssueType(String value) { this.jiraSubTaskIssueType = value; }
    }

    public static class ReplayComponent {
        private String componentKey = "";
        private String displayName = "";
        private String componentType = "UNKNOWN";
        private String repositoryProject = "";
        private String repositorySlug = "";
        private String defaultBranch = "";
        private String gitOpsRepo = "";
        private String helmChartPath = "";
        private String valuesPath = "";
        private String imageRepository = "";
        private String defaultNamespace = "";
        private String healthPath = "";
        private int servicePort;
        private String ownerTeam = "";
        private List<String> dependencyModes = new ArrayList<>();
        private boolean allowReplay;
        private boolean allowLoadTest;
        private boolean requiresApproval = true;

        public String getComponentKey() { return componentKey; }
        public void setComponentKey(String value) { this.componentKey = value; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String value) { this.displayName = value; }
        public String getComponentType() { return componentType; }
        public void setComponentType(String value) { this.componentType = value; }
        public String getRepositoryProject() { return repositoryProject; }
        public void setRepositoryProject(String value) { this.repositoryProject = value; }
        public String getRepositorySlug() { return repositorySlug; }
        public void setRepositorySlug(String value) { this.repositorySlug = value; }
        public String getDefaultBranch() { return defaultBranch; }
        public void setDefaultBranch(String value) { this.defaultBranch = value; }
        public String getGitOpsRepo() { return gitOpsRepo; }
        public void setGitOpsRepo(String value) { this.gitOpsRepo = value; }
        public String getHelmChartPath() { return helmChartPath; }
        public void setHelmChartPath(String value) { this.helmChartPath = value; }
        public String getValuesPath() { return valuesPath; }
        public void setValuesPath(String value) { this.valuesPath = value; }
        public String getImageRepository() { return imageRepository; }
        public void setImageRepository(String value) { this.imageRepository = value; }
        public String getDefaultNamespace() { return defaultNamespace; }
        public void setDefaultNamespace(String value) { this.defaultNamespace = value; }
        public String getHealthPath() { return healthPath; }
        public void setHealthPath(String value) { this.healthPath = value; }
        public int getServicePort() { return servicePort; }
        public void setServicePort(int value) { this.servicePort = value; }
        public String getOwnerTeam() { return ownerTeam; }
        public void setOwnerTeam(String value) { this.ownerTeam = value; }
        public List<String> getDependencyModes() { return dependencyModes; }
        public void setDependencyModes(List<String> value) {
            this.dependencyModes = value == null ? new ArrayList<>() : value;
        }
        public boolean isAllowReplay() { return allowReplay; }
        public void setAllowReplay(boolean value) { this.allowReplay = value; }
        public boolean isAllowLoadTest() { return allowLoadTest; }
        public void setAllowLoadTest(boolean value) { this.allowLoadTest = value; }
        public boolean isRequiresApproval() { return requiresApproval; }
        public void setRequiresApproval(boolean value) { this.requiresApproval = value; }
    }

    public static class Llm {
        private AiProviderType provider = AiProviderType.DISABLED;
        private String baseUrl = "";
        private String apiKeyEnv = "COMPANY_LLM_API_KEY";
        private String defaultModelName = "openai/gpt-3.5-turbo";
        private Double weeklyBudgetUsd;
        private String budgetPeriod = "WEEKLY";
        private double monthlyBudgetUsd = 200.0;
        private boolean budgetTrackingEnabled = true;
        private boolean allowPlainModelNames;
        private List<String> allowedModelNames = new ArrayList<>(List.of(
                "openai/gpt-3.5-turbo",
                "openai/gpt-4o-mini",
                "openai/gpt-4o"
        ));
        private Map<String, LlmModelProfile> modelProfiles =
                new LinkedHashMap<>();

        public Llm() {
            modelProfiles.put("CODE_ADVISORY", new LlmModelProfile(
                    "openai/gpt-4o-mini",
                    90,
                    12000,
                    3000
            ));
            modelProfiles.put("TEST_SUGGESTION", new LlmModelProfile(
                    "openai/gpt-4o-mini",
                    60,
                    10000,
                    2500
            ));
            modelProfiles.put("RISK_REVIEW", new LlmModelProfile(
                    "openai/gpt-4o",
                    90,
                    12000,
                    3000
            ));
            modelProfiles.put("EXECUTIVE_SUMMARY", new LlmModelProfile(
                    "openai/gpt-4o-mini",
                    45,
                    8000,
                    1200
            ));
        }

        public AiProviderType getProvider() { return provider; }
        public void setProvider(AiProviderType value) { this.provider = value; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { this.baseUrl = value; }
        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String value) { this.apiKeyEnv = value; }
        public String getDefaultModelName() { return defaultModelName; }
        public void setDefaultModelName(String value) { this.defaultModelName = value; }
        public double getWeeklyBudgetUsd() {
            return weeklyBudgetUsd == null ? monthlyBudgetUsd : weeklyBudgetUsd;
        }
        public void setWeeklyBudgetUsd(Double value) { this.weeklyBudgetUsd = value; }
        public String getBudgetPeriod() { return budgetPeriod; }
        public void setBudgetPeriod(String value) { this.budgetPeriod = value; }
        public double getMonthlyBudgetUsd() { return monthlyBudgetUsd; }
        public void setMonthlyBudgetUsd(double value) { this.monthlyBudgetUsd = value; }
        public boolean isBudgetTrackingEnabled() { return budgetTrackingEnabled; }
        public void setBudgetTrackingEnabled(boolean value) { this.budgetTrackingEnabled = value; }
        public boolean isAllowPlainModelNames() { return allowPlainModelNames; }
        public void setAllowPlainModelNames(boolean value) { this.allowPlainModelNames = value; }
        public List<String> getAllowedModelNames() { return allowedModelNames; }
        public void setAllowedModelNames(List<String> value) {
            this.allowedModelNames = value == null
                    ? new ArrayList<>()
                    : value;
        }
        public Map<String, LlmModelProfile> getModelProfiles() {
            return modelProfiles;
        }
        public void setModelProfiles(Map<String, LlmModelProfile> value) {
            this.modelProfiles = value == null
                    ? new LinkedHashMap<>()
                    : value;
        }
    }

    public static class LlmModelProfile {
        private String modelName = "";
        private int timeoutSeconds = 60;
        private int maxPromptChars = 12000;
        private int maxOutputTokens = 3000;

        public LlmModelProfile() {
        }

        public LlmModelProfile(
                String modelName,
                int timeoutSeconds,
                int maxPromptChars,
                int maxOutputTokens
        ) {
            this.modelName = modelName;
            this.timeoutSeconds = timeoutSeconds;
            this.maxPromptChars = maxPromptChars;
            this.maxOutputTokens = maxOutputTokens;
        }

        public String getModelName() { return modelName; }
        public void setModelName(String value) { this.modelName = value; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int value) { this.timeoutSeconds = value; }
        public int getMaxPromptChars() { return maxPromptChars; }
        public void setMaxPromptChars(int value) { this.maxPromptChars = value; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int value) { this.maxOutputTokens = value; }
    }

    public static class Company {
        private String baseUrl = "";
        private String endpoint = "/v1/chat/completions";
        private String model = "";
        private String authType = "BEARER";
        private String token = "";
        private long timeoutMs = 30000;
        private int maxInputChars = 120000;
        private int maxOutputChars = 30000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { this.baseUrl = value; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String value) { this.endpoint = value; }
        public String getModel() { return model; }
        public void setModel(String value) { this.model = value; }
        public String getAuthType() { return authType; }
        public void setAuthType(String value) { this.authType = value; }
        public String getToken() { return token; }
        public void setToken(String value) { this.token = value; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long value) { this.timeoutMs = value; }
        public int getMaxInputChars() { return maxInputChars; }
        public void setMaxInputChars(int value) { this.maxInputChars = value; }
        public int getMaxOutputChars() { return maxOutputChars; }
        public void setMaxOutputChars(int value) { this.maxOutputChars = value; }
    }

    public static class Jenkins extends Endpoint {
        private String jobName = "replaylab-validation";
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
        private String applicationKey = "";
        private String backendProjectKey = "";
        private String backendRepositorySlug = "";
        private String customerUiProjectKey = "";
        private String customerUiRepositorySlug = "";
        private String defaultBranch = "";
        private String argocdApplicationName = "";
        private String argocdProject = "";
        private String destinationServer = "";
        private String clusterName = "";
        private String namespaceStrategy = "";
        private String backendArgoCdApplicationName = "";
        private String backendSourceRepoUrl = "";
        private String backendChartPath = "";
        private String backendTargetRevision = "";
        private String backendValuesFile = "";
        private String backendNamespace = "";
        private String backendImageRepository = "";
        private String backendImageTag = "";
        private String backendHealthEndpoint = "";
        private String customerUiArgoCdApplicationName = "";
        private String customerUiSourceRepoUrl = "";
        private String customerUiChartPath = "";
        private String customerUiTargetRevision = "";
        private String customerUiValuesFile = "";
        private String customerUiNamespace = "";
        private String customerUiImageRepository = "";
        private String customerUiImageTag = "";
        private String customerUiHealthEndpoint = "";
        private String replayNamespacePrefix = "";
        private String preCreatedReplayNamespace = "";
        private String dbStateMode = "";
        private boolean stateContinuationRequested;
        private String secretStrategy = "";
        private boolean dbStrategyConfirmed;
        private boolean ingressConfigured;
        private String cleanupTtl = "";
        private String dbRuntimeMode = "";
        private boolean dbTest2WriteAllowed;
        private boolean dbTest2WriteRequiresApproval;
        private String dbRuntimeUserMode = "";
        private String activeMqMode = "";
        private boolean activeMqConnectionRequired;
        private boolean activeMqConsumerEnabled;
        private boolean activeMqProducerEnabled;
        private String kafkaMode = "";
        private boolean kafkaConnectionRequired;
        private boolean kafkaConsumerEnabled;
        private boolean kafkaProducerEnabled;
        private String redisMode = "";
        private boolean redisKeyPrefixRequired;
        private String emailMode = "";
        private String httpExternalMode = "";
        private String mqListenerEnabledConfigKey = "";
        private String kafkaListenerEnabledConfigKey = "";
        private String redisKeyPrefixConfigKey = "";
        private List<String> requiredMqSecretKeys = new ArrayList<>();
        private List<String> requiredKafkaSecretKeys = new ArrayList<>();
        private List<String> requiredRedisSecretKeys = new ArrayList<>();
        private int backendServicePort = 8080;
        private String backendContextPath = "";
        private String accessMode = "";
        private String replayHostSuffix = "";
        private String customerUiBackendBaseUrlConfigKey = "";
        private String backendAllowedOriginsConfigKey = "";
        private List<String> requiredDbSecretKeys = new ArrayList<>();
        private List<String> requiredCustomerUiConfigKeys = new ArrayList<>();
        private List<String> requiredBackendConfigKeys = new ArrayList<>();
        private String argocdDestinationCluster = "";
        private String argocdDestinationNamespacePrefix = "";
        private String helmChartPath = "";
        private String customerUiHelmChartPath = "";
        private String mockServerChartPath = "";
        private String repository = "";
        private String cloneUrl = "";
        private String localSourcePath = "";
        private String sourceWorkspaceRoot = "";
        private boolean sourceCandidateExtractionEnabled;
        private String sourceCandidateSource = "";
        private String sourceCandidateExtractionBranch = "";
        private List<String> allowedSourceExtensions = new ArrayList<>();
        private int maxSourceCandidateFiles = 3;
        private int maxSnippetChars = 12000;
        private SourceCandidateBitbucket bitbucket =
                new SourceCandidateBitbucket();
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
        private Map<String, ExternalDependency> externalDependencies =
                new LinkedHashMap<>();
        private Map<String, DbSampleDomain> dbSampleDomains =
                new LinkedHashMap<>();

        public String getApplicationKey() { return applicationKey; }
        public void setApplicationKey(String value) { this.applicationKey = value; }
        public String getBackendProjectKey() { return backendProjectKey; }
        public void setBackendProjectKey(String value) { this.backendProjectKey = value; }
        public String getBackendRepositorySlug() { return backendRepositorySlug; }
        public void setBackendRepositorySlug(String value) { this.backendRepositorySlug = value; }
        public String getCustomerUiProjectKey() { return customerUiProjectKey; }
        public void setCustomerUiProjectKey(String value) { this.customerUiProjectKey = value; }
        public String getCustomerUiRepositorySlug() { return customerUiRepositorySlug; }
        public void setCustomerUiRepositorySlug(String value) { this.customerUiRepositorySlug = value; }
        public String getDefaultBranch() { return defaultBranch; }
        public void setDefaultBranch(String value) { this.defaultBranch = value; }
        public String getArgocdApplicationName() { return argocdApplicationName; }
        public void setArgocdApplicationName(String value) { this.argocdApplicationName = value; }
        public String getArgocdProject() { return argocdProject; }
        public void setArgocdProject(String value) { this.argocdProject = value; }
        public String getDestinationServer() { return destinationServer; }
        public void setDestinationServer(String value) { this.destinationServer = value; }
        public String getClusterName() { return clusterName; }
        public void setClusterName(String value) { this.clusterName = value; }
        public String getNamespaceStrategy() { return namespaceStrategy; }
        public void setNamespaceStrategy(String value) { this.namespaceStrategy = value; }
        public String getBackendArgoCdApplicationName() { return backendArgoCdApplicationName; }
        public void setBackendArgoCdApplicationName(String value) { this.backendArgoCdApplicationName = value; }
        public String getBackendSourceRepoUrl() { return backendSourceRepoUrl; }
        public void setBackendSourceRepoUrl(String value) { this.backendSourceRepoUrl = value; }
        public String getBackendChartPath() { return backendChartPath; }
        public void setBackendChartPath(String value) { this.backendChartPath = value; }
        public String getBackendTargetRevision() { return backendTargetRevision; }
        public void setBackendTargetRevision(String value) { this.backendTargetRevision = value; }
        public String getBackendValuesFile() { return backendValuesFile; }
        public void setBackendValuesFile(String value) { this.backendValuesFile = value; }
        public String getBackendNamespace() { return backendNamespace; }
        public void setBackendNamespace(String value) { this.backendNamespace = value; }
        public String getBackendImageRepository() { return backendImageRepository; }
        public void setBackendImageRepository(String value) { this.backendImageRepository = value; }
        public String getBackendImageTag() { return backendImageTag; }
        public void setBackendImageTag(String value) { this.backendImageTag = value; }
        public String getBackendHealthEndpoint() { return backendHealthEndpoint; }
        public void setBackendHealthEndpoint(String value) { this.backendHealthEndpoint = value; }
        public String getCustomerUiArgoCdApplicationName() { return customerUiArgoCdApplicationName; }
        public void setCustomerUiArgoCdApplicationName(String value) { this.customerUiArgoCdApplicationName = value; }
        public String getCustomerUiSourceRepoUrl() { return customerUiSourceRepoUrl; }
        public void setCustomerUiSourceRepoUrl(String value) { this.customerUiSourceRepoUrl = value; }
        public String getCustomerUiChartPath() { return customerUiChartPath; }
        public void setCustomerUiChartPath(String value) { this.customerUiChartPath = value; }
        public String getCustomerUiTargetRevision() { return customerUiTargetRevision; }
        public void setCustomerUiTargetRevision(String value) { this.customerUiTargetRevision = value; }
        public String getCustomerUiValuesFile() { return customerUiValuesFile; }
        public void setCustomerUiValuesFile(String value) { this.customerUiValuesFile = value; }
        public String getCustomerUiNamespace() { return customerUiNamespace; }
        public void setCustomerUiNamespace(String value) { this.customerUiNamespace = value; }
        public String getCustomerUiImageRepository() { return customerUiImageRepository; }
        public void setCustomerUiImageRepository(String value) { this.customerUiImageRepository = value; }
        public String getCustomerUiImageTag() { return customerUiImageTag; }
        public void setCustomerUiImageTag(String value) { this.customerUiImageTag = value; }
        public String getCustomerUiHealthEndpoint() { return customerUiHealthEndpoint; }
        public void setCustomerUiHealthEndpoint(String value) { this.customerUiHealthEndpoint = value; }
        public String getReplayNamespacePrefix() { return replayNamespacePrefix; }
        public void setReplayNamespacePrefix(String value) { this.replayNamespacePrefix = value; }
        public String getPreCreatedReplayNamespace() { return preCreatedReplayNamespace; }
        public void setPreCreatedReplayNamespace(String value) { this.preCreatedReplayNamespace = value; }
        public String getDbStateMode() { return dbStateMode; }
        public void setDbStateMode(String value) { this.dbStateMode = value; }
        public boolean isStateContinuationRequested() { return stateContinuationRequested; }
        public void setStateContinuationRequested(boolean value) { this.stateContinuationRequested = value; }
        public String getSecretStrategy() { return secretStrategy; }
        public void setSecretStrategy(String value) { this.secretStrategy = value; }
        public boolean isDbStrategyConfirmed() { return dbStrategyConfirmed; }
        public void setDbStrategyConfirmed(boolean value) { this.dbStrategyConfirmed = value; }
        public boolean isIngressConfigured() { return ingressConfigured; }
        public void setIngressConfigured(boolean value) { this.ingressConfigured = value; }
        public String getCleanupTtl() { return cleanupTtl; }
        public void setCleanupTtl(String value) { this.cleanupTtl = value; }
        public String getDbRuntimeMode() { return dbRuntimeMode; }
        public void setDbRuntimeMode(String value) { this.dbRuntimeMode = value; }
        public boolean isDbTest2WriteAllowed() { return dbTest2WriteAllowed; }
        public void setDbTest2WriteAllowed(boolean value) { this.dbTest2WriteAllowed = value; }
        public boolean isDbTest2WriteRequiresApproval() { return dbTest2WriteRequiresApproval; }
        public void setDbTest2WriteRequiresApproval(boolean value) { this.dbTest2WriteRequiresApproval = value; }
        public String getDbRuntimeUserMode() { return dbRuntimeUserMode; }
        public void setDbRuntimeUserMode(String value) { this.dbRuntimeUserMode = value; }
        public String getActiveMqMode() { return activeMqMode; }
        public void setActiveMqMode(String value) { this.activeMqMode = value; }
        public boolean isActiveMqConnectionRequired() { return activeMqConnectionRequired; }
        public void setActiveMqConnectionRequired(boolean value) { this.activeMqConnectionRequired = value; }
        public boolean isActiveMqConsumerEnabled() { return activeMqConsumerEnabled; }
        public void setActiveMqConsumerEnabled(boolean value) { this.activeMqConsumerEnabled = value; }
        public boolean isActiveMqProducerEnabled() { return activeMqProducerEnabled; }
        public void setActiveMqProducerEnabled(boolean value) { this.activeMqProducerEnabled = value; }
        public String getKafkaMode() { return kafkaMode; }
        public void setKafkaMode(String value) { this.kafkaMode = value; }
        public boolean isKafkaConnectionRequired() { return kafkaConnectionRequired; }
        public void setKafkaConnectionRequired(boolean value) { this.kafkaConnectionRequired = value; }
        public boolean isKafkaConsumerEnabled() { return kafkaConsumerEnabled; }
        public void setKafkaConsumerEnabled(boolean value) { this.kafkaConsumerEnabled = value; }
        public boolean isKafkaProducerEnabled() { return kafkaProducerEnabled; }
        public void setKafkaProducerEnabled(boolean value) { this.kafkaProducerEnabled = value; }
        public String getRedisMode() { return redisMode; }
        public void setRedisMode(String value) { this.redisMode = value; }
        public boolean isRedisKeyPrefixRequired() { return redisKeyPrefixRequired; }
        public void setRedisKeyPrefixRequired(boolean value) { this.redisKeyPrefixRequired = value; }
        public String getEmailMode() { return emailMode; }
        public void setEmailMode(String value) { this.emailMode = value; }
        public String getHttpExternalMode() { return httpExternalMode; }
        public void setHttpExternalMode(String value) { this.httpExternalMode = value; }
        public String getMqListenerEnabledConfigKey() { return mqListenerEnabledConfigKey; }
        public void setMqListenerEnabledConfigKey(String value) { this.mqListenerEnabledConfigKey = value; }
        public String getKafkaListenerEnabledConfigKey() { return kafkaListenerEnabledConfigKey; }
        public void setKafkaListenerEnabledConfigKey(String value) { this.kafkaListenerEnabledConfigKey = value; }
        public String getRedisKeyPrefixConfigKey() { return redisKeyPrefixConfigKey; }
        public void setRedisKeyPrefixConfigKey(String value) { this.redisKeyPrefixConfigKey = value; }
        public List<String> getRequiredMqSecretKeys() { return requiredMqSecretKeys; }
        public void setRequiredMqSecretKeys(List<String> value) {
            this.requiredMqSecretKeys = value == null ? new ArrayList<>() : value;
        }
        public List<String> getRequiredKafkaSecretKeys() { return requiredKafkaSecretKeys; }
        public void setRequiredKafkaSecretKeys(List<String> value) {
            this.requiredKafkaSecretKeys = value == null ? new ArrayList<>() : value;
        }
        public List<String> getRequiredRedisSecretKeys() { return requiredRedisSecretKeys; }
        public void setRequiredRedisSecretKeys(List<String> value) {
            this.requiredRedisSecretKeys = value == null ? new ArrayList<>() : value;
        }
        public int getBackendServicePort() { return backendServicePort; }
        public void setBackendServicePort(int value) { this.backendServicePort = value; }
        public String getBackendContextPath() { return backendContextPath; }
        public void setBackendContextPath(String value) { this.backendContextPath = value; }
        public String getAccessMode() { return accessMode; }
        public void setAccessMode(String value) { this.accessMode = value; }
        public String getReplayHostSuffix() { return replayHostSuffix; }
        public void setReplayHostSuffix(String value) { this.replayHostSuffix = value; }
        public String getCustomerUiBackendBaseUrlConfigKey() { return customerUiBackendBaseUrlConfigKey; }
        public void setCustomerUiBackendBaseUrlConfigKey(String value) { this.customerUiBackendBaseUrlConfigKey = value; }
        public String getBackendAllowedOriginsConfigKey() { return backendAllowedOriginsConfigKey; }
        public void setBackendAllowedOriginsConfigKey(String value) { this.backendAllowedOriginsConfigKey = value; }
        public List<String> getRequiredDbSecretKeys() { return requiredDbSecretKeys; }
        public void setRequiredDbSecretKeys(List<String> value) {
            this.requiredDbSecretKeys = value == null ? new ArrayList<>() : value;
        }
        public List<String> getRequiredCustomerUiConfigKeys() { return requiredCustomerUiConfigKeys; }
        public void setRequiredCustomerUiConfigKeys(List<String> value) {
            this.requiredCustomerUiConfigKeys = value == null ? new ArrayList<>() : value;
        }
        public List<String> getRequiredBackendConfigKeys() { return requiredBackendConfigKeys; }
        public void setRequiredBackendConfigKeys(List<String> value) {
            this.requiredBackendConfigKeys = value == null ? new ArrayList<>() : value;
        }
        public String getArgocdDestinationCluster() { return argocdDestinationCluster; }
        public void setArgocdDestinationCluster(String value) { this.argocdDestinationCluster = value; }
        public String getArgocdDestinationNamespacePrefix() { return argocdDestinationNamespacePrefix; }
        public void setArgocdDestinationNamespacePrefix(String value) { this.argocdDestinationNamespacePrefix = value; }
        public String getHelmChartPath() { return helmChartPath; }
        public void setHelmChartPath(String value) { this.helmChartPath = value; }
        public String getCustomerUiHelmChartPath() { return customerUiHelmChartPath; }
        public void setCustomerUiHelmChartPath(String value) { this.customerUiHelmChartPath = value; }
        public String getMockServerChartPath() { return mockServerChartPath; }
        public void setMockServerChartPath(String value) { this.mockServerChartPath = value; }
        public String getRepository() { return repository; }
        public void setRepository(String value) { this.repository = value; }
        public String getCloneUrl() { return cloneUrl; }
        public void setCloneUrl(String value) { this.cloneUrl = value; }
        public String getLocalSourcePath() { return localSourcePath; }
        public void setLocalSourcePath(String value) { this.localSourcePath = value; }
        public String getSourceWorkspaceRoot() { return sourceWorkspaceRoot; }
        public void setSourceWorkspaceRoot(String value) { this.sourceWorkspaceRoot = value; }
        public boolean isSourceCandidateExtractionEnabled() { return sourceCandidateExtractionEnabled; }
        public void setSourceCandidateExtractionEnabled(boolean value) { this.sourceCandidateExtractionEnabled = value; }
        public String getSourceCandidateSource() { return sourceCandidateSource; }
        public void setSourceCandidateSource(String value) { this.sourceCandidateSource = value; }
        public String getSourceCandidateExtractionBranch() { return sourceCandidateExtractionBranch; }
        public void setSourceCandidateExtractionBranch(String value) { this.sourceCandidateExtractionBranch = value; }
        public List<String> getAllowedSourceExtensions() { return allowedSourceExtensions; }
        public void setAllowedSourceExtensions(List<String> value) {
            this.allowedSourceExtensions = value == null ? new ArrayList<>() : value;
        }
        public int getMaxSourceCandidateFiles() { return maxSourceCandidateFiles; }
        public void setMaxSourceCandidateFiles(int value) { this.maxSourceCandidateFiles = value; }
        public int getMaxSnippetChars() { return maxSnippetChars; }
        public void setMaxSnippetChars(int value) { this.maxSnippetChars = value; }
        public SourceCandidateBitbucket getBitbucket() { return bitbucket; }
        public void setBitbucket(SourceCandidateBitbucket value) {
            this.bitbucket = value == null
                    ? new SourceCandidateBitbucket()
                    : value;
        }
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
        public Map<String, ExternalDependency> getExternalDependencies() {
            return externalDependencies;
        }
        public void setExternalDependencies(
                Map<String, ExternalDependency> value
        ) {
            this.externalDependencies = value == null
                    ? new LinkedHashMap<>()
                    : value;
        }
        public Map<String, DbSampleDomain> getDbSampleDomains() {
            return dbSampleDomains;
        }
        public void setDbSampleDomains(Map<String, DbSampleDomain> value) {
            this.dbSampleDomains = value == null
                    ? new LinkedHashMap<>()
                    : value;
        }
    }

    public static class SourceCandidateBitbucket {
        private String baseUrl = "";
        private String usernameEnv = "";
        private String tokenEnv = "";
        private String accessKeyEnv = "";
        private int requestTimeoutSeconds = 30;
        private Map<String, SourceCandidateRepository> repositories =
                new LinkedHashMap<>();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { this.baseUrl = value; }
        public String getUsernameEnv() { return usernameEnv; }
        public void setUsernameEnv(String value) { this.usernameEnv = value; }
        public String getTokenEnv() { return tokenEnv; }
        public void setTokenEnv(String value) { this.tokenEnv = value; }
        /**
         * Backward-compatible alias for older local config. Prefer tokenEnv.
         */
        public String getAccessKeyEnv() { return accessKeyEnv; }
        public void setAccessKeyEnv(String value) { this.accessKeyEnv = value; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int value) {
            this.requestTimeoutSeconds = value;
        }
        public Map<String, SourceCandidateRepository> getRepositories() {
            return repositories;
        }
        public void setRepositories(
                Map<String, SourceCandidateRepository> value
        ) {
            this.repositories = value == null
                    ? new LinkedHashMap<>()
                    : value;
        }
    }

    public static class SourceCandidateRepository {
        private String logicalName = "";
        private String projectKey = "";
        private String repositorySlug = "";
        private String branch = "";
        private String browseUrl = "";
        private String rawPathTemplate = "";
        private String language = "";
        private List<String> allowedExtensions = new ArrayList<>();

        public String getLogicalName() { return logicalName; }
        public void setLogicalName(String value) { this.logicalName = value; }
        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String value) { this.projectKey = value; }
        public String getRepositorySlug() { return repositorySlug; }
        public void setRepositorySlug(String value) { this.repositorySlug = value; }
        public String getBranch() { return branch; }
        public void setBranch(String value) { this.branch = value; }
        public String getBrowseUrl() { return browseUrl; }
        public void setBrowseUrl(String value) { this.browseUrl = value; }
        public String getRawPathTemplate() { return rawPathTemplate; }
        public void setRawPathTemplate(String value) { this.rawPathTemplate = value; }
        public String getLanguage() { return language; }
        public void setLanguage(String value) { this.language = value; }
        public List<String> getAllowedExtensions() { return allowedExtensions; }
        public void setAllowedExtensions(List<String> value) {
            this.allowedExtensions = value == null
                    ? new ArrayList<>()
                    : value;
        }
    }

    public static class ExternalDependency {
        private String dependencyName = "";
        private String dependencyType = "";
        private String originalBaseUrl = "";
        private String configKey = "";
        private String mockPath = "";
        private String mockType = "";
        private String responseSource = "";

        public String getDependencyName() { return dependencyName; }
        public void setDependencyName(String value) { this.dependencyName = value; }
        public String getDependencyType() { return dependencyType; }
        public void setDependencyType(String value) { this.dependencyType = value; }
        public String getOriginalBaseUrl() { return originalBaseUrl; }
        public void setOriginalBaseUrl(String value) { this.originalBaseUrl = value; }
        public String getConfigKey() { return configKey; }
        public void setConfigKey(String value) { this.configKey = value; }
        public String getMockPath() { return mockPath; }
        public void setMockPath(String value) { this.mockPath = value; }
        public String getMockType() { return mockType; }
        public void setMockType(String value) { this.mockType = value; }
        public String getResponseSource() { return responseSource; }
        public void setResponseSource(String value) { this.responseSource = value; }
    }

    public static class DbSampleDomain {
        private String domain = "";
        private String schema = "";
        private String tableName = "";
        private List<String> keyFields = new ArrayList<>();
        private String sampleQueryTemplate = "";
        private List<String> expectedResponseFields = new ArrayList<>();
        private List<String> expectedMockResponseFields = new ArrayList<>();
        private List<String> sanitizationRules = new ArrayList<>();

        public String getDomain() { return domain; }
        public void setDomain(String value) { this.domain = value; }
        public String getSchema() { return schema; }
        public void setSchema(String value) { this.schema = value; }
        public String getTableName() { return tableName; }
        public void setTableName(String value) { this.tableName = value; }
        public List<String> getKeyFields() { return keyFields; }
        public void setKeyFields(List<String> value) {
            this.keyFields = value == null ? new ArrayList<>() : value;
        }
        public String getSampleQueryTemplate() { return sampleQueryTemplate; }
        public void setSampleQueryTemplate(String value) {
            this.sampleQueryTemplate = value;
        }
        public List<String> getExpectedResponseFields() {
            return expectedResponseFields;
        }
        public void setExpectedResponseFields(List<String> value) {
            this.expectedResponseFields = value == null
                    ? new ArrayList<>()
                    : value;
        }
        public List<String> getExpectedMockResponseFields() {
            return expectedMockResponseFields.isEmpty()
                    ? expectedResponseFields
                    : expectedMockResponseFields;
        }
        public void setExpectedMockResponseFields(List<String> value) {
            this.expectedMockResponseFields = value == null
                    ? new ArrayList<>()
                    : value;
        }
        public List<String> getSanitizationRules() {
            return sanitizationRules;
        }
        public void setSanitizationRules(List<String> value) {
            this.sanitizationRules = value == null
                    ? new ArrayList<>()
                    : value;
        }
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
        private String branchPrefix = "replaylab/";
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

    public static class DbEvidence {
        private boolean enabled;
        private DbEvidenceDataSource backend = new DbEvidenceDataSource();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { this.enabled = value; }
        public DbEvidenceDataSource getBackend() { return backend; }
        public void setBackend(DbEvidenceDataSource value) { this.backend = value; }
    }

    public static class DbEvidenceDataSource {
        private String url = "";
        private String username = "";
        private String password = "";
        private String schema = "";

        public String getUrl() { return url; }
        public void setUrl(String value) { this.url = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { this.username = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { this.password = value; }
        public String getSchema() { return schema; }
        public void setSchema(String value) { this.schema = value; }
    }
}
