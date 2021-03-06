/*
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
package org.apache.beam.runners.dataflow.worker;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.apache.beam.model.pipeline.v1.Endpoints.ApiServiceDescriptor;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.runners.dataflow.options.DataflowWorkerHarnessOptions;
import org.apache.beam.runners.dataflow.worker.fn.BeamFnControlService;
import org.apache.beam.runners.dataflow.worker.fn.ServerFactory;
import org.apache.beam.runners.dataflow.worker.fn.data.BeamFnDataGrpcService;
import org.apache.beam.runners.dataflow.worker.fn.logging.BeamFnLoggingService;
import org.apache.beam.runners.dataflow.worker.fn.stream.ServerStreamObserverFactory;
import org.apache.beam.runners.dataflow.worker.logging.DataflowWorkerLoggingInitializer;
import org.apache.beam.runners.fnexecution.GrpcContextHeaderAccessorProvider;
import org.apache.beam.runners.fnexecution.control.FnApiControlClient;
import org.apache.beam.runners.fnexecution.state.GrpcStateService;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.vendor.grpc.v1_13_1.io.grpc.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the harness for executing Dataflow jobs that make use of the Beam Fn API, and operates
 * independently of the SDK(s) being used by users.
 *
 * <p>The worker harness is a mediator between Dataflow Service and the SDK, translating
 * instructions (such as map tasks) from the Dataflow Service/DFE into Fn API instructions, and vice
 * versa.
 */
public class DataflowRunnerHarness {
  private static final Logger LOG = LoggerFactory.getLogger(DataflowRunnerHarness.class);

  /** Fetches and processes work units from the Dataflow service. */
  public static void main(String[] unusedArgs) throws Exception {
    @Nullable RunnerApi.Pipeline pipeline = DataflowWorkerHarnessHelper.getPipelineFromEnv();

    // This descriptor is used for all services except logging. They are isolated to keep
    // critical traffic protected from best effort traffic.
    ApiServiceDescriptor controlApiService = DataflowWorkerHarnessHelper.getControlDescriptor();
    ApiServiceDescriptor loggingApiService = DataflowWorkerHarnessHelper.getLoggingDescriptor();

    LOG.info(
        "{} started, using port {} for control, {} for logging.",
        DataflowRunnerHarness.class,
        controlApiService,
        loggingApiService);

    DataflowWorkerHarnessHelper.initializeLogging(DataflowRunnerHarness.class);
    DataflowWorkerHarnessOptions pipelineOptions =
        DataflowWorkerHarnessHelper.initializeGlobalStateAndPipelineOptions(
            DataflowRunnerHarness.class);
    DataflowWorkerHarnessHelper.configureLogging(pipelineOptions);

    // Initialized registered file systems.˜
    FileSystems.setDefaultPipelineOptions(pipelineOptions);

    ServerFactory serverFactory = ServerFactory.fromOptions(pipelineOptions);
    ServerStreamObserverFactory streamObserverFactory =
        ServerStreamObserverFactory.fromOptions(pipelineOptions);

    Server servicesServer = null;
    Server loggingServer = null;
    try (BeamFnLoggingService beamFnLoggingService =
            new BeamFnLoggingService(
                loggingApiService,
                DataflowWorkerLoggingInitializer.getSdkLoggingHandler()::publish,
                streamObserverFactory::from,
                GrpcContextHeaderAccessorProvider.getHeaderAccessor());
        BeamFnControlService beamFnControlService =
            new BeamFnControlService(
                controlApiService,
                streamObserverFactory::from,
                GrpcContextHeaderAccessorProvider.getHeaderAccessor());
        BeamFnDataGrpcService beamFnDataService =
            new BeamFnDataGrpcService(
                pipelineOptions,
                controlApiService,
                streamObserverFactory::from,
                GrpcContextHeaderAccessorProvider.getHeaderAccessor());
        GrpcStateService beamFnStateService = GrpcStateService.create()) {

      servicesServer =
          serverFactory.create(
              controlApiService,
              ImmutableList.of(beamFnControlService, beamFnDataService, beamFnStateService));

      loggingServer =
          serverFactory.create(loggingApiService, ImmutableList.of(beamFnLoggingService));

      start(
          pipeline,
          pipelineOptions,
          beamFnControlService,
          beamFnDataService,
          controlApiService,
          beamFnStateService);
      servicesServer.shutdown();
      loggingServer.shutdown();
    } finally {
      if (servicesServer != null) {
        servicesServer.awaitTermination(30, TimeUnit.SECONDS);
        servicesServer.shutdownNow();
      }
      if (loggingServer != null) {
        loggingServer.awaitTermination(30, TimeUnit.SECONDS);
        loggingServer.shutdownNow();
      }
    }
  }

  @SuppressWarnings("InfiniteLoopStatement")
  public static void start(
      @Nullable RunnerApi.Pipeline pipeline,
      DataflowWorkerHarnessOptions pipelineOptions,
      BeamFnControlService beamFnControlService,
      BeamFnDataGrpcService beamFnDataService,
      ApiServiceDescriptor stateApiServiceDescriptor,
      GrpcStateService beamFnStateService)
      throws Exception {

    SdkHarnessRegistry sdkHarnessRegistry =
        SdkHarnessRegistries.createFnApiSdkHarnessRegistry(
            stateApiServiceDescriptor, beamFnStateService, beamFnDataService);
    if (pipelineOptions.isStreaming()) {
      DataflowWorkUnitClient client = new DataflowWorkUnitClient(pipelineOptions, LOG);
      LOG.info("Initializing Streaming Worker.");

      StreamingDataflowWorker worker =
          StreamingDataflowWorker.forStreamingFnWorkerHarness(
              Collections.emptyList(), client, pipelineOptions, pipeline, sdkHarnessRegistry);
      worker.startStatusPages();
      worker.start();
      ExecutorService executor = Executors.newSingleThreadExecutor();
      executor.execute(
          () -> { // Task to get new client connections
            while (true) {
              LOG.info("Waiting for client.");
              // Never close controlClient. It will be closed  when the client terminates the
              // connection.
              FnApiControlClient controlClient = beamFnControlService.get();
              LOG.info("Control client connected for {}", controlClient.getWorkerId());
              controlClient.onClose(sdkHarnessRegistry::unregisterWorkerClient);
              // register the client with worker once connected
              sdkHarnessRegistry.registerWorkerClient(controlClient);
            }
          });
      worker.waitTillExecutionFinishes();
      executor.shutdownNow();
    } else {
      while (true) {
        try (FnApiControlClient controlClient = beamFnControlService.get()) {
          DataflowWorkUnitClient client = new DataflowWorkUnitClient(pipelineOptions, LOG);
          LOG.info("Initializing Batch Worker.");
          BatchDataflowWorker worker =
              BatchDataflowWorker.forBatchFnWorkerHarness(
                  pipeline, sdkHarnessRegistry, client, pipelineOptions);
          // register the client with worker once connected
          sdkHarnessRegistry.registerWorkerClient(controlClient);
          LOG.info("Client connected.");
          try {
            while (true) {
              worker.getAndPerformWork();
            }
          } catch (IOException e) {
            LOG.error("Getting and performing work failed.", e);
          }
        }
      }
    }
  }
}
