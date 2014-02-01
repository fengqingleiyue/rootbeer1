package org.trifort.rootbeer.runtime;

import java.util.List;

public interface GpuContext {

  public void init();
  public void close();
  public GpuDevice getDevice();
  public List<StatsRow> getStats();
  
  public void run(Kernel template, ThreadConfig thread_config);
  public void run(List<Kernel> work, ThreadConfig thread_config);
}
