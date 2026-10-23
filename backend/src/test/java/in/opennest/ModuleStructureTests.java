package in.opennest;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleStructureTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(OpenNestApplication.class);

    @Test
    void discoversAllFiveModules() {
        assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder("common", "identity", "listing", "interaction", "sandbox");
    }

    @Test
    void respectsModuleBoundaries() {
        MODULES.verify();
    }
}
