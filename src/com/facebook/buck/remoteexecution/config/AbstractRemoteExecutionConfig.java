/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.remoteexecution.config;

import static com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy.REMOTE;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

/** Config object for the [remoteexecution] section of .buckconfig. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractRemoteExecutionConfig implements ConfigView<BuckConfig> {
  public static final Logger LOG = Logger.get(RemoteExecutionConfig.class);
  public static final String SECTION = "remoteexecution";
  public static final String OLD_SECTION = ModernBuildRuleConfig.SECTION;

  public static final int DEFAULT_REMOTE_PORT = 19030;
  public static final int DEFAULT_CAS_PORT = 19031;

  public static final int DEFAULT_REMOTE_STRATEGY_THREADS = 12;
  public static final int DEFAULT_REMOTE_CONCURRENT_ACTION_COMPUTATIONS = 4;
  public static final int DEFAULT_REMOTE_CONCURRENT_EXECUTIONS = 80;
  public static final int DEFAULT_REMOTE_CONCURRENT_RESULT_HANDLING = 6;

  public String getRemoteHost() {
    return getValueWithFallback("remote_host").orElse("localhost");
  }

  public int getRemotePort() {
    return getValueWithFallback("remote_port").map(Integer::parseInt).orElse(DEFAULT_REMOTE_PORT);
  }

  public String getCasHost() {
    return getValueWithFallback("cas_host").orElse("localhost");
  }

  public int getCasPort() {
    return getValueWithFallback("cas_port").map(Integer::parseInt).orElse(DEFAULT_CAS_PORT);
  }

  public boolean getInsecure() {
    return getDelegate().getBooleanValue(SECTION, "insecure", true);
  }

  public boolean getCasInsecure() {
    return getDelegate().getBooleanValue(SECTION, "cas_insecure", true);
  }

  public Optional<String> getRemoteHostSNIName() {
    return getDelegate().getValue(SECTION, "remote_host_sni_name");
  }

  public Optional<String> getCasHostSNIName() {
    return getDelegate().getValue(SECTION, "cas_host_sni_name");
  }

  /** client TLS certificate file in PEM format */
  public Optional<Path> getCertFile() {
    return getFileOption("cert");
  }

  /** client TLS private key in PEM format */
  public Optional<Path> getKeyFile() {
    return getFileOption("key");
  }

  /** file containing all TLS authorities to verify server certificate with (PEM format) */
  public Optional<Path> getCAsFile() {
    return getFileOption("ca");
  }

  @Value.Derived
  public RemoteExecutionStrategyConfig getStrategyConfig() {
    return new RemoteExecutionStrategyConfig() {
      /**
       * Number of threads for the strategy to do its work. This doesn't need to be a lot, but
       * should probably be greater than concurrent_result_handling below.
       */
      @Override
      public int getThreads() {
        return getDelegate()
            .getInteger(SECTION, "worker_threads")
            .orElse(DEFAULT_REMOTE_STRATEGY_THREADS);
      }

      /**
       * Number of actions to compute at a time. These should be <25ms average, but require I/O and
       * cpu.
       */
      @Override
      public int getMaxConcurrentActionComputations() {
        return getDelegate()
            .getInteger(SECTION, "concurrent_action_computations")
            .orElse(DEFAULT_REMOTE_CONCURRENT_ACTION_COMPUTATIONS);
      }

      /**
       * Limit on the number of outstanding execution requests. This is probably the value that's
       * most likely to be non-default.
       */
      @Override
      public int getMaxConcurrentExecutions() {
        return getDelegate()
            .getInteger(SECTION, "concurrent_executions")
            .orElse(DEFAULT_REMOTE_CONCURRENT_EXECUTIONS);
      }

      /**
       * Number of results to concurrently handle at a time. This is mostly just downloading outputs
       * which is currently a blocking operation.
       */
      @Override
      public int getMaxConcurrentResultHandling() {
        return getDelegate()
            .getInteger(SECTION, "concurrent_result_handling")
            .orElse(DEFAULT_REMOTE_CONCURRENT_RESULT_HANDLING);
      }
    };
  }

  public RemoteExecutionType getType() {
    Optional<RemoteExecutionType> specifiedType =
        getDelegate().getEnum(SECTION, "type", RemoteExecutionType.class);
    if (specifiedType.isPresent()) {
      return specifiedType.get();
    }

    ModernBuildRuleBuildStrategy fallbackStrategy =
        getDelegate().getView(ModernBuildRuleConfig.class).getBuildStrategy();
    // Try the old modern_build_rule way.
    switch (fallbackStrategy) {
      case HYBRID_LOCAL:
      case DEBUG_RECONSTRUCT:
      case DEBUG_PASSTHROUGH:
      case REMOTE:
      case NONE:
        break;

      case GRPC_REMOTE:
        specifiedType = Optional.of(RemoteExecutionType.GRPC);
        break;
      case DEBUG_ISOLATED_OUT_OF_PROCESS_GRPC:
        specifiedType = Optional.of(RemoteExecutionType.DEBUG_GRPC_IN_PROCESS);
        break;
      case DEBUG_GRPC_SERVICE_IN_PROCESS:
        specifiedType = Optional.of(RemoteExecutionType.DEBUG_GRPC_LOCAL);
        break;
    }

    if (!specifiedType.isPresent()) {
      return RemoteExecutionType.DEFAULT;
    }

    String currentConfig = String.format("%s.strategy=%s", OLD_SECTION, fallbackStrategy);
    String newStrategy = String.format("%s.strategy=%s", OLD_SECTION, REMOTE);
    String newRemoteType = String.format("%s.type=%s", SECTION, specifiedType.get());

    LOG.error(
        "Configuration %s is deprecated. This should be configured by setting both %s and %s.",
        currentConfig, newStrategy, newRemoteType);
    return specifiedType.get();
  }

  public Optional<String> getValueWithFallback(String key) {
    Optional<String> value = getDelegate().getValue(SECTION, key);
    if (value.isPresent()) {
      return value;
    }
    value = getDelegate().getValue(OLD_SECTION, key);
    if (value.isPresent()) {
      LOG.error(
          "Configuration should be specified with %s.%s, not %s.%s.",
          SECTION, key, OLD_SECTION, key);
    }
    return value;
  }

  public Optional<Path> getFileOption(String key) {
    return getDelegate().getValue(SECTION, key).map(Paths::get);
  }

  /** Whether SuperConsole output of Remote Execution information is enabled. */
  public boolean isSuperConsoleEnabled() {
    return getType() != RemoteExecutionType.NONE;
  }
}
