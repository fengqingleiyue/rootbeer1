package org.trifort.rootbeer.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.trifort.rootbeer.configuration.Configuration;
import org.trifort.rootbeer.runtimegpu.GpuException;
import org.trifort.rootbeer.util.ResourceReader;

public class CUDAContext implements Context {

  private GpuDevice m_device;
  private List<StatsRow> m_stats;
  private boolean m_32bit;
  private Map<String, byte[]> m_cubinFiles;
  
  private Memory m_objectMemory;
  private Memory m_textureMemory;
  private Memory m_handlesMemory;
  private Memory m_exceptionsMemory;
  private Memory m_classMemory;
  
  private Map<Kernel, Long> m_handles;
  
  public CUDAContext(GpuDevice device){
    m_device = device;    
    
    String arch = System.getProperty("os.arch");
    m_32bit = arch.equals("x86") || arch.equals("i386");
    
    m_cubinFiles = new HashMap<String, byte[]>();
    m_handles = new HashMap<Kernel, Long>();

    m_textureMemory = new FixedMemory(64);
  }

  @Override
  public void init(int memory_size) {
    m_objectMemory = new FixedMemory(memory_size);
  }

  @Override
  public void init() {
    long free_mem_size = m_device.getFreeGlobalMemoryBytes();
    free_mem_size -= 64 * 1024 * 1024;
    free_mem_size -= m_handlesMemory.getSize();
    free_mem_size -= m_exceptionsMemory.getSize();
    free_mem_size -= m_classMemory.getSize();
    m_objectMemory = new FixedMemory(free_mem_size);
  }
  
  @Override
  public void close() {
    m_objectMemory.close();
    m_objectMemory = null;
    m_handlesMemory.close();
    m_exceptionsMemory.close();
    m_classMemory.close();
  }
  
  public List<StatsRow> getStats(){
    return m_stats;
  }
  
  @Override
  public GpuDevice getDevice() {
    return m_device;
  }

  @Override
  public void run(Kernel template, ThreadConfig thread_config) {
    CompiledKernel compiled_kernel = (CompiledKernel) template;
    
    String filename;
    if(m_32bit){
      filename = compiled_kernel.getCubin32();
    } else {
      filename = compiled_kernel.getCubin64();
    }
    
    if(filename.endsWith(".error")){
      throw new RuntimeException("CUDA code compiled with error");
    }
    
    byte[] cubin_file;
    
    if(m_cubinFiles.containsKey(filename)){
      cubin_file = m_cubinFiles.get(filename);
    } else {
      cubin_file = readCubinFile(filename);
      m_cubinFiles.put(filename, cubin_file);
    }
    
    m_handlesMemory = new FixedMemory(8);
    m_exceptionsMemory = new FixedMemory(8);
    m_classMemory = new FixedMemory(1024);
    if(m_objectMemory == null){
      init();
    }
    
    writeBlocksTemplate(compiled_kernel);
    runBlocks(thread_config, cubin_file, compiled_kernel.getNewUsed());
    readBlocksTemplate(compiled_kernel, thread_config);
  }

  @Override
  public void run(List<Kernel> work, ThreadConfig thread_config) {
    CompiledKernel compiled_kernel = (CompiledKernel) work.get(0);
    
    String filename;
    if(m_32bit){
      filename = compiled_kernel.getCubin32();
    } else {
      filename = compiled_kernel.getCubin64();
    }
    
    if(filename.endsWith(".error")){
      throw new RuntimeException("CUDA code compiled with error");
    }
    
    byte[] cubin_file;
    
    if(m_cubinFiles.containsKey(filename)){
      cubin_file = m_cubinFiles.get(filename);
    } else {
      cubin_file = readCubinFile(filename);
      m_cubinFiles.put(filename, cubin_file);
    }
    
    m_handlesMemory = new FixedMemory(8*work.size());
    m_exceptionsMemory = new FixedMemory(8*work.size());
    m_classMemory = new FixedMemory(1024);
    if(m_objectMemory == null){
      init();
    }
    
    writeBlocks(work);
    runBlocks(thread_config, cubin_file, compiled_kernel.getNewUsed());
    readBlocks(work);
  }
  
  private void writeBlocks(List<Kernel> work){
    m_objectMemory.clearHeapEndPtr();
    m_handlesMemory.clearHeapEndPtr();
    m_handles.clear();
    
    CompiledKernel compiled_kernel = (CompiledKernel) work.get(0);
    Serializer serializer = compiled_kernel.getSerializer(m_objectMemory, m_textureMemory);
    serializer.writeStaticsToHeap();
    
    for(Kernel kernel : work){
      long handle = serializer.writeToHeap(compiled_kernel);
      m_handlesMemory.writeRef(handle);
      m_handles.put(kernel, handle);
    }
    
    if(Configuration.getPrintMem()){
      BufferPrinter printer = new BufferPrinter();
      printer.print(m_objectMemory, 0, 896);
    }
  }
  
  private void readBlocks(List<Kernel> work){
    m_objectMemory.setAddress(0);
    m_handlesMemory.setAddress(0);
    m_exceptionsMemory.setAddress(0);
    
    CompiledKernel compiled_kernel = (CompiledKernel) work.get(0);
    Serializer serializer = compiled_kernel.getSerializer(m_objectMemory, m_textureMemory);
    
    for(int i = 0; i < work.size(); ++i){
      long ref = m_exceptionsMemory.readRef();
      if(ref != 0){
        long ref_num = ref >> 4;
        if(ref_num == compiled_kernel.getNullPointerNumber()){
          throw new NullPointerException(); 
        } else if(ref_num == compiled_kernel.getOutOfMemoryNumber()){
          throw new OutOfMemoryError();
        }
        
        m_objectMemory.setAddress(ref);           
        Object except = serializer.readFromHeap(null, true, ref);
        if(except instanceof Error){
          Error except_th = (Error) except;
          throw except_th;
        } else if(except instanceof GpuException){
          GpuException gpu_except = (GpuException) except;
          gpu_except.throwArrayOutOfBounds();
        } else {
          throw new RuntimeException((Throwable) except);
        }
      }
    }
    
    serializer.readStaticsFromHeap();
    for(Kernel kernel : work){
      long handle = m_handles.get(kernel);
      serializer.readFromHeap(kernel, true, handle);
    }
    
    if(Configuration.getPrintMem()){
      BufferPrinter printer = new BufferPrinter();
      printer.print(m_objectMemory, 0, 896);
    }
  }
  
  private void runBlocks(ThreadConfig thread_config, byte[] cubin_file, 
    boolean new_used){
    
    cudaRun(m_device.getDeviceId(), cubin_file, cubin_file.length, 
      thread_config.getBlockShapeX(), thread_config.getGridShapeX(), 
      thread_config.getNumThreads(), m_objectMemory, m_handlesMemory,
      m_exceptionsMemory, m_classMemory, new_used);
  }  
  
  private void writeBlocksTemplate(CompiledKernel compiled_kernel){
    m_objectMemory.clearHeapEndPtr();
    m_handlesMemory.clearHeapEndPtr();
    
    Serializer serializer = compiled_kernel.getSerializer(m_objectMemory, m_textureMemory);
    serializer.writeStaticsToHeap();
    long handle = serializer.writeToHeap(compiled_kernel);
    m_handlesMemory.writeRef(handle);
    
    if(Configuration.getPrintMem()){
      BufferPrinter printer = new BufferPrinter();
      printer.print(m_objectMemory, 0, 896);
    }
  }
  
  private void readBlocksTemplate(CompiledKernel compiled_kernel, ThreadConfig thread_config){
    m_handlesMemory.setAddress(0);
    m_exceptionsMemory.setAddress(0);
    m_exceptionsMemory.setAddress(0);
    
    Serializer serializer = compiled_kernel.getSerializer(m_objectMemory, m_textureMemory);
    
    for(int i = 0; i < thread_config.getNumThreads(); ++i){
      long ref = m_exceptionsMemory.readRef();
      if(ref != 0){
        long ref_num = ref >> 4;
        if(ref_num == compiled_kernel.getNullPointerNumber()){
          throw new NullPointerException(); 
        } else if(ref_num == compiled_kernel.getOutOfMemoryNumber()){
          throw new OutOfMemoryError();
        }
        
        m_objectMemory.setAddress(ref);           
        Object except = serializer.readFromHeap(null, true, ref);
        if(except instanceof Error){
          Error except_th = (Error) except;
          throw except_th;
        } else if(except instanceof GpuException){
          GpuException gpu_except = (GpuException) except;
          gpu_except.throwArrayOutOfBounds();
        } else {
          throw new RuntimeException((Throwable) except);
        }
      }
    }    
    
    serializer.readStaticsFromHeap();
    serializer.readFromHeap(compiled_kernel, true, m_handlesMemory.readRef());
    
    if(Configuration.getPrintMem()){
      BufferPrinter printer = new BufferPrinter();
      printer.print(m_objectMemory, 0, 896);
    }
  }

  private byte[] readCubinFile(String filename) {
    try {
      List<byte[]> buffer = ResourceReader.getResourceArray(filename);
      int total_len = 0;
      for(byte[] sub_buffer : buffer){
        total_len += sub_buffer.length;
      }
      byte[] cubin_file = new byte[total_len];
      int pos = 0;
      for(byte[] small_buffer : buffer){
        System.arraycopy(small_buffer, 0, cubin_file, pos, small_buffer.length);
        pos += small_buffer.length;
      }
      return cubin_file;
    } catch(Exception ex){
      throw new RuntimeException(ex);
    }
  }
  
  private native void cudaRun(int device_index, byte[] cubin_file, int cubin_length,
    int block_shape_x, int grid_shape_x, int num_threads, Memory object_mem,
    Memory handles_mem, Memory exceptions_mem, Memory class_mem, boolean new_used);
}