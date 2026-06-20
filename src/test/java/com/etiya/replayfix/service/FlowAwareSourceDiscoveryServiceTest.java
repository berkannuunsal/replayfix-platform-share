package com.etiya.replayfix.service;

import com.etiya.replayfix.model.SourceFlowAnchor;
import com.etiya.replayfix.model.SuspectSignalCategory;
import com.etiya.replayfix.model.SuspectSignalStrength;
import com.etiya.replayfix.model.SuspectSourceSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowAwareSourceDiscoveryServiceTest {

    @TempDir
    Path temporaryDirectory;

    private final SourceFlowAnchorExtractionService anchorService =
            new SourceFlowAnchorExtractionService();
    private final FlowAwareSourceDiscoveryService discoveryService =
            new FlowAwareSourceDiscoveryService();

    @Test
    void genericTermsAloneDoNotCreateCandidates() throws Exception {
        writeJava(
                "src/main/java/com/example/UserRegionService.java",
                """
                        package com.example;
                        public class UserRegionService {
                            public void updateRegion(String user, String tax) {}
                        }
                        """
        );
        List<SourceFlowAnchor> anchors = anchorService.extract(List.of(
                signal("user"),
                signal("account"),
                signal("billing"),
                signal("region"),
                signal("update"),
                signal("tax")
        ));

        var result = discoveryService.discover(temporaryDirectory, anchors, 20);

        assertThat(anchors).allMatch(anchor -> !anchor.primary());
        assertThat(result.candidateFiles()).isEmpty();
    }

    @Test
    void detectsControllerEndpointAnnotationAndMethod() throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        public class BusinessFlowController {
                            @PostMapping("/businessFlow/initialize")
                            public InitResponse initialize(InitRequest request) {
                                return null;
                            }
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20
        );

        assertThat(result.candidateFlowChain())
                .anySatisfy(item -> {
                    assertThat(item.layer()).isEqualTo("CONTROLLER");
                    assertThat(item.methodName()).isEqualTo("initialize");
                    assertThat(item.relatedSignals())
                            .contains("/businessFlow/initialize");
                });
        assertThat(result.matchedEndpointAnchors())
                .contains("/businessFlow/initialize");
    }

    @Test
    void classLevelAndMethodLevelMappingMatchesBusinessFlowInitialize()
            throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        @RequestMapping("/businessFlow")
                        public class BusinessFlowController {
                            @PostMapping("/initialize")
                            public void initialize() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20
        );

        assertControllerMatch(result, "/businessFlow/initialize");
    }

    @Test
    void requestMappingPathAttributeAndPostMappingPathAttributeMatch()
            throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        @RequestMapping(path="/businessFlow")
                        public class BusinessFlowController {
                            @PostMapping(path="/initialize")
                            public void initialize() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20
        );

        assertControllerMatch(result, "/businessFlow/initialize");
    }

    @Test
    void requestMappingValueAttributeAndPostMappingValueAttributeMatch()
            throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        @RequestMapping(value="/businessFlow")
                        public class BusinessFlowController {
                            @PostMapping(value="/initialize")
                            public void initialize() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20
        );

        assertControllerMatch(result, "/businessFlow/initialize");
    }

    @Test
    void arrayPathMappingIsSupported() throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        @RequestMapping({"/legacy", "/businessFlow"})
                        public class BusinessFlowController {
                            @PostMapping({"/start", "/initialize"})
                            public void initialize() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20
        );

        assertControllerMatch(result, "/businessFlow/initialize");
    }

    @Test
    void pathNormalizationWorks() throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        @RequestMapping("/businessFlow/")
                        public class BusinessFlowController {
                            @PostMapping("//initialize/")
                            public void initialize() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("businessFlow/initialize/", "ENDPOINT")),
                20
        );

        assertControllerMatch(result, "businessFlow/initialize/");
    }

    @Test
    void simpleStringConstantMappingIsSupported() throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        @RequestMapping(BUSINESS_FLOW)
                        public class BusinessFlowController {
                            private static final String BUSINESS_FLOW = "/businessFlow";
                            private static final String INITIALIZE = "/initialize";
                            @PostMapping(INITIALIZE)
                            public void initialize() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20
        );

        assertControllerMatch(result, "/businessFlow/initialize");
    }

    @Test
    void genericInitializeAloneDoesNotCreateEndpointCandidate()
            throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        @RequestMapping("/businessFlow")
                        public class BusinessFlowController {
                            @PostMapping("/initialize")
                            public void initialize() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(new SourceFlowAnchor(
                        "/initialize",
                        "ENDPOINT",
                        "WEAK",
                        false,
                        "weak fragment"
                )),
                20
        );

        assertThat(result.candidateFlowChain()).isEmpty();
    }

    @Test
    void billingAccountAloneDoesNotCreateRepositoryCandidateWhenEndpointAnchorExists()
            throws Exception {
        writeJava(
                "src/main/java/com/example/BillingAccountRepository.java",
                """
                        package com.example;
                        public class BillingAccountRepository {
                            public void saveBillingAccount(String billingAccount) {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(
                        anchor("/businessFlow/initialize", "ENDPOINT"),
                        new SourceFlowAnchor(
                                "billingAccount",
                                "DOMAIN_OBJECT",
                                "WEAK",
                                false,
                                "weak fallback"
                        )
                ),
                20
        );

        assertThat(result.candidateFlowChain()).isEmpty();
        assertThat(result.unmatchedEndpointAnchors())
                .contains("/businessFlow/initialize");
    }

    @Test
    void controllerCandidatesOutrankRepositoryCandidates() throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        public class BusinessFlowController {
                            @PostMapping("/businessFlow/initialize")
                            public void initialize() {}
                        }
                        """
        );
        writeJava(
                "src/main/java/com/example/BillingAccountRepository.java",
                """
                        package com.example;
                        public class BillingAccountRepository {
                            public void billingAccount() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(
                        anchor("/businessFlow/initialize", "ENDPOINT"),
                        anchor("BillingAccount", "DOMAIN_OBJECT")
                ),
                20
        );

        assertThat(result.candidateFlowChain()).isNotEmpty();
        assertThat(result.candidateFlowChain().get(0).layer())
                .isEqualTo("CONTROLLER");
        assertThat(result.candidateFlowChain())
                .noneMatch(item -> "REPOSITORY".equals(item.layer()));
    }

    @Test
    void unmatchedEndpointAnchorsAndDiscoveredEndpointsArePopulatedWhenNoMatch()
            throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        public class BusinessFlowController {
                            @PostMapping("/businessFlow/other")
                            public void other() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20
        );

        assertThat(result.matchedEndpointAnchors()).isEmpty();
        assertThat(result.unmatchedEndpointAnchors())
                .contains("/businessFlow/initialize");
        assertThat(result.discoveredControllerEndpoints()).isNotEmpty();
    }

    @Test
    void expandsControllerToServiceThroughInjectedDependency()
            throws Exception {
        writeJava(
                "src/main/java/com/example/BusinessFlowController.java",
                """
                        package com.example;
                        public class BusinessFlowController {
                            private final BusinessFlowService businessFlowService;
                            @PostMapping("/businessFlow/initialize")
                            public InitResponse initialize(InitRequest request) {
                                return businessFlowService.initialize(request);
                            }
                        }
                        """
        );
        writeJava(
                "src/main/java/com/example/BusinessFlowService.java",
                """
                        package com.example;
                        public class BusinessFlowService {
                            public InitResponse initialize(InitRequest request) {
                                return null;
                            }
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20
        );

        assertThat(result.candidateFlowChain())
                .anySatisfy(item -> {
                    assertThat(item.layer()).isEqualTo("SERVICE");
                    assertThat(item.className()).isEqualTo("BusinessFlowService");
                    assertThat(item.methodName()).isEqualTo("initialize");
                });
    }

    @Test
    void resolvesFieldInjectedServiceFromControllerMethodCall()
            throws Exception {
        writeUserRegionFlow("private UserService userService;", "");

        var result = discoverUserRegionFlow();

        assertThat(result.serviceResolutionAttempts()).isEqualTo(1);
        assertThat(result.resolvedServiceTypes())
                .contains("UserService", "UserServiceImpl");
        assertThat(result.candidateFlowChain())
                .extracting("layer")
                .containsSubsequence(
                        "CONTROLLER",
                        "SERVICE_INTERFACE",
                        "SERVICE_IMPL",
                        "DTO"
                );
    }

    @Test
    void resolvesConstructorInjectedServiceFromControllerMethodCall()
            throws Exception {
        writeUserRegionFlow(
                "",
                """
                            public UserController(UserService userService) {
                                this.userService = userService;
                            }
                        """
        );

        var result = discoverUserRegionFlow();

        assertThat(result.resolvedServiceTypes())
                .contains("UserService", "UserServiceImpl");
        assertThat(result.unresolvedServiceCalls()).isEmpty();
    }

    @Test
    void resolvesInterfaceImplementationAndMatchingDtoMethod()
            throws Exception {
        writeUserRegionFlow("private final UserService userService;", "");

        var result = discoverUserRegionFlow();

        assertThat(result.resolvedImplementationFiles())
                .contains("src/main/java/com/example/UserServiceImpl.java");
        assertThat(result.candidateMethods())
                .anySatisfy(method -> {
                    assertThat(method.className()).isEqualTo("UserServiceImpl");
                    assertThat(method.methodName()).isEqualTo("updateUser");
                    assertThat(method.snippet())
                            .contains("UpdateAplUserPrefPrvncRequest request");
                });
    }

    @Test
    void dtoRemainsInCandidateChainAfterServiceResolution()
            throws Exception {
        writeUserRegionFlow("private final UserService userService;", "");

        var result = discoverUserRegionFlow();

        assertThat(result.candidateFlowChain())
                .anySatisfy(item -> {
                    assertThat(item.layer()).isEqualTo("DTO");
                    assertThat(item.className())
                            .isEqualTo("UpdateAplUserPrefPrvncRequest");
                });
    }

    @Test
    void repositoryCandidatesRequireServiceChainReference()
            throws Exception {
        writeUserRegionFlow("private final UserService userService;", "");
        writeJava(
                "src/main/java/com/example/BillingAccountRepository.java",
                """
                        package com.example;
                        public class BillingAccountRepository {
                            public void weakFallbackOnly() {}
                        }
                        """
        );

        var result = discoverUserRegionFlow();

        assertThat(result.candidateFlowChain())
                .anySatisfy(item -> {
                    assertThat(item.layer()).isEqualTo("REPOSITORY");
                    assertThat(item.className()).isEqualTo("UserRepository");
                });
        assertThat(result.candidateFlowChain())
                .noneMatch(item -> "BillingAccountRepository"
                        .equals(item.className()));
    }

    @Test
    void detectsDtoAndEntityFromMethodSignature() throws Exception {
        writeJava(
                "src/main/java/com/example/RegionController.java",
                """
                        package com.example;
                        public class RegionController {
                            @PostMapping("/user/region/update")
                            public RegionResponse updateRegion(RegionRequest request) {
                                return null;
                            }
                        }
                        """
        );
        writeJava(
                "src/main/java/com/example/RegionRequest.java",
                "package com.example; public class RegionRequest {}\n"
        );
        writeJava(
                "src/main/java/com/example/RegionResponse.java",
                "package com.example; public class RegionResponse {}\n"
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/user/region/update", "ENDPOINT")),
                20
        );

        assertThat(result.candidateFlowChain())
                .extracting("className")
                .contains("RegionRequest", "RegionResponse");
    }

    private FlowAwareSourceDiscoveryService.DiscoveryResult discoverUserRegionFlow() {
        return discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/user/region/update", "ENDPOINT")),
                20
        );
    }

    private void writeUserRegionFlow(
            String fieldDeclaration,
            String constructor
    ) throws Exception {
        writeJava(
                "src/main/java/com/example/UserController.java",
                """
                        package com.example;
                        public class UserController {
                        %s
                        %s
                            @PutMapping("/user/region/update")
                            public void updateUserParty(UpdateAplUserPrefPrvncRequest request) {
                                userService.updateUser(request);
                            }
                        }
                        """.formatted(fieldDeclaration, constructor)
        );
        writeJava(
                "src/main/java/com/example/UserService.java",
                """
                        package com.example;
                        public interface UserService {
                            void updateUser(UpdateAplUserPrefPrvncRequest request);
                        }
                        """
        );
        writeJava(
                "src/main/java/com/example/UserServiceImpl.java",
                """
                        package com.example;
                        public class UserServiceImpl implements UserService {
                            private final UserMapper userMapper;
                            private final UserRepository userRepository;
                            public void updateUser(UpdateAplUserPrefPrvncRequest request) {
                                userMapper.map(request);
                                userRepository.save(request);
                            }
                        }
                        """
        );
        writeJava(
                "src/main/java/com/example/UpdateAplUserPrefPrvncRequest.java",
                "package com.example; public class UpdateAplUserPrefPrvncRequest {}\n"
        );
        writeJava(
                "src/main/java/com/example/UserMapper.java",
                "package com.example; public class UserMapper { public void map(Object value) {} }\n"
        );
        writeJava(
                "src/main/java/com/example/UserRepository.java",
                "package com.example; public class UserRepository { public void save(Object value) {} }\n"
        );
    }

    @Test
    void maxScannedFilesIsRespected() throws Exception {
        writeJava(
                "src/main/java/com/example/AController.java",
                """
                        package com.example;
                        public class AController {
                            @PostMapping("/businessFlow/initialize")
                            public void initialize() {}
                        }
                        """
        );
        writeJava(
                "src/main/java/com/example/BController.java",
                """
                        package com.example;
                        public class BController {
                            @PostMapping("/businessFlow/initialize")
                            public void initialize() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20,
                1,
                256,
                false
        );

        assertThat(result.javaFiles()).hasSize(1);
        assertThat(result.candidateFiles()).hasSize(1);
    }

    @Test
    void srcTestIsSkippedByDefault() throws Exception {
        writeJava(
                "src/test/java/com/example/TestController.java",
                """
                        package com.example;
                        public class TestController {
                            @PostMapping("/businessFlow/initialize")
                            public void initialize() {}
                        }
                        """
        );

        var result = discoveryService.discover(
                temporaryDirectory,
                List.of(anchor("/businessFlow/initialize", "ENDPOINT")),
                20
        );

        assertThat(result.javaFiles()).isEmpty();
        assertThat(result.candidateFiles()).isEmpty();
    }

    private SourceFlowAnchor anchor(String value, String type) {
        return new SourceFlowAnchor(value, type, "test");
    }

    private void assertControllerMatch(
            FlowAwareSourceDiscoveryService.DiscoveryResult result,
            String anchor
    ) {
        assertThat(result.candidateFlowChain())
                .anySatisfy(item -> assertThat(item.layer())
                        .isEqualTo("CONTROLLER"));
        assertThat(result.matchedEndpointAnchors()).contains(anchor);
        assertThat(result.unmatchedEndpointAnchors()).doesNotContain(anchor);
    }

    private SuspectSourceSignal signal(String value) {
        return new SuspectSourceSignal(
                value,
                SuspectSignalCategory.BUSINESS_TERM,
                SuspectSignalStrength.STRONG,
                List.of("TEST"),
                "test"
        );
    }

    private void writeJava(String relativePath, String content) throws Exception {
        Path path = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
