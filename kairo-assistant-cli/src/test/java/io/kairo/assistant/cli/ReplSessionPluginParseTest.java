package io.kairo.assistant.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kairo.api.plugin.PluginSource;
import org.junit.jupiter.api.Test;

class ReplSessionPluginParseTest {

    @Test
    void githubSpecBecomesGitHubSource() {
        var src = ReplSession.parsePluginSourceSpec("github:owner/repo");
        assertThat(src).isInstanceOf(PluginSource.GitHub.class);
        var gh = (PluginSource.GitHub) src;
        assertThat(gh.ownerRepo()).isEqualTo("owner/repo");
        assertThat(gh.ref()).isNull();
    }

    @Test
    void githubSpecParsesRef() {
        var gh = (PluginSource.GitHub) ReplSession.parsePluginSourceSpec("github:owner/repo@v1.2.3");
        assertThat(gh.ref()).isEqualTo("v1.2.3");
    }

    @Test
    void npmSpecParsesPackageAndVersion() {
        var src = ReplSession.parsePluginSourceSpec("npm:my-pkg@1.0.0");
        assertThat(src).isInstanceOf(PluginSource.Npm.class);
        var npm = (PluginSource.Npm) src;
        assertThat(npm.packageName()).isEqualTo("my-pkg");
        assertThat(npm.version()).isEqualTo("1.0.0");
    }

    @Test
    void npmRequiresVersion() {
        assertThatThrownBy(() -> ReplSession.parsePluginSourceSpec("npm:no-version"))
                .hasMessageContaining("@version");
    }

    @Test
    void gitUrlSpecParsesUrlAndRef() {
        var src = ReplSession.parsePluginSourceSpec("git+https://example.com/repo.git@trunk");
        assertThat(src).isInstanceOf(PluginSource.GitUrl.class);
        var g = (PluginSource.GitUrl) src;
        assertThat(g.url()).isEqualTo("https://example.com/repo.git");
        assertThat(g.ref()).isEqualTo("trunk");
    }

    @Test
    void gitSubdirSpecParsesUrlRefAndSubdir() {
        var src = ReplSession.parsePluginSourceSpec(
                "git-subdir+https://example.com/mono.git@main:plugins/foo");
        assertThat(src).isInstanceOf(PluginSource.GitSubdir.class);
        var s = (PluginSource.GitSubdir) src;
        assertThat(s.url()).isEqualTo("https://example.com/mono.git");
        assertThat(s.ref()).isEqualTo("main");
        assertThat(s.subdir()).isEqualTo("plugins/foo");
    }

    @Test
    void gitSubdirRequiresColonSubdir() {
        assertThatThrownBy(() -> ReplSession.parsePluginSourceSpec("git-subdir+https://x.git"))
                .hasMessageContaining("subdir");
    }

    @Test
    void plainPathBecomesLocalPath() {
        var src = ReplSession.parsePluginSourceSpec("/abs/plugin");
        assertThat(src).isInstanceOf(PluginSource.LocalPath.class);
        assertThat(((PluginSource.LocalPath) src).path().toString()).isEqualTo("/abs/plugin");
    }

    @Test
    void relativePathIsResolvedToAbsolute() {
        var src = ReplSession.parsePluginSourceSpec("./relative-plugin");
        assertThat(src).isInstanceOf(PluginSource.LocalPath.class);
        assertThat(((PluginSource.LocalPath) src).path().isAbsolute()).isTrue();
    }
}
