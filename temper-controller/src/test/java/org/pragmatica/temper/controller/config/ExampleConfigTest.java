package org.pragmatica.temper.controller.config;

import java.nio.file.Path;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The shipped example parses, so the documented runtime config cannot drift from the loader.
class ExampleConfigTest {
    @Test
    void exampleConfig_parses() {
        var config = ConfigLoader.load(Path.of("controller.toml.example"), Path.of("/home/builder"));

        assertThat(config.isSuccess()).as(config.toString()).isTrue();
        assertThat(config.unwrap().version()).as("load takes the build's version (D-6, pinned by ControllerVersionTest)")
                  .isEqualTo(ControllerVersion.bundled().unwrap());
        assertThat(config.unwrap().hosts().getFirst().knownHosts()).isEqualTo(Path.of("/home/builder/.temper/known_hosts"));
        assertThat(config.unwrap().fleet().kilnUrl().map(Object::toString)).isEqualTo(Option.some("http://127.0.0.1:7171"));
        assertThat(config.unwrap().fleet().stateDir()).isEqualTo(Path.of("/home/builder/.temper/state"));
        assertThat(config.unwrap().fleet().bbHosts()).as("the bb hosts are shown commented out").isEmpty();
    }
}
