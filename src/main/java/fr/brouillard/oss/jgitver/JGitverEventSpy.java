/*
 * Copyright (C) 2016 Matthieu Brouillard [http://oss.brouillard.fr/jgitver-maven-plugin] (matthieu@brouillard.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.brouillard.oss.jgitver;

import fr.brouillard.oss.jgitver.lambda.ThrowingFunction;
import java.io.File;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionEvent.Type;
import org.apache.maven.execution.MavenSession;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;

/**
 * An event spy for Maven that ensures the project version is replaced with the JGitver calculated
 * version before Maven loads the model for processing. This class extends Maven's {@link
 * AbstractEventSpy} and is designed to work with JGitver. This class is annotated with {@link
 * Named} and {@link Singleton} to indicate that it should be used as a singleton component in a
 * dependency injection context, and that it can be referred to by the name "jgitver".
 */
@Named("jgitver")
@Singleton
public class JGitverEventSpy extends AbstractEventSpy {

  final JGitverPomIO pomIO;

  final JGitverSessionHolder sessionHolder;

  final JGitverModelProcessor processor;

  /**
   * Constructs a new {@link JGitverEventSpy} with the provided session holder, model processor, and
   * POM I/O.
   *
   * @param sessionHolder the session holder to use
   * @param processor the model processor to use
   * @param pomIO the POM I/O to use
   */
  @Inject
  public JGitverEventSpy(
      JGitverSessionHolder sessionHolder, JGitverModelProcessor processor, JGitverPomIO pomIO) {
    this.sessionHolder = sessionHolder;
    this.processor = processor;
    this.pomIO = pomIO;
  }

  /**
   * Handles Maven execution events. When a ProjectDiscoveryStarted event is received, this method
   * rewrites the execution root POM file with the version calculated by JGitver.
   *
   * @param event the Maven execution event
   * @throws Exception if an error occurs while handling the event
   */
  @Override
  public void onEvent(Object event) throws Exception {
    try {
      if (!(event instanceof ExecutionEvent)) {
        return;
      }

      final ExecutionEvent ee = (ExecutionEvent) event;
      if (ee.getType() != Type.ProjectDiscoveryStarted) {
        return;
      }

      MavenSession mavenSession = ee.getSession();
      sessionHolder
          .session(mavenSession)
          .ifPresent(
              session ->
                  parsePOM(mavenSession.getRequest().getPom()).stream()
                      .filter(Project::isExecutionRoot)
                      .findFirst()
                      .ifPresent(
                          project -> this.onProject(mavenSession, project, session.getVersion())));

    } finally {
      super.onEvent(event);
    }
  }

  /**
   * Parses the provided POM file into a list of Project instances. This method uses the {@link
   * JGitverPomIO} instance to read the POM file. The logic for this method is based onto the
   * maven-pom-manipulation plugin IO module.
   *
   * @param pomFile the POM file to parse
   * @return a list of Project instances representing the projects defined in the POM file
   * @throws ManipulationException if an error occurs while parsing the POM file
   */
  List<Project> parsePOM(File pomFile) {
    try {
      return pomIO.parseProject(pomFile);
    } catch (ManipulationException error) {
      return ThrowingFunction.sneakyThrow(error);
    }
  }

  /**
   * Updates the version of the provided project and writes the project's POM to disk. This method
   * uses the {@link JGitverPomIO} instance to write the POM file. The logic for this method is
   * based onto the maven-pom-manipulation plugin IO module.
   *
   * @param mavenSession the current Maven session
   * @param project the project whose version should be updated
   * @param version the new version for the project
   * @throws ManipulationException if an error occurs while writing the POM file
   */
  void onProject(MavenSession mavenSession, Project project, String version) {
    project.getModel().setVersion(version);
    try {
      pomIO.writePOM(project);
    } catch (ManipulationException error) {
      ThrowingFunction.sneakyThrow(error);
    }
  }
}
