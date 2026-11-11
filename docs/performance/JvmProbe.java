import com.sun.management.OperatingSystemMXBean;
import com.sun.tools.attach.VirtualMachine;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/** Temporary local observer; never loaded into the production application classpath. */
public class JvmProbe {
    public static void main(String[] args) throws Exception {
        var vm = VirtualMachine.attach(args[0]);
        String address;
        try {
            address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
                var out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            var server = connector.getMBeanServerConnection();
            var memory = ManagementFactory.newPlatformMXBeanProxy(server,
                    ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
            var threads = ManagementFactory.newPlatformMXBeanProxy(server,
                    ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
            var os = ManagementFactory.newPlatformMXBeanProxy(server,
                    ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBean.class);
            var gc = ManagementFactory.getPlatformMXBeans(server, GarbageCollectorMXBean.class);
            out.println("epoch_ms,heap_used_bytes,heap_committed_bytes,heap_max_bytes,"
                    + "process_cpu_ns,process_cpu_load,live_threads,gc_count,gc_ms");
            for (int i = 0; i < Integer.parseInt(args[2]); i++) {
                var heap = memory.getHeapMemoryUsage();
                long count = 0;
                long time = 0;
                for (var collector : gc) {
                    count += Math.max(0, collector.getCollectionCount());
                    time += Math.max(0, collector.getCollectionTime());
                }
                out.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%.6f,%d,%d,%d%n",
                        System.currentTimeMillis(), heap.getUsed(), heap.getCommitted(), heap.getMax(),
                        os.getProcessCpuTime(), os.getProcessCpuLoad(), threads.getThreadCount(), count, time);
                out.flush();
                Thread.sleep(1000);
            }
        }
    }
}
