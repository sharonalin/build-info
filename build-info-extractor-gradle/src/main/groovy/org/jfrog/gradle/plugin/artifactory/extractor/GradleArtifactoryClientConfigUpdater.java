/*
 * Copyright (C) 2011 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.gradle.plugin.artifactory.extractor;

import com.google.common.base.Predicate;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildInfoFields;
import org.jfrog.build.client.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;

import javax.annotation.Nullable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import static org.jfrog.build.api.BuildInfoProperties.BUILD_INFO_PROP_PREFIX;
import static org.jfrog.build.extractor.BuildInfoExtractorUtils.BUILD_INFO_PROP_PREDICATE;

/**
 * Populator util for filling up an ArtifactoryClientConfiguration based on a gradle project + environment properties
 *
 * @author Tomer Cohen
 */
public class GradleArtifactoryClientConfigUpdater {

    /**
     * Returns a  configuration handler object out of a Gradle project. This method will aggregate the properties in our
     * defined hierarchy.<br/> <ol><li>First search for the property as a system property, if found return it.</li>
     * <li>Second search for the property in the Gradle {@link org.gradle.StartParameter#getProjectProperties} container
     * and if found there, then return it.</li> <li>Third search for the property in {@link
     * org.gradle.api.Project#property(String)}</li> <li>if not found, search upwards in the project hierarchy until
     * reach the root project.</li> <li> if not found at all in this hierarchy return null</li></ol>
     *
     * @param project the gradle project with properties for build info client configuration (Usually in start parameter
     *                from CI Server)
     */
    public static void update(ArtifactoryClientConfiguration config, Project project) {
        Properties props = new Properties();
        // First aggregate properties from parent to child
        fillProperties(project, props);
        // Then start parameters
        StartParameter startParameter = project.getGradle().getStartParameter();
        props.putAll(startParameter.getProjectProperties());
        // Then System properties
        Properties mergedProps = BuildInfoExtractorUtils.mergePropertiesWithSystemAndPropertyFile(props);
        // Then special buildInfo properties
        Properties buildInfoProperties =
                BuildInfoExtractorUtils.filterDynamicProperties(mergedProps, BUILD_INFO_PROP_PREDICATE);
        buildInfoProperties =
                BuildInfoExtractorUtils.stripPrefixFromProperties(buildInfoProperties, BUILD_INFO_PROP_PREFIX);
        mergedProps.putAll(buildInfoProperties);

        config.fillFromProperties(mergedProps);

        //After props are set, apply missing project props (not set by CI-plugin generated props)
        setMissingBuildAttributes(config, project);
    }

    private static void setMissingBuildAttributes(ArtifactoryClientConfiguration config, Project project) {
        //Build name
        String buildName = config.info.getBuildName();
        if (StringUtils.isBlank(buildName)) {
            buildName = project.getName();
            config.info.setBuildName(buildName);
        }
        config.publisher.addMatrixParam(BuildInfoFields.BUILD_NAME, buildName);

        //Build number
        String buildNumber = config.info.getBuildNumber();
        if (StringUtils.isBlank(buildNumber)) {
            buildNumber = new Date().getTime() + "";
            config.info.setBuildNumber(buildNumber);
        }
        config.publisher.addMatrixParam(BuildInfoFields.BUILD_NUMBER, buildNumber);

        //Build start (was set by the plugin - no need to make up a fallback val)
        String buildStartedIso = config.info.getBuildStarted();
        Date buildStartDate;
        try {
            buildStartDate = new SimpleDateFormat(Build.STARTED_FORMAT).parse(buildStartedIso);
        } catch (ParseException e) {
            throw new RuntimeException("Build start date format error: " + buildStartedIso, e);
        }
        config.publisher.addMatrixParam(BuildInfoFields.BUILD_TIMESTAMP, String.valueOf(buildStartDate.getTime()));

        //Build agent
        String buildAgentName = config.info.getBuildAgentName();
        String buildAgentVersion = config.info.getBuildAgentVersion();
        if (StringUtils.isBlank(buildAgentName) && StringUtils.isBlank(buildAgentVersion)) {
            config.info.setBuildAgentName("Gradle");
            config.info.setBuildAgentVersion(project.getGradle().getGradleVersion());
        }
    }

    private static void fillProperties(Project project, Properties props) {
        Project parent = project.getParent();
        if (parent != null) {
            // Parent first than me
            fillProperties(parent, props);
        }
        Map<String, ?> projectProperties = project.getProperties();
        projectProperties = Maps.filterValues(projectProperties, new Predicate<Object>() {
            public boolean apply(@Nullable Object input) {
                return input != null && input instanceof String;
            }
        });
        props.putAll(projectProperties);
    }
}
