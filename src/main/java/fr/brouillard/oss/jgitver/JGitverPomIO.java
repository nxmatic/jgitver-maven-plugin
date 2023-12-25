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

import com.google.inject.Singleton;
import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;
import javax.inject.Named;
import org.commonjava.maven.ext.common.ManipulationException;
import org.commonjava.maven.ext.common.model.Project;
import org.commonjava.maven.ext.io.PomIO;

/**
 * A specialized version of the {@link PomIO} class that is designed to work with JGitver. It
 * provides a method to write down a project's POM (Project Object Model) instrumented by JGitver to
 * disk, This class is annotated with {@link Named} and {@link Singleton} to indicate that it should
 * be used as a singleton component in a dependency injection context, and that it can be referred
 * to by the name "jgitver".
 */
@Named("jgitver")
@Singleton
public class JGitverPomIO extends PomIO {

  /**
   * Writes the provided project's POM (Project Object Model) to disk.
   *
   * @param project the project whose POM should be written
   * @throws ManipulationException if an error occurs while writing the POM
   */
  public void writeTemporaryPOMs(Set<Project> projects) throws ManipulationException {
    writeTemporaryPOMs(projects, withUseVersion);
  }

  /**
   * A function that takes a File instance (representing a POM file), and returns a new File
   * instance representing a sibling file named "pom-with-use-version.xml".
   */
  final Function<File, File> withUseVersion =
      source -> pomResolver.apply(source, Path.of("pom-jgitver-plugin.xml"));
}
