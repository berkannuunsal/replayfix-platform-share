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

        assertThat(anchors).isEmpty();
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
