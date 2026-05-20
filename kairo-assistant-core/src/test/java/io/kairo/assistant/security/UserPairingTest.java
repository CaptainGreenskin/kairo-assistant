package io.kairo.assistant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserPairingTest {

    @TempDir
    Path tempDir;

    @Test
    void disabledAlwaysAuthorizes() {
        UserPairing pairing = new UserPairing(tempDir, false);
        assertThat(pairing.isAuthorized("dingtalk", "unknown")).isTrue();
    }

    @Test
    void enabledRejectsUnpairedUser() {
        UserPairing pairing = new UserPairing(tempDir, true);
        assertThat(pairing.isAuthorized("dingtalk", "user1")).isFalse();
    }

    @Test
    void pairingFlowWorks() {
        UserPairing pairing = new UserPairing(tempDir, true);
        String code = pairing.generatePairingCode("dingtalk", "user1");

        assertThat(code).hasSize(6);
        assertThat(pairing.isAuthorized("dingtalk", "user1")).isFalse();

        boolean result = pairing.completePairing(code);
        assertThat(result).isTrue();
        assertThat(pairing.isAuthorized("dingtalk", "user1")).isTrue();
    }

    @Test
    void invalidCodeFails() {
        UserPairing pairing = new UserPairing(tempDir, true);
        assertThat(pairing.completePairing("999999")).isFalse();
    }

    @Test
    void unpairRemovesUser() {
        UserPairing pairing = new UserPairing(tempDir, true);
        String code = pairing.generatePairingCode("feishu", "u2");
        pairing.completePairing(code);

        assertThat(pairing.unpair("feishu", "u2")).isTrue();
        assertThat(pairing.isAuthorized("feishu", "u2")).isFalse();
    }

    @Test
    void persistsAcrossInstances() {
        UserPairing p1 = new UserPairing(tempDir, true);
        String code = p1.generatePairingCode("slack", "u3");
        p1.completePairing(code);

        UserPairing p2 = new UserPairing(tempDir, true);
        assertThat(p2.isAuthorized("slack", "u3")).isTrue();
    }

    @Test
    void codeCanOnlyBeUsedOnce() {
        UserPairing pairing = new UserPairing(tempDir, true);
        String code = pairing.generatePairingCode("dingtalk", "u4");

        assertThat(pairing.completePairing(code)).isTrue();
        assertThat(pairing.completePairing(code)).isFalse();
    }

    @Test
    void allUsersReturnsSnapshot() {
        UserPairing pairing = new UserPairing(tempDir, true);
        String c1 = pairing.generatePairingCode("a", "1");
        String c2 = pairing.generatePairingCode("b", "2");
        pairing.completePairing(c1);
        pairing.completePairing(c2);

        assertThat(pairing.allUsers()).hasSize(2);
    }
}
