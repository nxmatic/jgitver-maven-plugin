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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Named;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;

/**
 * Holds JGitverSession instances for different Maven sessions. This class is designed to be
 * compatible with m2e, where each project in the workspace is built separately with its own
 * MavenSession. Therefore, each MavenSession (identified by its root directory) can have its own
 * JGitverSession.
 */
@Named
@Singleton
public class JGitverSessionHolder {
  private Map<File, Info> sessions = new HashMap<>();

  class Info {
    final JGitverSession session;

    final MavenSession mavenSession;

    Info(MavenSession mavenSession, JGitverSession session) {
      this.session = session;
      this.mavenSession = mavenSession;
    }

    JGitverSession session() {
      return session;
    }

    String mavenVersion() {
      throw new UnsupportedOperationException("should see");
    }
  }

  /**
   * Associates a JGitverSession with a MavenSession. The JGitverSession is identified by the root
   * directory of the MavenSession. The root directory is retrieved from the MavenSession's
   * request's multi-module project directory.
   *
   * @param mavenSession the MavenSession, its request's multi-module project directory is used as
   *     the key
   * @param jgitverSession the JGitverSession to be associated with the MavenSession
   */
  public void setSession(MavenSession mavenSession, JGitverSession jgitverSession) {
    this.sessions.put(
        mavenSession.getRequest().getMultiModuleProjectDirectory(),
        new Info(mavenSession, jgitverSession));
  }

  /**
   * Retrieves the JGitverSession associated with a MavenSession. The JGitverSession is identified
   * by the root directory of the MavenSession. The root directory is retrieved from the
   * MavenSession's request's multi-module project directory.
   *
   * @param mavenSession the MavenSession, its request's multi-module project directory is used as
   *     the key
   * @return the JGitverSession associated with the MavenSession, or an empty Optional if no session
   *     is associated
   */
  public Optional<JGitverSession> session(MavenSession mavenSession) {
    return Optional.ofNullable(mavenSession)
        .map(MavenSession::getRequest)
        .map(MavenExecutionRequest::getMultiModuleProjectDirectory)
        .map(sessions::get)
        .map(Info::session);
  }
}
