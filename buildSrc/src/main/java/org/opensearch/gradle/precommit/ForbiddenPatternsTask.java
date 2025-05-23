/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.gradle.precommit;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.util.PatternFilterable;
import org.gradle.api.tasks.util.PatternSet;

import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Checks for patterns in source files for the project which are forbidden.
 */
public class ForbiddenPatternsTask extends DefaultTask {

    /*
     * A pattern set of which files should be checked.
     */
    private final PatternFilterable filesFilter = new PatternSet()
        // we always include all source files, and exclude what should not be checked
        .include("**")
        // exclude known binary extensions
        .exclude("**/*.gz")
        .exclude("**/*.ico")
        .exclude("**/*.jar")
        .exclude("**/*.zip")
        .exclude("**/*.p12")
        .exclude("**/*.jks")
        .exclude("**/*.crt")
        .exclude("**/*.der")
        .exclude("**/*.pem")
        .exclude("**/*.key")
        .exclude("**/*.bcfks")
        .exclude("**/*.keystore")
        .exclude("**/*.png");

    /*
     * The rules: a map from the rule name, to a rule regex pattern.
     */
    private final Map<String, String> patterns = new HashMap<>();
    private final Project project;

    @Inject
    public ForbiddenPatternsTask(Project project) {
        setDescription("Checks source files for invalid patterns like nocommits or tabs");
        getInputs().property("excludes", filesFilter.getExcludes());
        getInputs().property("rules", patterns);

        // add mandatory rules
        patterns.put("nocommit", "nocommit|NOCOMMIT");
        patterns.put("nocommit should be all lowercase or all uppercase", "((?i)nocommit)(?<!(nocommit|NOCOMMIT))");
        patterns.put("tab", "\t");

        this.project = project;
    }

    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getFiles() {
        return project.getExtensions()
            .getByType(JavaPluginExtension.class)
            .getSourceSets()
            .stream()
            .map(sourceSet -> sourceSet.getAllSource().matching(filesFilter))
            .reduce(FileTree::plus)
            .orElse(project.files().getAsFileTree());
    }

    @TaskAction
    public void checkInvalidPatterns() throws IOException {
        Pattern allPatterns = Pattern.compile("(" + String.join(")|(", getPatterns().values()) + ")");
        List<String> failures = new ArrayList<>();
        for (File f : getFiles()) {
            List<String> lines;
            try (Stream<String> stream = Files.lines(f.toPath(), StandardCharsets.UTF_8)) {
                lines = stream.collect(Collectors.toList());
            } catch (UncheckedIOException e) {
                throw new IllegalArgumentException("Failed to read " + f + " as UTF_8", e);
            }
            List<Integer> invalidLines = IntStream.range(0, lines.size())
                .filter(i -> allPatterns.matcher(lines.get(i)).find())
                .boxed()
                .collect(Collectors.toList());

            String path = project.getRootProject().getProjectDir().toURI().relativize(f.toURI()).toString();
            failures.addAll(
                invalidLines.stream()
                    .map(l -> new AbstractMap.SimpleEntry<>(l + 1, lines.get(l)))
                    .flatMap(
                        kv -> patterns.entrySet()
                            .stream()
                            .filter(p -> Pattern.compile(p.getValue()).matcher(kv.getValue()).find())
                            .map(p -> "- " + p.getKey() + " on line " + kv.getKey() + " of " + path)
                    )
                    .collect(Collectors.toList())
            );
        }
        if (failures.isEmpty() == false) {
            throw new GradleException("Found invalid patterns:\n" + String.join("\n", failures));
        }

        File outputMarker = getOutputMarker();
        outputMarker.getParentFile().mkdirs();
        Files.write(outputMarker.toPath(), "done".getBytes(StandardCharsets.UTF_8));
    }

    @OutputFile
    public File getOutputMarker() {
        return new File(project.getBuildDir(), "markers/" + getName());
    }

    @Input
    public Map<String, String> getPatterns() {
        return Collections.unmodifiableMap(patterns);
    }

    public void exclude(String... excludes) {
        filesFilter.exclude(excludes);
    }

    public void rule(Map<String, String> props) {
        String name = props.remove("name");
        if (name == null) {
            throw new InvalidUserDataException("Missing [name] for invalid pattern rule");
        }
        String pattern = props.remove("pattern");
        if (pattern == null) {
            throw new InvalidUserDataException("Missing [pattern] for invalid pattern rule");
        }
        if (props.isEmpty() == false) {
            throw new InvalidUserDataException("Unknown arguments for ForbiddenPatterns rule mapping: " + props.keySet().toString());
        }
        // TODO: fail if pattern contains a newline, it won't work (currently)
        patterns.put(name, pattern);
    }
}
