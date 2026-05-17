package io.kairo.assistant.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class I18nTest {

    @AfterEach
    void reset() {
        I18n.setLocale("en");
        I18n.clearCache();
    }

    @Test
    void englishLocale() {
        I18n.setLocale("en");
        assertThat(I18n.t("agent.greeting")).contains("Kairo Assistant");
    }

    @Test
    void chineseLocale() {
        I18n.setLocale("zh");
        assertThat(I18n.t("agent.greeting")).contains("Kairo 助手");
    }

    @Test
    void fallbackToEnglish() {
        I18n.setLocale("fr");
        assertThat(I18n.t("agent.greeting")).contains("Kairo Assistant");
    }

    @Test
    void missingKeyReturnsKey() {
        assertThat(I18n.t("nonexistent.key")).isEqualTo("nonexistent.key");
    }

    @Test
    void formatArgs() {
        I18n.setLocale("en");
        String msg = I18n.tf("error.unknown_action", "deploy");
        assertThat(msg).isEqualTo("Unknown action: deploy");
    }

    @Test
    void toolDescriptions() {
        I18n.setLocale("en");
        assertThat(I18n.t("tool.calculator.desc")).contains("math");

        I18n.setLocale("zh");
        assertThat(I18n.t("tool.calculator.desc")).contains("数学");
    }

    @Test
    void localeGetter() {
        I18n.setLocale("zh");
        assertThat(I18n.locale()).isEqualTo("zh");
    }
}
