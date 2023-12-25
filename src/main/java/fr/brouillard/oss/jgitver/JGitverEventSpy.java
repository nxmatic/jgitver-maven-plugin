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
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionEvent.Type;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
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
          .ifPresent(session -> this.onProject(mavenSession, session));
    } finally {
      super.onEvent(event);
    }
  }

  /**
   * Parses the requested project POM file into a list of Project modules and updates their version
   * using the JGitver computed version.
   *
   * <p>This method uses the {@link PomIO} to write the POM file. The logic for this method is
   * mainly based onto the maven-pom-manipulation plugin IO module.
   *
   * @param session the current JGitver session
   * @param mavenSession the related maven session
   * @throws ManipulationException if an error occurs while manipulating the POM file (sneaky
   *     thrown)
   */
  void onProject(MavenSession mavenSession, JGitverSession session) {
    try {
      Set<Project> modules =
          pomIO.parseProject(mavenSession.getRequest().getPom()).stream()
              .peek(project -> setVersionOf(mavenSession, session, project))
              .collect(Collectors.toSet());

      pomIO.writeTemporaryPOMs(modules);
    } catch (ManipulationException error) {
      ThrowingFunction.sneakyThrow(error);
    }
  }

  /**
   * Set the module maven version of the module according to the JGitver computed version.
   *
   * @param session the JGitver session
   * @return module the manipulated model of the module
   */
  void setVersionOf(MavenSession mavenSession, JGitverSession session, Project module) {
    final Model model = module.getModel();
    final String version = session.getVersion();
    model.setVersion(version);
    if (module.isExecutionRoot()) {
      mavenSession.getRequest().setPom(pomIO.withUseVersion.apply(module.getPom()));
    } else {
      model.getParent().setVersion(version);
    }
  }
}
