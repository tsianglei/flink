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

package org.apache.flink.kubernetes;

import org.apache.flink.client.deployment.ClusterDeploymentException;
import org.apache.flink.client.deployment.ClusterDescriptor;
import org.apache.flink.client.deployment.ClusterRetrieveException;
import org.apache.flink.client.deployment.ClusterSpecification;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.ClusterClientProvider;
import org.apache.flink.client.program.rest.RestClusterClient;
import org.apache.flink.configuration.BlobServerOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptionsInternal;
import org.apache.flink.kubernetes.entrypoint.KubernetesSessionClusterEntrypoint;
import org.apache.flink.kubernetes.kubeclient.Endpoint;
import org.apache.flink.kubernetes.kubeclient.FlinkKubeClient;
import org.apache.flink.kubernetes.kubeclient.resources.KubernetesService;
import org.apache.flink.kubernetes.utils.Constants;
import org.apache.flink.kubernetes.utils.KubernetesUtils;
import org.apache.flink.runtime.entrypoint.ClusterEntrypoint;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.util.FlinkException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Kubernetes specific {@link ClusterDescriptor} implementation.
 */
public class KubernetesClusterDescriptor implements ClusterDescriptor<String> {

	private static final Logger LOG = LoggerFactory.getLogger(KubernetesClusterDescriptor.class);

	private static final String CLUSTER_DESCRIPTION = "Kubernetes cluster";

	private final Configuration flinkConfig;

	private final FlinkKubeClient client;

	private final String clusterId;

	public KubernetesClusterDescriptor(Configuration flinkConfig, FlinkKubeClient client) {
		this.flinkConfig = flinkConfig;
		this.client = client;
		this.clusterId = checkNotNull(
			flinkConfig.getString(KubernetesConfigOptions.CLUSTER_ID),
			"ClusterId must be specified!");
	}

	@Override
	public String getClusterDescription() {
		return CLUSTER_DESCRIPTION;
	}

	private ClusterClientProvider<String> createClusterClientProvider(String clusterId) {
		return () -> {
			final Configuration configuration = new Configuration(flinkConfig);

			final Endpoint restEndpoint = client.getRestEndpoint(clusterId);

			if (restEndpoint != null) {
				configuration.setString(RestOptions.ADDRESS, restEndpoint.getAddress());
				configuration.setInteger(RestOptions.PORT, restEndpoint.getPort());
			} else {
				throw new RuntimeException(
						new ClusterRetrieveException(
								"Could not get the rest endpoint of " + clusterId));
			}

			try {

				RestClusterClient<String> resultClient = new RestClusterClient<>(
						configuration, clusterId);
				LOG.info(
						"Succesfully retrieved cluster client for cluster {}, JobManager Web Interface : {}",
						clusterId,
						resultClient.getWebInterfaceURL());
				return resultClient;
			} catch (Exception e) {
				client.handleException(e);
				throw new RuntimeException(new ClusterRetrieveException("Could not create the RestClusterClient.", e));
			}
		};
	}

	@Override
	public ClusterClientProvider<String> retrieve(String clusterId) {
		return createClusterClientProvider(clusterId);
	}

	@Override
	public ClusterClientProvider<String> deploySessionCluster(ClusterSpecification clusterSpecification) throws ClusterDeploymentException {
		final ClusterClientProvider<String> clusterClient = deployClusterInternal(
			KubernetesSessionClusterEntrypoint.class.getName(),
			clusterSpecification,
			false);

		return clusterClient;
	}

	@Override
	public ClusterClientProvider<String> deployJobCluster(
			ClusterSpecification clusterSpecification,
			JobGraph jobGraph,
			boolean detached) throws ClusterDeploymentException {
		throw new ClusterDeploymentException("Per job could not be supported now.");
	}

	private ClusterClientProvider<String> deployClusterInternal(
			String entryPoint,
			ClusterSpecification clusterSpecification,
			boolean detached) throws ClusterDeploymentException {
		final ClusterEntrypoint.ExecutionMode executionMode = detached ?
			ClusterEntrypoint.ExecutionMode.DETACHED
			: ClusterEntrypoint.ExecutionMode.NORMAL;
		flinkConfig.setString(ClusterEntrypoint.EXECUTION_MODE, executionMode.toString());

		flinkConfig.setString(KubernetesConfigOptionsInternal.ENTRY_POINT_CLASS, entryPoint);

		// Rpc, blob, rest, taskManagerRpc ports need to be exposed, so update them to fixed values.
		if (KubernetesUtils.parsePort(flinkConfig, BlobServerOptions.PORT) == 0) {
			flinkConfig.setString(BlobServerOptions.PORT, String.valueOf(Constants.BLOB_SERVER_PORT));
			LOG.warn(
				"Kubernetes service requires a fixed port. Configuration {} will be set to {}",
				BlobServerOptions.PORT.key(),
				Constants.BLOB_SERVER_PORT);
		}

		if (KubernetesUtils.parsePort(flinkConfig, TaskManagerOptions.RPC_PORT) == 0) {
			flinkConfig.setString(TaskManagerOptions.RPC_PORT, String.valueOf(Constants.TASK_MANAGER_RPC_PORT));
			LOG.warn(
				"Kubernetes service requires a fixed port. Configuration {} will be set to {}",
				TaskManagerOptions.RPC_PORT.key(),
				Constants.TASK_MANAGER_RPC_PORT);
		}

		// Set jobmanager address to namespaced service name
		final String nameSpace = flinkConfig.getString(KubernetesConfigOptions.NAMESPACE);
		flinkConfig.setString(JobManagerOptions.ADDRESS, clusterId + "." + nameSpace);

		try {
			final KubernetesService internalSvc = client.createInternalService(clusterId).get();
			// Update the service id in Flink config, it will be used for gc.
			final String serviceId = internalSvc.getInternalResource().getMetadata().getUid();
			if (serviceId != null) {
				flinkConfig.setString(KubernetesConfigOptionsInternal.SERVICE_ID, serviceId);
			} else {
				throw new ClusterDeploymentException("Get service id failed.");
			}

			// Create the rest service when exposed type is not ClusterIp.
			final String restSvcExposedType = flinkConfig.getString(KubernetesConfigOptions.REST_SERVICE_EXPOSED_TYPE);
			if (!restSvcExposedType.equals(KubernetesConfigOptions.ServiceExposedType.ClusterIP.toString())) {
				client.createRestService(clusterId).get();
			}

			client.createConfigMap();
			client.createFlinkMasterDeployment(clusterSpecification);

			ClusterClientProvider<String> clusterClientProvider = createClusterClientProvider(clusterId);

			try (ClusterClient<String> clusterClient = clusterClientProvider.getClusterClient()) {
				LOG.info(
						"Create flink session cluster {} successfully, JobManager Web Interface: {}",
						clusterId,
						clusterClient.getWebInterfaceURL());
			}

			return clusterClientProvider;
		} catch (Exception e) {
			client.handleException(e);
			throw new ClusterDeploymentException("Could not create Kubernetes cluster " + clusterId, e);
		}
	}

	@Override
	public void killCluster(String clusterId) throws FlinkException {
		try {
			client.stopAndCleanupCluster(clusterId);
		} catch (Exception e) {
			client.handleException(e);
			throw new FlinkException("Could not kill Kubernetes cluster " + clusterId);
		}
	}

	@Override
	public void close() {
		try {
			client.close();
		} catch (Exception e) {
			client.handleException(e);
			LOG.error("failed to close client, exception {}", e.toString());
		}
	}
}
