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
