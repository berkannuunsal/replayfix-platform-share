package com.etiya.replaylab.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaTopLevelClassRenamerTest {

    private JavaTopLevelClassRenamer renamer;

    @BeforeEach
    void setUp() {
        renamer = new JavaTopLevelClassRenamer();
    }

    @Test
    void shouldRenameClassDeclaration() {
        String source = """
                package com.example;
                
                public class OriginalClass {
                    public void method() {}
                }
                """;

        JavaTopLevelClassRenamer.RenameResult result =
                renamer.rename(source, "OriginalClass", "RenamedClass");

        assertTrue(result.source().contains("class RenamedClass"));
        assertFalse(result.source().contains("class OriginalClass"));
        assertEquals(1, result.classDeclarationMatches());
    }

    @Test
    void shouldRenamePublicClassDeclaration() {
        String source = """
                package com.example;
                
                public class MyTest {
                }
                """;

        JavaTopLevelClassRenamer.RenameResult result =
                renamer.rename(source, "MyTest", "MyTestV2");

        assertTrue(result.source().contains("public class MyTestV2"));
        assertEquals(1, result.classDeclarationMatches());
    }

    @Test
    void shouldRenameConstructor() {
        String source = """
                package com.example;
                
                public class MyTest {
                    public MyTest(String value) {
                    }
                }
                """;

        JavaTopLevelClassRenamer.RenameResult result =
                renamer.rename(source, "MyTest", "MyTestV2");

        assertTrue(result.source().contains("public MyTestV2(String value)"));
        assertEquals(1, result.classDeclarationMatches());
        assertEquals(1, result.constructorMatches());
    }

    @Test
    void shouldNotRenameStringLiteral() {
        String source = """
                package com.example;
                
                public class MyTest {
                    String name = "MyTest should not change";
                }
                """;

        JavaTopLevelClassRenamer.RenameResult result =
                renamer.rename(source, "MyTest", "MyTestV2");

        assertTrue(result.source().contains("\"MyTest should not change\""));
        assertTrue(result.source().contains("class MyTestV2"));
    }

    @Test
    void shouldNotRenameComment() {
        String source = """
                package com.example;
                
                // MyTest comment should remain
                public class MyTest {
                }
                """;

        JavaTopLevelClassRenamer.RenameResult result =
                renamer.rename(source, "MyTest", "MyTestV2");

        assertTrue(result.source().contains("// MyTest comment"));
        assertTrue(result.source().contains("class MyTestV2"));
    }

    @Test
    void shouldThrowWhenMultipleDeclarationsFound() {
        String source = """
                package com.example;
                
                public class MyTest {
                }
                
                class MyTest {
                }
                """;

        assertThrows(
                IllegalStateException.class,
                () -> renamer.rename(source, "MyTest", "MyTestV2")
        );
    }

    @Test
    void shouldThrowWhenInvalidOriginalIdentifier() {
        assertThrows(
                IllegalArgumentException.class,
                () -> renamer.rename("source", "Invalid-Class", "Valid")
        );
    }

    @Test
    void shouldThrowWhenInvalidTargetIdentifier() {
        assertThrows(
                IllegalArgumentException.class,
                () -> renamer.rename("source", "ValidClass", "Invalid Class")
        );
    }
}
