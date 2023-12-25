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

import fr.brouillard.oss.jgitver.cfg.Configuration;
import fr.brouillard.oss.jgitver.lambda.ThrowingFunction;
import fr.brouillard.oss.jgitver.metadata.Metadatas;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.building.ModelProcessor;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.logging.Logger;
import org.commonjava.maven.ext.core.ManipulatingExtensionBridge;

@Named("jgitver")
@Singleton
public class JGitverExtension extends AbstractMavenLifecycleParticipant {
  @Inject private Logger logger;

  @Inject private PlexusContainer container;

  @Inject private ModelProcessor modelProcessor;

  @Inject private JGitverSessionHolder sessionHolder;

  @Inject private JGitverConfiguration configurationProvider;

  @Inject private ManipulatingExtensionBridge manipulatingBridge;

  /**
   * Called after the Maven session starts. If jgitver is not skipped, it opens a new JGitverSession
   * and associates it with the MavenSession.
   *
   * @param mavenSession the MavenSession that just started
   * @throws MavenExecutionException if an error occurs while opening the JGitverSession
   */
  @Override
  public void afterSessionStart(MavenSession mavenSession) throws MavenExecutionException {
    if (JGitverUtils.shouldSkip(mavenSession)) {
      logger.info("jgitver execution has been skipped by request of the user");
      sessionHolder.setSession(mavenSession, null);
    } else {
      sessionHolder.setSession(mavenSession, opener.openSession(mavenSession));
    }
  }

  /**
   * Called after the Maven session ends. It removes the JGitverSession associated with the
   * MavenSession.
   *
   * @param session the MavenSession that just ended
   * @throws MavenExecutionException if an error occurs while removing the JGitverSession
   */
  @Override
  public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
    sessionHolder.setSession(session, null);
  }

  /**
   * Called after the projects have been read. This method ensures that a JGitverSession exists for
   * the MavenSession, as the afterSessionStart method is not invoked by M2E. If jgitver is not
   * skipped, it opens a new JGitverSession and associates it with the MavenSession. Note that a
   * single MavenSession can be involved in multiple projects when invoked by M2E.
   *
   * @param session the MavenSession that just started
   * @param projects the projects that have been read
   * @throws MavenExecutionException if an error occurs while opening the JGitverSession
   */
  @Override
  public void afterProjectsRead(MavenSession mavenSession) throws MavenExecutionException {
    if (JGitverUtils.shouldSkip(mavenSession)) {
      return;
    }
    if (!JGitverModelProcessor.class.isAssignableFrom(modelProcessor.getClass())) {
      return;
    }

    File projectBaseDir = mavenSession.getCurrentProject().getBasedir();
    try {
      if (projectBaseDir != null
          && !configurationProvider.ignore(new File(projectBaseDir, "pom.xml"))) {
        if (isRunningInM2E(mavenSession)) {
          m2eInvoker.invoke(() -> opener.ensureOpenSession(mavenSession));
        }
        if (sessionHolder.session(mavenSession).isEmpty()) {
          JGitverUtils.failAsOldMechanism(cs -> logger.warn(cs.toString()));
        }
        sessionHolder
            .session(mavenSession)
            .ifPresent(
                jgitverSession -> {
                  logger.info("jgitver-maven-plugin is about to change project(s) version(s)");

                  jgitverSession
                      .getProjects()
                      .forEach(
                          gav ->
                              logger.info(
                                  "    "
                                      + jgitverSession.getOriginalGAV(
                                          gav, manipulatingBridge.readReport(mavenSession))
                                      + " -> "
                                      + jgitverSession.getVersion()));
                });
      }
    } catch (IOException ex) {
      new MavenExecutionException(
          "cannot evaluate if jgitver should ignore base project directory: " + projectBaseDir, ex);
    }
  }

  private final Invoker m2eInvoker =
      new ChainedInvoker(new ThreadSafeInvoker(), new ClassRealmContextInjector());

  @FunctionalInterface
  private interface Invokable<T> {
    T invoke();
  }

  @FunctionalInterface
  private interface Runnable<T> {
    void invoke();
  }

  private interface Invoker {
    <T> T invoke(Invokable<T> invokable);

    default void invoke(Runnable runnable) {
      invoke(
          () -> {
            runnable.invoke();
            return null;
          });
    }
  }

  private class ChainedInvoker implements Invoker {
    private final List<Invoker> invokers;

    public ChainedInvoker(Invoker... invokers) {
      this.invokers = Arrays.asList(invokers);
    }

    @Override
    public <T> T invoke(Invokable<T> invokable) {
      Invokable<T> chainedInvokable = invokable;
      for (int i = invokers.size() - 1; i >= 0; i--) {
        Invoker invoker = invokers.get(i);
        final Invokable<T> finalChainedInvokable = chainedInvokable;
        chainedInvokable = () -> invoker.invoke(finalChainedInvokable);
      }
      return chainedInvokable.invoke();
    }
  }

  private class ClassRealmContextInjector implements Invoker {
    final ClassRealm classRealm = loadClassRealm();

    public <T> T invoke(Invokable<T> invokable) {
      ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
      try {
        Thread.currentThread().setContextClassLoader(classRealm);
        return invokable.invoke();
      } finally {
        Thread.currentThread().setContextClassLoader(originalClassLoader);
      }
    }

    ClassRealm loadClassRealm() {
      return (ClassRealm) GitVersionCalculatorBuilder.class.getClassLoader();
    }
  }

  private class ThreadSafeInvoker implements Invoker {

    @SuppressWarnings("unused")
    private static final long serialVersionUID = 1L;

    private final ReentrantLock lock = new ReentrantLock();

    public <T> T invoke(Invokable<T> invokable) {
      lock.lock(); // block until condition holds
      try {
        return invokable.invoke();
      } finally {
        lock.unlock();
      }
    }
  }

  private boolean isRunningInM2E(MavenSession session) {
    return session.getUserProperties().containsKey("m2e.version");
  }

  private final SessionOpener opener = new SessionOpener();

  class SessionOpener {

    void ensureOpenSession(MavenSession mavenSession) {
      sessionHolder.setSession(
          mavenSession,
          sessionHolder.session(mavenSession).orElseGet(() -> openSessionSneaky(mavenSession)));
      sessionHolder.session(mavenSession).orElseThrow();
    }

    JGitverSession openSession(MavenSession mavenSession) throws MavenExecutionException {
      final File rootDirectory = mavenSession.getRequest().getMultiModuleProjectDirectory();
      try (GitVersionCalculator calculator = GitVersionCalculator.location(rootDirectory)) {
        return openSession(mavenSession, calculator);
      } catch (Exception ex) {
        logger.warn(
            "cannot autoclose GitVersionCalculator object for project: " + rootDirectory, ex);
        return null;
      }
    }

    JGitverSession openSessionSneaky(MavenSession mavenSession) {
      try {
        return openSession(mavenSession);
      } catch (MavenExecutionException cause) {
        return ThrowingFunction.sneakyThrow(cause);
      }
    }

    private JGitverSession openSession(
        MavenSession mavenSession, GitVersionCalculator gitVersionCalculator)
        throws MavenExecutionException {
      final File rootDirectory = mavenSession.getRequest().getMultiModuleProjectDirectory();

      logger.debug("using " + JGitverUtils.EXTENSION_PREFIX + " on directory: " + rootDirectory);

      Configuration cfg = configurationProvider.getConfiguration();

      if (cfg.strategy != null) {
        gitVersionCalculator.setStrategy(cfg.strategy);
      } else {
        gitVersionCalculator.setMavenLike(cfg.mavenLike);
      }

      if (cfg.policy != null) {
        gitVersionCalculator.setLookupPolicy(cfg.policy);
      }

      gitVersionCalculator
          .setAutoIncrementPatch(cfg.autoIncrementPatch)
          .setUseDirty(cfg.useDirty)
          .setUseDistance(cfg.useCommitDistance)
          .setUseGitCommitTimestamp(cfg.useGitCommitTimestamp)
          .setUseGitCommitId(cfg.useGitCommitId)
          .setUseSnapshot(cfg.useSnapshot)
          .setGitCommitIdLength(cfg.gitCommitIdLength)
          .setUseDefaultBranchingPolicy(cfg.useDefaultBranchingPolicy)
          .setNonQualifierBranches(cfg.nonQualifierBranches)
          .setVersionPattern(cfg.versionPattern)
          .setTagVersionPattern(cfg.tagVersionPattern)
          .setScript(cfg.script)
          .setScriptType(cfg.scriptType);

      if (cfg.maxSearchDepth >= 1 && cfg.maxSearchDepth != Configuration.UNSET_DEPTH) {
        // keep redundant test in case we change UNSET_DEPTH value
        gitVersionCalculator.setMaxDepth(cfg.maxSearchDepth);
      }

      if (JGitverUtils.shouldForceComputation(mavenSession)) {
        gitVersionCalculator.setForceComputation(true);
      }

      if (cfg.regexVersionTag != null) {
        gitVersionCalculator.setFindTagVersionPattern(cfg.regexVersionTag);
      }

      if (cfg.branchPolicies != null && !cfg.branchPolicies.isEmpty()) {
        List<BranchingPolicy> policies =
            cfg.branchPolicies.stream()
                .map(bp -> new BranchingPolicy(bp.pattern, bp.transformations))
                .collect(Collectors.toList());

        gitVersionCalculator.setQualifierBranchingPolicies(policies);
      }

      logger.info(
          String.format(
              "Using jgitver-maven-plugin [%s] (sha1: %s)",
              JGitverMavenPluginProperties.getVersion(), JGitverMavenPluginProperties.getSHA1()));
      long start = System.currentTimeMillis();

      String computedVersion = gitVersionCalculator.getVersion();

      long duration = System.currentTimeMillis() - start;
      logger.info(String.format("    version '%s' computed in %d ms", computedVersion, duration));
      logger.info("");

      boolean isDirty =
          gitVersionCalculator
              .meta(Metadatas.DIRTY)
              .map(Boolean::parseBoolean)
              .orElse(Boolean.FALSE);

      if (cfg.failIfDirty && isDirty) {
        throw new IllegalStateException("repository is dirty");
      }

      JGitverInformationProvider infoProvider = Providers.decorate(gitVersionCalculator);
      JGitverInformationProvider finalInfoProvider = infoProvider;
      infoProvider =
          JGitverUtils.versionOverride(mavenSession, logger)
              .map(version -> Providers.fixVersion(version, finalInfoProvider))
              .orElse(infoProvider);

      // Put metadatas into Maven session properties
      JGitverUtils.fillPropertiesFromMetadatas(
          mavenSession.getUserProperties(), infoProvider, logger);

      // Put metadatas into exportedProps file (if requested)
      Optional<String> exportPropsPathOpt = JGitverUtils.exportPropertiesPath(mavenSession, logger);
      if (exportPropsPathOpt.isPresent()) {
        Properties exportedProps = new Properties();
        JGitverUtils.fillPropertiesFromMetadatas(exportedProps, infoProvider, logger);
        String exportPropsPath = exportPropsPathOpt.get();
        try (OutputStream os = new FileOutputStream(exportPropsPath)) {
          exportedProps.store(
              os, "Output from " + JGitverUtils.EXTENSION_ARTIFACT_ID + " execution.");
          logger.info("Properties exported to file \"" + exportPropsPath + "\"");
        } catch (IOException ex) {
          throw new IllegalArgumentException(
              "Cannot write properties to file \"" + exportPropsPath + "\"", ex);
        }
      }

      return new JGitverSession(infoProvider, rootDirectory);
    }
  }
}
