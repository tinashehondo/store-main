package com.example.store.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults a deployment gets when it sets nothing: no credentials and no demo data. Read from the YAML files
 * themselves, so that the result does not depend on the environment of the machine running the tests.
 */
class ConfigurationDefaultsTest {

    @Test
    void defaultConfigurationHasNoDatabaseCredentialDefaults() throws IOException {
        PropertySource<?> defaults = load("application.yaml");

        assertThat(defaults.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
        assertThat(defaults.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
    }

    @Test
    void defaultConfigurationRunsTheProdContextSoTheDemoDataIsNeverLoaded() throws IOException {
        assertThat(load("application.yaml").getProperty("spring.liquibase.contexts"))
                .isEqualTo("${LIQUIBASE_CONTEXTS:prod}");
    }

    @Test
    void devProfileKeepsTheLocalCredentialsAndTheDemoData() throws IOException {
        PropertySource<?> dev = load("application-dev.yaml");

        assertThat(dev.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME:admin}");
        assertThat(dev.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD:admin}");
        assertThat(dev.getProperty("spring.liquibase.contexts")).isEqualTo("${LIQUIBASE_CONTEXTS:demo}");
    }

    private static PropertySource<?> load(String file) throws IOException {
        return new YamlPropertySourceLoader()
                .load(file, new ClassPathResource(file))
                .get(0);
    }
}
