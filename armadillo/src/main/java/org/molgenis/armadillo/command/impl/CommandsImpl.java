package org.molgenis.armadillo.command.impl;

import static java.lang.String.format;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static org.molgenis.armadillo.controller.ArmadilloUtils.GLOBAL_ENV;
import static org.molgenis.armadillo.minio.ArmadilloStorageService.*;
import static org.molgenis.armadillo.minio.ArmadilloStorageService.PARQUET;
import static org.springframework.security.core.context.SecurityContextHolder.clearContext;
import static org.springframework.security.core.context.SecurityContextHolder.createEmptyContext;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;
import static org.springframework.security.core.context.SecurityContextHolder.setContext;

import java.io.InputStream;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;
import org.molgenis.armadillo.ArmadilloSession;
import org.molgenis.armadillo.ArmadilloSessionFactory;
import org.molgenis.armadillo.command.ArmadilloCommand;
import org.molgenis.armadillo.command.ArmadilloCommandDTO;
import org.molgenis.armadillo.command.Commands;
import org.molgenis.armadillo.minio.ArmadilloStorageService;
import org.molgenis.armadillo.profile.Profile;
import org.molgenis.armadillo.profile.Profiles;
import org.molgenis.r.model.RPackage;
import org.molgenis.r.service.PackageService;
import org.molgenis.r.service.RExecutorService;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.Rserve.RConnection;
import org.springframework.core.io.InputStreamResource;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.SessionScope;

@Service
@SessionScope
class CommandsImpl implements Commands {

  private final ArmadilloStorageService armadilloStorage;
  private final PackageService packageService;
  private final RExecutorService rExecutorService;
  private final ExecutorService executorService;
  private final ArmadilloSessionFactory armadilloSessionFactory;
  private volatile ArmadilloSession armadilloSession;
  private String currentProfile;
  private Profiles profiles;

  @SuppressWarnings("java:S3077") // ArmadilloCommand is thread-safe
  private volatile ArmadilloCommand lastCommand;

  public CommandsImpl(
      ArmadilloStorageService armadilloStorage,
      PackageService packageService,
      RExecutorService rExecutorService,
      ExecutorService executorService,
      ArmadilloSessionFactory armadilloSessionFactory,
      Profiles profiles) {
    this.armadilloStorage = armadilloStorage;
    this.packageService = packageService;
    this.rExecutorService = rExecutorService;
    this.executorService = executorService;
    this.armadilloSessionFactory = armadilloSessionFactory;
    this.profiles = profiles;
    var defaultProfile = profiles.getDefaultProfile();
    this.armadilloSession = armadilloSessionFactory.createSession(defaultProfile.getArmadilloConnectionFactory());
  }

  @Override
  public Profile getCurrentProfile() {
    return profiles.getProfileByName(currentProfile).orElseThrow();
  }

  @Override
  public synchronized Optional<Profile> selectProfile(String profileName) {
    var profile = profiles.getProfileByName(profileName);
    profile.ifPresent(toSelect -> {
      if (this.armadilloSession != null) {
        armadilloSession.sessionCleanup();
      }
      this.armadilloSession = armadilloSessionFactory.createSession(toSelect.getArmadilloConnectionFactory());
      this.currentProfile = profileName;

    });
    return profile;
  }

  @Override
  public List<String> listProfiles() {
    return profiles.getProfiles().stream()
        .map(Profile::getProfileName)
        .collect(Collectors.toList());
  }

  @Override
  public Optional<CompletableFuture<REXP>> getLastExecution() {
    return Optional.ofNullable(lastCommand).flatMap(it -> it.getExecution());
  }

  @Override
  public Optional<ArmadilloCommandDTO> getLastCommand() {
    return Optional.ofNullable(lastCommand).map(ArmadilloCommand::asDto);
  }

  synchronized <T> CompletableFuture<T> schedule(ArmadilloCommandImpl<T> command) {
    final ArmadilloSession session = armadilloSession;
    lastCommand = command;
    Supplier<T> execution = withCurrentSecurityContext(() -> session.execute(command::evaluate));
    CompletableFuture<T> result = supplyAsync(execution, executorService);
    command.setExecution(result);
    return result;
  }

  private <V> Supplier<V> withCurrentSecurityContext(Supplier<V> supplier) {
    final SecurityContext context = getContext();
    return () -> {
      final SecurityContext originalSecurityContext = getContext();
      try {
        setContext(context);
        return supplier.get();
      } finally {
        SecurityContext emptyContext = createEmptyContext();
        if (emptyContext.equals(originalSecurityContext)) {
          clearContext();
        } else {
          setContext(originalSecurityContext);
        }
      }
    };
  }

  @Override
  public CompletableFuture<REXP> evaluate(String expression) {
    return schedule(
        new ArmadilloCommandImpl<>(expression, true) {
          @Override
          protected REXP doWithConnection(RConnection connection) {
            return rExecutorService.execute(expression, connection);
          }
        });
  }

  @Override
  public CompletableFuture<Void> assign(String symbol, String expression) {
    String statement = format("is.null(base::assign('%s', value={%s}))", symbol, expression);
    return schedule(
        new ArmadilloCommandImpl<>(statement, false) {
          @Override
          protected Void doWithConnection(RConnection connection) {
            rExecutorService.execute(statement, connection);
            return null;
          }
        });
  }

  @Override
  public CompletableFuture<Void> loadWorkspace(Principal principal, String id) {
    return schedule(
        new ArmadilloCommandImpl<>("Load user workspace " + id, false) {
          @Override
          protected Void doWithConnection(RConnection connection) {
            InputStream inputStream = armadilloStorage.loadWorkspace(principal, id);
            rExecutorService.loadWorkspace(
                connection, new InputStreamResource(inputStream), GLOBAL_ENV);
            return null;
          }
        });
  }

  @Override
  public CompletableFuture<Void> loadTable(String symbol, String table, List<String> variables) {
    int index = table.indexOf('/');
    String project = table.substring(0, index);
    String objectName = table.substring(index + 1);
    return schedule(
        new ArmadilloCommandImpl<>("Load table " + table, false) {
          @Override
          protected Void doWithConnection(RConnection connection) {
            InputStream inputStream = armadilloStorage.loadTable(project, objectName);
            rExecutorService.loadTable(
                connection,
                new InputStreamResource(inputStream),
                table + PARQUET,
                symbol,
                variables);
            return null;
          }
        });
  }

  @Override
  public CompletableFuture<Void> loadResource(String symbol, String resource) {
    int index = resource.indexOf('/');
    String project = resource.substring(0, index);
    String objectName = resource.substring(index + 1);
    return schedule(
        new ArmadilloCommandImpl<>("Load resource " + resource, false) {
          @Override
          protected Void doWithConnection(RConnection connection) {
            InputStream inputStream = armadilloStorage.loadResource(project, objectName);
            rExecutorService.loadResource(
                connection, new InputStreamResource(inputStream), resource + RDS, symbol);
            return null;
          }
        });
  }

  @Override
  public CompletableFuture<Void> saveWorkspace(Principal principal, String id) {
    return schedule(
        new ArmadilloCommandImpl<>("Save user workspace" + id, false) {
          @Override
          protected Void doWithConnection(RConnection connection) {
            rExecutorService.saveWorkspace(
                connection, is -> armadilloStorage.saveWorkspace(is, principal, id));
            return null;
          }
        });
  }

  @Override
  public CompletableFuture<List<RPackage>> getPackages() {
    return schedule(
        new ArmadilloCommandImpl<>("getInstalledPackages", true) {
          @Override
          protected List<RPackage> doWithConnection(RConnection connection) {
            return packageService.getInstalledPackages(connection);
          }
        });
  }

  @PreDestroy
  public void preDestroy() {
    armadilloSession.sessionCleanup();
  }
}
