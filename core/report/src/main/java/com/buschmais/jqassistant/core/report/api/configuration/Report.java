package com.buschmais.jqassistant.core.report.api.configuration;

import java.util.*;

import com.buschmais.jqassistant.core.shared.annotation.Description;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "jqassistant.analyze.report")
public interface Report {

    String BUILD = "build";

    @Description("The customizable build information to be displayed in reports.")
    Build build();

    String DIRECTORY = "directory";

    @Description("The directory for generated reports.")
    Optional<String> directory();

    String PROPERTIES = "properties";

    @Description("The properties to configure report plugins. The supported properties are plugin specific.")
    Map<String, String> properties();

    String WARN_ON_SEVERITY = "warn-on-severity";

    @Description("Determines the severity level to report warnings for rules with equal or higher severities.")
    @WithDefault("MINOR")
    String warnOnSeverity();

    String FAIL_ON_SEVERITY = "fail-on-severity";

    @Description("Determines the severity level to report failures for rules with equal or higher severities.")
    @WithDefault("MAJOR")
    String failOnSeverity();

    String CONTINUE_ON_FAILURE = "continue-on-failure";

    @Description("Determines if jQAssistant shall continue the build if failures have been detected.")
    @WithDefault("false")
    boolean continueOnFailure();

    String CREATE_ARCHIVE = "create-archive";

    @Description("Create an archive containing all generated reports.")
    @WithDefault("false")
    boolean createArchive();
}
