/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.llap.cli;


import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.llap.cli.LlapStatusOptionsProcessor.LlapStatusOptions;
import org.apache.hadoop.hive.llap.configuration.LlapDaemonConfiguration;
import org.apache.hadoop.hive.llap.registry.ServiceInstance;
import org.apache.hadoop.hive.llap.registry.impl.LlapRegistryService;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.slider.api.ClusterDescription;
import org.apache.slider.api.ClusterDescriptionKeys;
import org.apache.slider.api.StatusKeys;
import org.apache.slider.client.SliderClient;
import org.apache.slider.core.exceptions.SliderException;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LlapStatusServiceDriver {

  private static final Logger LOG = LoggerFactory.getLogger(LlapStatusServiceDriver.class);



  private static final String AM_KEY = "slider-appmaster";
  private static final String LLAP_KEY = "LLAP";

  private final Configuration conf;
  private final Clock clock = new SystemClock();
  @VisibleForTesting
  final AppStatusBuilder appStatusBuilder = new AppStatusBuilder();

  public LlapStatusServiceDriver() {
    SessionState ss = SessionState.get();
    conf = (ss != null) ? ss.getConf() : new HiveConf(SessionState.class);
  }

  /**
   * Parse command line options.
   *
   * @param args
   * @return command line options.
   */
  public LlapStatusOptions parseOptions(String[] args) throws LlapStatusCliException {

    LlapStatusOptionsProcessor optionsProcessor = new LlapStatusOptionsProcessor();
    LlapStatusOptions options;
    try {
      options = optionsProcessor.processOptions(args);
      return options;
    } catch (Exception e) {
      LOG.info("Failed to parse arguments", e);
      throw new LlapStatusCliException(ExitCode.INCORRECT_USAGE, "Incorrect usage");
    }
  }

  public int run(LlapStatusOptions options) {

    SliderClient sliderClient = null;
    try {

      for (String f : LlapDaemonConfiguration.DAEMON_CONFIGS) {
        conf.addResource(f);
      }
      conf.reloadConfiguration();
      for (Map.Entry<Object, Object> props : options.getConf().entrySet()) {
        conf.set((String) props.getKey(), (String) props.getValue());
      }

      String appName;
      appName = options.getName();
      if (StringUtils.isEmpty(appName)) {
        appName = HiveConf.getVar(conf, HiveConf.ConfVars.LLAP_DAEMON_SERVICE_HOSTS);
        if (appName.startsWith("@") && appName.length() > 1) {
          // This is a valid slider app name. Parse it out.
          appName = appName.substring(1);
        } else {
          // Invalid app name. Checked later.
          appName = null;
        }
      }
      if (StringUtils.isEmpty(appName)) {
        String message =
            "Invalid app name. This must be setup via config or passed in as a parameter." +
                " This tool works with clusters deployed by Slider/YARN";
        LOG.info(message);
        return ExitCode.INCORRECT_USAGE.getInt();
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Using appName: {}", appName);
      }

      try {
        sliderClient = createSliderClient();
      } catch (LlapStatusCliException e) {
        logError(e);
        return e.getExitCode().getInt();
      }

      // Get the App report from YARN
      ApplicationReport appReport = null;
      try {
        appReport = getAppReport(appName, sliderClient, options.getFindAppTimeoutMs());
      } catch (LlapStatusCliException e) {
        logError(e);
        return e.getExitCode().getInt();
      }

      // Process the report to decide whether to go to slider.
      ExitCode ret;
      try {
        ret = processAppReport(appReport, appStatusBuilder);
      } catch (LlapStatusCliException e) {
        logError(e);
        return e.getExitCode().getInt();
      }

      if (ret != ExitCode.SUCCESS) {
        return ret.getInt();
      } else if (EnumSet.of(State.APP_NOT_FOUND, State.COMPLETE, State.LAUNCHING)
          .contains(appStatusBuilder.getState())) {
        return ExitCode.SUCCESS.getInt();
      } else {
        // Get information from slider.
        try {
          ret = populateAppStatusFromSlider(appName, sliderClient, appStatusBuilder);
        } catch (LlapStatusCliException e) {
          // In case of failure, send back whatever is constructed sop far - which wouldbe from the AppReport
          logError(e);
          return e.getExitCode().getInt();
        }
      }

      if (ret !=ExitCode.SUCCESS ) {
        return ret.getInt();
      } else {
        try {
          ret = populateAppStatusFromLlapRegistry(appName, appStatusBuilder);
        } catch (LlapStatusCliException e) {
          logError(e);
          return e.getExitCode().getInt();
        }
      }
      return ret.getInt();
    }finally {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Final AppState: " + appStatusBuilder.toString());
      }
      if (sliderClient != null) {
        sliderClient.stop();
      }
    }
  }

  public void outputJson(PrintWriter writer) throws LlapStatusCliException {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SerializationConfig.Feature.FAIL_ON_EMPTY_BEANS, false);
    mapper.setSerializationInclusion(JsonSerialize.Inclusion.NON_NULL);
    mapper.setSerializationInclusion(JsonSerialize.Inclusion.NON_EMPTY);
    try {
      writer.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(appStatusBuilder));
    } catch (IOException e) {
      LOG.warn("Failed to create JSON", e);
      throw new LlapStatusCliException(ExitCode.LLAP_JSON_GENERATION_ERROR, "Failed to create JSON",
          e);
    }
  }

  private SliderClient createSliderClient() throws LlapStatusCliException {
    SliderClient sliderClient;
    try {
      sliderClient = new SliderClient() {
        @Override
        public void serviceInit(Configuration conf) throws Exception {
          super.serviceInit(conf);
          initHadoopBinding();
        }
      };
      Configuration sliderClientConf = new Configuration(conf);
      sliderClientConf = sliderClient.bindArgs(sliderClientConf,
          new String[] { "help" });
      sliderClient.init(sliderClientConf);
      sliderClient.start();
      return sliderClient;
    } catch (Exception e) {
      throw new LlapStatusCliException(ExitCode.SLIDER_CLIENT_ERROR_CREATE_FAILED,
          "Failed to create slider client", e);
    }
  }


  private ApplicationReport getAppReport(String appName, SliderClient sliderClient,
                                         long timeoutMs) throws LlapStatusCliException {

    long startTime = clock.getTime();
    long timeoutTime = timeoutMs < 0 ? Long.MAX_VALUE : (startTime + timeoutMs);
    ApplicationReport appReport = null;

    // TODO HIVE-13454 Maybe add an option to wait for a certain amount of time for the app to
    // move to running state. Potentially even wait for the containers to be launched.

//    while (clock.getTime() < timeoutTime && appReport == null) {

    while (appReport == null) {
      try {
        appReport = sliderClient.getYarnAppListClient().findInstance(appName);
        if (timeoutMs == 0) {
          // break immediately if timeout is 0
          break;
        }
        // Otherwise sleep, and try again.
        if (appReport == null) {
          long remainingTime = Math.min(timeoutTime - clock.getTime(), 500l);
          if (remainingTime > 0) {
            Thread.sleep(remainingTime);
          } else {
            break;
          }
        }
      } catch (Exception e) { // No point separating IOException vs YarnException vs others
        throw new LlapStatusCliException(ExitCode.YARN_ERROR,
            "Failed to get Yarn AppReport", e);
      }
    }
    return appReport;
  }


  /**
   * Populates parts of the AppStatus
   *
   * @param appReport
   * @param appStatusBuilder
   * @return an ExitCode. An ExitCode other than ExitCode.SUCCESS implies future progress not possible
   * @throws LlapStatusCliException
   */
  private ExitCode processAppReport(ApplicationReport appReport,
                               AppStatusBuilder appStatusBuilder) throws LlapStatusCliException {
    if (appReport == null) {
      appStatusBuilder.setState(State.APP_NOT_FOUND);
      LOG.info("No Application Found");
      return ExitCode.SUCCESS;
    }

    appStatusBuilder.setAmInfo(
        new AmInfo().setAppName(appReport.getName()).setAppType(appReport.getApplicationType()));
    appStatusBuilder.setAppStartTime(appReport.getStartTime());
    switch (appReport.getYarnApplicationState()) {
      case NEW:
      case NEW_SAVING:
      case SUBMITTED:
        appStatusBuilder.setState(State.LAUNCHING);
        return ExitCode.SUCCESS;
      case ACCEPTED:
        appStatusBuilder.maybeCreateAndGetAmInfo().setAppId(appReport.getApplicationId().toString());
        appStatusBuilder.setState(State.LAUNCHING);
        return ExitCode.SUCCESS;
      case RUNNING:
        appStatusBuilder.maybeCreateAndGetAmInfo().setAppId(appReport.getApplicationId().toString());
        // If the app state is running, get additional information from Slider itself.
        return ExitCode.SUCCESS;
      case FINISHED:
      case FAILED:
      case KILLED:
        appStatusBuilder.maybeCreateAndGetAmInfo().setAppId(appReport.getApplicationId().toString());
        appStatusBuilder.setAppFinishTime(appReport.getFinishTime());
        appStatusBuilder.setState(State.COMPLETE);
        return ExitCode.SUCCESS;
      default:
        throw new LlapStatusCliException(ExitCode.INTERNAL_ERROR,
            "Unknown Yarn Application State: " + appReport.getYarnApplicationState());
    }
  }


  /**
   *
   * @param appName
   * @param sliderClient
   * @param appStatusBuilder
   * @return an ExitCode. An ExitCode other than ExitCode.SUCCESS implies future progress not possible
   * @throws LlapStatusCliException
   */
  private ExitCode populateAppStatusFromSlider(String appName, SliderClient sliderClient, AppStatusBuilder appStatusBuilder) throws
      LlapStatusCliException {

    ClusterDescription clusterDescription;
    try {
      clusterDescription = sliderClient.getClusterDescription(appName);
    } catch (SliderException e) {
      throw new LlapStatusCliException(ExitCode.SLIDER_CLIENT_ERROR_OTHER,
          "Failed to get cluster description from slider. SliderErrorCode=" + (e).getExitCode(), e);
    } catch (Exception e) {
      throw new LlapStatusCliException(ExitCode.SLIDER_CLIENT_ERROR_OTHER,
          "Failed to get cluster description from slider", e);
    }

    if (clusterDescription == null) {
      LOG.info("Slider ClusterDescription not available");
      return ExitCode.SLIDER_CLIENT_ERROR_OTHER; // ClusterDescription should always be present.
    } else {
      // Process the Cluster Status returned by slider.
      appStatusBuilder.setOriginalConfigurationPath(clusterDescription.originConfigurationPath);
      appStatusBuilder.setGeneratedConfigurationPath(clusterDescription.generatedConfigurationPath);
      appStatusBuilder.setAppStartTime(clusterDescription.createTime);

      // Finish populating AMInfo
      appStatusBuilder.maybeCreateAndGetAmInfo().setAmWebUrl(clusterDescription.getInfo(StatusKeys.INFO_AM_WEB_URL));
      appStatusBuilder.maybeCreateAndGetAmInfo().setHostname(clusterDescription.getInfo(StatusKeys.INFO_AM_HOSTNAME));
      appStatusBuilder.maybeCreateAndGetAmInfo().setContainerId(clusterDescription.getInfo(StatusKeys.INFO_AM_CONTAINER_ID));


      if (clusterDescription.statistics != null) {
        Map<String, Integer> llapStats = clusterDescription.statistics.get(LLAP_KEY);
        if (llapStats != null) {
          int desiredContainers = llapStats.get(StatusKeys.STATISTICS_CONTAINERS_DESIRED);
          int liveContainers = llapStats.get(StatusKeys.STATISTICS_CONTAINERS_LIVE);
          appStatusBuilder.setDesiredInstances(desiredContainers);
          appStatusBuilder.setLiveInstances(liveContainers);
        } else {
          throw new LlapStatusCliException(ExitCode.SLIDER_CLIENT_ERROR_OTHER,
              "Failed to get statistics for LLAP"); // Error since LLAP should always exist.
        }
        // TODO HIVE-13454 Use some information from here such as containers.start.failed
        // and containers.failed.recently to provide an estimate of whether this app is healthy or not.
      } else {
        throw new LlapStatusCliException(ExitCode.SLIDER_CLIENT_ERROR_OTHER,
            "Failed to get statistics"); // Error since statistics should always exist.
      }

      // Code to locate container status via slider. Not using this at the moment.
      if (clusterDescription.status != null) {
        Object liveObject = clusterDescription.status.get(ClusterDescriptionKeys.KEY_CLUSTER_LIVE);
        if (liveObject != null) {
          Map<String, Map<String, Map<String, Object>>> liveEntity =
              (Map<String, Map<String, Map<String, Object>>>) liveObject;
          Map<String, Map<String, Object>> llapEntity = liveEntity.get(LLAP_KEY);

          if (llapEntity != null) { // Not a problem. Nothing has come up yet.
            for (Map.Entry<String, Map<String, Object>> containerEntry : llapEntity.entrySet()) {
              String containerIdString = containerEntry.getKey();
              Map<String, Object> containerParams = containerEntry.getValue();

              String host = (String) containerParams.get("host");

              LlapInstance llapInstance = new LlapInstance(host, containerIdString);

              appStatusBuilder.addNewLlapInstance(llapInstance);
            }
          }

        }
      }

      return ExitCode.SUCCESS;

    }
  }


  /**
   *
   * @param appName
   * @param appStatusBuilder
   * @return an ExitCode. An ExitCode other than ExitCode.SUCCESS implies future progress not possible
   * @throws LlapStatusCliException
   */
  private ExitCode populateAppStatusFromLlapRegistry(String appName, AppStatusBuilder appStatusBuilder) throws
      LlapStatusCliException {
    Configuration llapRegistryConf= new Configuration(conf);
    llapRegistryConf
        .set(HiveConf.ConfVars.LLAP_DAEMON_SERVICE_HOSTS.varname, "@" + appName);
    LlapRegistryService llapRegistry;
    try {
      llapRegistry = LlapRegistryService.getClient(llapRegistryConf);
    } catch (Exception e) {
      throw new LlapStatusCliException(ExitCode.LLAP_REGISTRY_ERROR,
          "Failed to create llap registry client", e);
    }
    try {
      Map<String, ServiceInstance> serviceInstanceMap;
      try {
        serviceInstanceMap = llapRegistry.getInstances().getAll();
      } catch (IOException e) {
        throw new LlapStatusCliException(ExitCode.LLAP_REGISTRY_ERROR, "Failed to get instances from llap registry", e);
      }

      if (serviceInstanceMap == null || serviceInstanceMap.isEmpty()) {
        LOG.info("No information found in the LLAP registry");
        appStatusBuilder.setLiveInstances(0);
        appStatusBuilder.setState(State.LAUNCHING);
        appStatusBuilder.clearLlapInstances();
        return ExitCode.SUCCESS;
      } else {


        // Tracks instances known by both slider and llap.
        List<LlapInstance> validatedInstances = new LinkedList<>();
        List<String> llapExtraInstances = new LinkedList<>();

        for (Map.Entry<String, ServiceInstance> serviceInstanceEntry : serviceInstanceMap
            .entrySet()) {

          ServiceInstance serviceInstance = serviceInstanceEntry.getValue();
          String containerIdString = serviceInstance.getProperties().get(HiveConf.ConfVars.LLAP_DAEMON_CONTAINER_ID.varname);


          LlapInstance llapInstance = appStatusBuilder.removeAndgetLlapInstanceForContainer(containerIdString);
          if (llapInstance != null) {
            llapInstance.setMgmtPort(serviceInstance.getManagementPort());
            llapInstance.setRpcPort(serviceInstance.getRpcPort());
            llapInstance.setShufflePort(serviceInstance.getShufflePort());
            llapInstance.setWebUrl(serviceInstance.getServicesAddress());
            llapInstance.setStatusUrl(serviceInstance.getServicesAddress() + "/status");
            validatedInstances.add(llapInstance);
          } else {
            // This likely indicates that an instance has recently restarted
            // (the old instance has not been unregistered), and the new instances has not registered yet.
            llapExtraInstances.add(containerIdString);
            // This instance will not be added back, since it's services are not up yet.
          }

        }

        appStatusBuilder.setLiveInstances(validatedInstances.size());
        if (validatedInstances.size() >= appStatusBuilder.getDesiredInstances()) {
          appStatusBuilder.setState(State.RUNNING_ALL);
          if (validatedInstances.size() > appStatusBuilder.getDesiredInstances()) {
            LOG.warn("Found more entries in LLAP registry, as compared to desired entries");
          }
        } else {
          appStatusBuilder.setState(State.RUNNING_PARTIAL);
        }

        // At this point, everything that can be consumed from AppStatusBuilder has been consumed.
        // Debug only
        if (appStatusBuilder.allInstances().size() > 0) {
          // Containers likely to come up soon.
          LOG.debug("Potential instances starting up: {}", appStatusBuilder.allInstances());
        }
        if (llapExtraInstances.size() > 0) {
          // Old containers which are likely shutting down
          LOG.debug("Instances likely to shutdown soon: {}", llapExtraInstances);
        }

        appStatusBuilder.clearAndAddPreviouslyKnownInstances(validatedInstances);

      }
      return ExitCode.SUCCESS;
    } finally {
      llapRegistry.stop();
    }

  }


  static final class AppStatusBuilder {

    private AmInfo amInfo;
    private State state = State.UNKNOWN;
    private String originalConfigurationPath;
    private String generatedConfigurationPath;

    private Integer desiredInstances;
    private Integer liveInstances;

    private Long appStartTime;
    private Long appFinishTime;

    private final List<LlapInstance> llapInstances = new LinkedList<>();

    private transient Map<String, LlapInstance> containerToInstanceMap = new HashMap<>();

    public void setAmInfo(AmInfo amInfo) {
      this.amInfo = amInfo;
    }

    public AppStatusBuilder setState(
        State state) {
      this.state = state;
      return this;
    }

    public AppStatusBuilder setOriginalConfigurationPath(String originalConfigurationPath) {
      this.originalConfigurationPath = originalConfigurationPath;
      return this;
    }

    public AppStatusBuilder setGeneratedConfigurationPath(String generatedConfigurationPath) {
      this.generatedConfigurationPath = generatedConfigurationPath;
      return this;
    }

    public AppStatusBuilder setAppStartTime(long appStartTime) {
      this.appStartTime = appStartTime;
      return this;
    }

    public AppStatusBuilder setAppFinishTime(long finishTime) {
      this.appFinishTime = finishTime;
      return this;
    }

    public AppStatusBuilder setDesiredInstances(int desiredInstances) {
      this.desiredInstances = desiredInstances;
      return this;
    }

    public AppStatusBuilder setLiveInstances(int liveInstances) {
      this.liveInstances = liveInstances;
      return this;
    }

    public AppStatusBuilder addNewLlapInstance(LlapInstance llapInstance) {
      this.llapInstances.add(llapInstance);
      this.containerToInstanceMap.put(llapInstance.getContainerId(), llapInstance);
      return this;
    }

    public LlapInstance removeAndgetLlapInstanceForContainer(String containerIdString) {
      return containerToInstanceMap.remove(containerIdString);
    }

    public void clearLlapInstances() {
      this.llapInstances.clear();
      this.containerToInstanceMap.clear();
    }

    public AppStatusBuilder clearAndAddPreviouslyKnownInstances(List<LlapInstance> llapInstances) {
      clearLlapInstances();
      for (LlapInstance llapInstance : llapInstances) {
        addNewLlapInstance(llapInstance);
      }
      return this;
    }

    @JsonIgnore
    public List<LlapInstance> allInstances() {
      return this.llapInstances;
    }

    public AmInfo getAmInfo() {
      return amInfo;
    }

    public State getState() {
      return state;
    }

    public String getOriginalConfigurationPath() {
      return originalConfigurationPath;
    }

    public String getGeneratedConfigurationPath() {
      return generatedConfigurationPath;
    }

    public Integer getDesiredInstances() {
      return desiredInstances;
    }

    public Integer getLiveInstances() {
      return liveInstances;
    }

    public Long getAppStartTime() {
      return appStartTime;
    }

    public Long getAppFinishTime() {
      return appFinishTime;
    }

    public List<LlapInstance> getLlapInstances() {
      return llapInstances;
    }

    @JsonIgnore
    public AmInfo maybeCreateAndGetAmInfo() {
      if (amInfo == null) {
        amInfo = new AmInfo();
      }
      return amInfo;
    }

    @Override
    public String toString() {
      return "AppStatusBuilder{" +
          "amInfo=" + amInfo +
          ", state=" + state +
          ", originalConfigurationPath='" + originalConfigurationPath + '\'' +
          ", generatedConfigurationPath='" + generatedConfigurationPath + '\'' +
          ", desiredInstances=" + desiredInstances +
          ", liveInstances=" + liveInstances +
          ", appStartTime=" + appStartTime +
          ", appFinishTime=" + appFinishTime +
          ", llapInstances=" + llapInstances +
          ", containerToInstanceMap=" + containerToInstanceMap +
          '}';
    }
  }

  static class AmInfo {
    private String appName;
    private String appType;
    private String appId;
    private String containerId;
    private String hostname;
    private String amWebUrl;

    public AmInfo setAppName(String appName) {
      this.appName = appName;
      return this;
    }

    public AmInfo setAppType(String appType) {
      this.appType = appType;
      return this;
    }

    public AmInfo setAppId(String appId) {
      this.appId = appId;
      return this;
    }

    public AmInfo setContainerId(String containerId) {
      this.containerId = containerId;
      return this;
    }

    public AmInfo setHostname(String hostname) {
      this.hostname = hostname;
      return this;
    }

    public AmInfo setAmWebUrl(String amWebUrl) {
      this.amWebUrl = amWebUrl;
      return this;
    }

    public String getAppName() {
      return appName;
    }

    public String getAppType() {
      return appType;
    }

    public String getAppId() {
      return appId;
    }

    public String getContainerId() {
      return containerId;
    }

    public String getHostname() {
      return hostname;
    }

    public String getAmWebUrl() {
      return amWebUrl;
    }

    @Override
    public String toString() {
      return "AmInfo{" +
          "appName='" + appName + '\'' +
          ", appType='" + appType + '\'' +
          ", appId='" + appId + '\'' +
          ", containerId='" + containerId + '\'' +
          ", hostname='" + hostname + '\'' +
          ", amWebUrl='" + amWebUrl + '\'' +
          '}';
    }
  }

  static class LlapInstance {
    private final String hostname;
    private final String containerId;
    private String statusUrl;
    private String webUrl;
    private Integer rpcPort;
    private Integer mgmtPort;
    private Integer  shufflePort;

    // TODO HIVE-13454 Add additional information such as #executors, container size, etc

    public LlapInstance(String hostname, String containerId) {
      this.hostname = hostname;
      this.containerId = containerId;
    }

    public LlapInstance setWebUrl(String webUrl) {
      this.webUrl = webUrl;
      return this;
    }

    public LlapInstance setStatusUrl(String statusUrl) {
      this.statusUrl = statusUrl;
      return this;
    }

    public LlapInstance setRpcPort(int rpcPort) {
      this.rpcPort = rpcPort;
      return this;
    }

    public LlapInstance setMgmtPort(int mgmtPort) {
      this.mgmtPort = mgmtPort;
      return this;
    }

    public LlapInstance setShufflePort(int shufflePort) {
      this.shufflePort = shufflePort;
      return this;
    }

    public String getHostname() {
      return hostname;
    }

    public String getStatusUrl() {
      return statusUrl;
    }

    public String getContainerId() {
      return containerId;
    }

    public String getWebUrl() {
      return webUrl;
    }

    public Integer getRpcPort() {
      return rpcPort;
    }

    public Integer getMgmtPort() {
      return mgmtPort;
    }

    public Integer getShufflePort() {
      return shufflePort;
    }

    @Override
    public String toString() {
      return "LlapInstance{" +
          "hostname='" + hostname + '\'' +
          ", containerId='" + containerId + '\'' +
          ", statusUrl='" + statusUrl + '\'' +
          ", webUrl='" + webUrl + '\'' +
          ", rpcPort=" + rpcPort +
          ", mgmtPort=" + mgmtPort +
          ", shufflePort=" + shufflePort +
          '}';
    }
  }

  static class LlapStatusCliException extends Exception {
    final ExitCode exitCode;


    public LlapStatusCliException(ExitCode exitCode, String message) {
      super(exitCode.getInt() +": " + message);
      this.exitCode = exitCode;
    }

    public LlapStatusCliException(ExitCode exitCode, String message, Throwable cause) {
      super(message, cause);
      this.exitCode = exitCode;
    }

    public ExitCode getExitCode() {
      return exitCode;
    }
  }

  enum State {
    APP_NOT_FOUND, LAUNCHING,
    RUNNING_PARTIAL,
    RUNNING_ALL, COMPLETE, UNKNOWN
  }

  public enum ExitCode {
    SUCCESS(0),
    INCORRECT_USAGE(10),
    YARN_ERROR(20),
    SLIDER_CLIENT_ERROR_CREATE_FAILED(30),
    SLIDER_CLIENT_ERROR_OTHER(31),
    LLAP_REGISTRY_ERROR(40),
    LLAP_JSON_GENERATION_ERROR(50),
    // Error in the script itself - likely caused by an incompatible change, or new functionality / states added.
    INTERNAL_ERROR(100);

    private final int exitCode;

    ExitCode(int exitCode) {
      this.exitCode = exitCode;
    }

    public int getInt() {
      return exitCode;
    }
  }


  private static void logError(Throwable t) {
    LOG.error("FAILED: " + t.getMessage(), t);
    System.err.println("FAILED: " + t.getMessage());
  }


  public static void main(String[] args) {
    LOG.info("LLAP status invoked with arguments = {}", args);
    int ret = ExitCode.SUCCESS.getInt();

    LlapStatusServiceDriver statusServiceDriver = null;
    LlapStatusOptions options = null;
    try {
      statusServiceDriver = new LlapStatusServiceDriver();
      options = statusServiceDriver.parseOptions(args);
    } catch (Throwable t) {
      logError(t);
      if (t instanceof LlapStatusCliException) {
        LlapStatusCliException ce = (LlapStatusCliException) t;
        ret = ce.getExitCode().getInt();
      } else {
        ret = ExitCode.INTERNAL_ERROR.getInt();
      }
    } finally {
      LOG.info("LLAP status finished");
    }
    if (ret != 0 || options == null) { // Failure / help
      System.exit(ret);
    }

    try {
      ret = statusServiceDriver.run(options);
      if (ret == ExitCode.SUCCESS.getInt()) {
        try (OutputStream os = options.getOutputFile() == null ? System.out :
            new BufferedOutputStream(
                new FileOutputStream(options.getOutputFile())); PrintWriter pw = new PrintWriter(
            os)) {
          statusServiceDriver.outputJson(pw);
        }
      }

    } catch (Throwable t) {
      logError(t);
      if (t instanceof LlapStatusCliException) {
        LlapStatusCliException ce = (LlapStatusCliException) t;
        ret = ce.getExitCode().getInt();
      } else {
        ret = ExitCode.INTERNAL_ERROR.getInt();
      }
    } finally {
      LOG.info("LLAP status finished");
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Completed processing - exiting with " + ret);
    }
    System.exit(ret);
  }
}
