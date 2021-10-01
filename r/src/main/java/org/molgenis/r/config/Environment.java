package org.molgenis.r.config;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Positive;

public class Environment {
  @NotEmpty private String name;
  @NotEmpty private String host = "localhost";
  @Positive private int port = 6311;

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
