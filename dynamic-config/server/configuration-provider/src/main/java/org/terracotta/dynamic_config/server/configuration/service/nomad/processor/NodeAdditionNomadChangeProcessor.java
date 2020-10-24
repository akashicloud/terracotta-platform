/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.dynamic_config.server.configuration.service.nomad.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.dynamic_config.api.model.Cluster;
import org.terracotta.dynamic_config.api.model.Node;
import org.terracotta.dynamic_config.api.model.NodeContext;
import org.terracotta.dynamic_config.api.model.nomad.NodeAdditionNomadChange;
import org.terracotta.dynamic_config.api.service.ClusterValidator;
import org.terracotta.dynamic_config.api.service.TopologyService;
import org.terracotta.dynamic_config.server.api.DynamicConfigEventFiring;
import org.terracotta.dynamic_config.server.api.NomadChangeProcessor;
import org.terracotta.nomad.server.NomadException;

import javax.management.JMException;
import javax.management.MBeanServer;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.terracotta.server.ServerEnv;
import org.terracotta.server.ServerMBean;

/**
 * @author Mathieu Carbou
 */
public class NodeAdditionNomadChangeProcessor implements NomadChangeProcessor<NodeAdditionNomadChange> {
  private static final Logger LOGGER = LoggerFactory.getLogger(NodeAdditionNomadChangeProcessor.class);
  private static final String PLATFORM_MBEAN_OPERATION_NAME = "addPassive";

  private final TopologyService topologyService;
  private final DynamicConfigEventFiring dynamicConfigEventFiring;
  private final MBeanServer mbeanServer = ServerEnv.getServer().getManagement().getMBeanServer();
  private final ObjectName TOPOLOGY_MBEAN;

  public NodeAdditionNomadChangeProcessor(TopologyService topologyService, DynamicConfigEventFiring dynamicConfigEventFiring) {
    this.topologyService = requireNonNull(topologyService);
    this.dynamicConfigEventFiring = requireNonNull(dynamicConfigEventFiring);
    try {
      TOPOLOGY_MBEAN = ServerMBean.createMBeanName("TopologyMBean");
    } catch (MalformedObjectNameException mal) {
      throw new RuntimeException(mal);
    }
  }

  @Override
  public void validate(NodeContext baseConfig, NodeAdditionNomadChange change) throws NomadException {
    LOGGER.info("Validating change: {}", change.getSummary());
    if (baseConfig == null) {
      throw new NomadException("Existing config must not be null");
    }
    try {
      checkMBeanOperation();
      Cluster updated = change.apply(baseConfig.getCluster());
      new ClusterValidator(updated).validate();
    } catch (RuntimeException e) {
      throw new NomadException("Error when trying to apply: '" + change.getSummary() + "': " + e.getMessage(), e);
    }
  }

  @Override
  public final void apply(NodeAdditionNomadChange change) throws NomadException {
    Cluster runtime = topologyService.getRuntimeNodeContext().getCluster();
    if (runtime.containsNode(change.getNode().getUID())) {
      return;
    }

    try {
      Node node = change.getNode();
      LOGGER.info("Adding node: {} to stripe: {}", node.getName(), runtime.getStripe(change.getStripeUID()).get().getName());
      LOGGER.debug("Calling mBean {}#{}", TOPOLOGY_MBEAN, PLATFORM_MBEAN_OPERATION_NAME);
      mbeanServer.invoke(
          TOPOLOGY_MBEAN,
          PLATFORM_MBEAN_OPERATION_NAME,
          new Object[]{node.getHostname(), node.getPort().orDefault(), node.getGroupPort().orDefault()},
          new String[]{String.class.getName(), int.class.getName(), int.class.getName()}
      );

      dynamicConfigEventFiring.onNodeAddition(change.getStripeUID(), node);
    } catch (RuntimeException | JMException e) {
      throw new NomadException("Error when applying: '" + change.getSummary() + "': " + e.getMessage(), e);
    }
  }

  private void checkMBeanOperation() {
    boolean canCall;
    try {
      canCall = Stream
          .of(mbeanServer.getMBeanInfo(TOPOLOGY_MBEAN).getOperations())
          .anyMatch(attr -> PLATFORM_MBEAN_OPERATION_NAME.equals(attr.getName()));
    } catch (JMException e) {
      LOGGER.error("MBeanServer::getMBeanInfo resulted in:", e);
      canCall = false;
    }
    if (!canCall) {
      throw new IllegalStateException("Unable to invoke MBean operation to attach a node");
    }
  }
}
