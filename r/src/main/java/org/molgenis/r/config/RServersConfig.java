package org.molgenis.r.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "rserve")
@Component
public class RServersConfig {
  private final List<Node> nodes;

  public RServersConfig(List<Node> nodes) {
    this.nodes = nodes;
  }

  public List<Node> getNodes() {
    return nodes;
  }
}
