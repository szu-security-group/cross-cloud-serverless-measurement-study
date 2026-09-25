package com.fchen_group.TPDSInScf.Run;

import com.alibaba.fastjson.JSON;
import com.fchen_group.TPDSInScf.Core.ChallengeData;
import com.fchen_group.TPDSInScf.Core.IntegrityAuditing;
import com.fchen_group.TPDSInScf.Core.ProofData;
import com.fchen_group.TPDSInScf.Utils.FibIter;
import com.fchen_group.TPDSInScf.Utils.ResponseClass;
import com.fchen_group.TPDSInScf.Utils.TenRequestClass;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Azure Functions handler — uses HTTP trigger.
 * Invoked via HTTP POST to /api/audit
 */
public class AzureHandle {

    /** Cold-start probe, refreshed at every invocation entry; see ResponseClass.initMs. */
    static long PROBE_INIT_MS = -1;

    @FunctionName("Audit")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS)
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

        PROBE_INIT_MS = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();

        context.getLogger().info("Azure audit function triggered.");

        String body = request.getBody();
        TenRequestClass req = JSON.parseObject(body, TenRequestClass.class);
        String result = processRequest(req, context);

        return request.createResponseBuilder(HttpStatus.OK)
                .body(result)
                .header("Content-Type", "application/json")
                .build();
    }

    private String processRequest(TenRequestClass request, ExecutionContext context) {
        if ("fib".equals(request.testType)) return handleFib(request, context);

        ChallengeData challengeData = request.challengeData;
        String bucketName = request.bucketName;
        String regionName = request.regionName;
        int DATA_SHARDS = request.DaTA_SHARDS;
        int PARITY_SHARDS = request.PARITY_SHARDS;
        String secretId = request.secretId; // Azure connection string
        int threadNum = request.threadNum;

        // Azure Blob Storage SDK via connection string
        com.azure.storage.blob.BlobServiceClient blobServiceClient =
                new com.azure.storage.blob.BlobServiceClientBuilder()
                        .connectionString(secretId)
                        .buildClient();
        com.azure.storage.blob.BlobContainerClient containerClient =
                blobServiceClient.getBlobContainerClient(bucketName);

        IntegrityAuditing integrityAuditing = new IntegrityAuditing(DATA_SHARDS, PARITY_SHARDS);
        byte[][] downloadData = new byte[challengeData.index.length][DATA_SHARDS];
        byte[][] downloadParity = new byte[challengeData.index.length][PARITY_SHARDS];

        long start_time_download;
        long end_time_download;
        long start_time_proof;
        long end_time_proof;
        ProofData proofData;

        MemSampler sampler = new MemSampler();
        sampler.start();

        try {
            if (threadNum != 1) {
                ExecutorService executor = Executors.newFixedThreadPool(threadNum);
                start_time_download = System.nanoTime();
                for (int i = 0; i < challengeData.index.length; i++) {
                    final int index = i;
                    executor.submit(() -> {
                        downloadData[index] = downloadBlobRange(containerClient,
                                "sourceFile.txt",
                                challengeData.index[index] * DATA_SHARDS,
                                DATA_SHARDS);
                        downloadParity[index] = downloadBlobRange(containerClient,
                                "parities.txt",
                                challengeData.index[index] * PARITY_SHARDS,
                                PARITY_SHARDS);
                    });
                }
                executor.shutdown();
                try {
                    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                end_time_download = System.nanoTime();
            } else {
                start_time_download = System.nanoTime();
                for (int i = 0; i < challengeData.index.length; i++) {
                    downloadData[i] = downloadBlobRange(containerClient,
                            "sourceFile.txt",
                            challengeData.index[i] * DATA_SHARDS,
                            DATA_SHARDS);
                    downloadParity[i] = downloadBlobRange(containerClient,
                            "parities.txt",
                            challengeData.index[i] * PARITY_SHARDS,
                            PARITY_SHARDS);
                }
                end_time_download = System.nanoTime();
            }

            start_time_proof = System.nanoTime();
            proofData = integrityAuditing.prove(challengeData, downloadData, downloadParity);
            end_time_proof = System.nanoTime();
        } finally {
            sampler.stop();
        }

        ResponseClass responseClass = new ResponseClass(proofData);
        responseClass.download_time = end_time_download - start_time_download;
        responseClass.proofTime = end_time_proof - start_time_proof;
        responseClass.initMs = PROBE_INIT_MS;
        String hostInstanceId = System.getenv("WEBSITE_INSTANCE_ID");
        responseClass.instanceId = hostInstanceId != null ? hostInstanceId : context.getInvocationId();
        responseClass.allocatedMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        Runtime rt = Runtime.getRuntime();
        responseClass.memUsageMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        responseClass.totalPhysMB = sampler.totalPhysMB;
        responseClass.peakPhysUsedMB = sampler.peakPhysUsedMB;
        responseClass.peakHeapUsedMB = sampler.peakHeapUsedMB;
        responseClass.peakNonHeapUsedMB = sampler.peakNonHeapUsedMB;
        responseClass.peakDirectUsedMB = sampler.peakDirectUsedMB;
        responseClass.peakCommittedMB = sampler.peakCommittedMB;
        responseClass.peakThreads = sampler.peakThreads;
        responseClass.samples = sampler.samples;
        responseClass.sampleWindowMs = sampler.windowMs;
        responseClass.memSource = sampler.probe.source;
        responseClass.memSourceDetail = sampler.probe.detail;
        responseClass.limitMB = sampler.probe.limitMB;
        responseClass.usedStartMB = sampler.probe.startUsedMB;
        responseClass.usedPeakMB = sampler.probe.peakUsedMB;
        responseClass.usedLastMB = sampler.probe.lastUsedMB;
        responseClass.procPeakWorkingSetMB = sampler.probe.procPeakWorkingSetMB;
        responseClass.procPeakPrivateMB = sampler.probe.procPeakPrivateMB;
        responseClass.procMemApi = sampler.probe.procApi;
        responseClass.privSource = sampler.probe.privSource;
        responseClass.privStartMB = sampler.probe.privStartMB;
        responseClass.privPeakMB = sampler.probe.privPeakMB;
        responseClass.privLastMB = sampler.probe.privLastMB;
        responseClass.envMemLimitMB = sampler.probe.sandbox.memLimitMB > 0
                ? sampler.probe.sandbox.memLimitMB : null;

        String output = JSON.toJSONString(responseClass);
        context.getLogger().info("result:" + output);
        return output;
    }

    /**
     * Polls memory quantities at high frequency for the duration of one invocation.
     * A single post-hoc read misses the peak: at 32 threads the concurrent download
     * phase lasts well under a second, far below Azure Monitor's PT1M resolution.
     * All values are MB.
     */
    static final class MemSampler {
        private static final long INTERVAL_MS = 25L;
        private static final long MAX_MS = 300_000L;  // safety net: never outlive a hung invocation

        private Thread thread;
        private volatile boolean running;

        long totalPhysMB = -1;
        long peakPhysUsedMB = -1;
        long peakHeapUsedMB;
        long peakNonHeapUsedMB;
        long peakDirectUsedMB;
        long peakCommittedMB;
        int peakThreads;
        int samples;
        long windowMs;
        final InstanceMemProbe probe = new InstanceMemProbe();

        void start() {
            running = true;
            probe.init();
            thread = new Thread(this::poll, "mem-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            try { thread.join(2000); } catch (InterruptedException ignore) { }
        }

        private void poll() {
            long t0 = System.currentTimeMillis();
            java.lang.management.MemoryMXBean mx =
                    java.lang.management.ManagementFactory.getMemoryMXBean();
            java.lang.management.ThreadMXBean tx =
                    java.lang.management.ManagementFactory.getThreadMXBean();
            java.lang.management.OperatingSystemMXBean osb =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            com.sun.management.OperatingSystemMXBean os =
                    (osb instanceof com.sun.management.OperatingSystemMXBean)
                            ? (com.sun.management.OperatingSystemMXBean) osb : null;
            try {
                while (running && System.currentTimeMillis() - t0 < MAX_MS) {
                    java.lang.management.MemoryUsage heap = mx.getHeapMemoryUsage();
                    java.lang.management.MemoryUsage nonHeap = mx.getNonHeapMemoryUsage();

                    long directUsed = 0, directComm = 0;
                    for (java.lang.management.BufferPoolMXBean bp : java.lang.management.ManagementFactory
                            .getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
                        directUsed += bp.getMemoryUsed();
                        directComm += bp.getTotalCapacity();
                    }

                    if (os != null) {
                        long total = os.getTotalPhysicalMemorySize();
                        long free = os.getFreePhysicalMemorySize();
                        if (total > 0) {
                            totalPhysMB = total / 1048576;
                            if (free >= 0) {
                                peakPhysUsedMB = Math.max(peakPhysUsedMB, (total - free) / 1048576);
                            }
                        }
                    }

                    peakHeapUsedMB = Math.max(peakHeapUsedMB, heap.getUsed() / 1048576);
                    peakNonHeapUsedMB = Math.max(peakNonHeapUsedMB, nonHeap.getUsed() / 1048576);
                    peakDirectUsedMB = Math.max(peakDirectUsedMB, directUsed / 1048576);
                    peakCommittedMB = Math.max(peakCommittedMB,
                            (heap.getCommitted() + nonHeap.getCommitted() + directComm) / 1048576);
                    peakThreads = Math.max(peakThreads, tx.getThreadCount());
                    probe.sample();
                    samples++;

                    Thread.sleep(INTERVAL_MS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                // sampling is best-effort; never fail an invocation over telemetry
            }
            windowMs = System.currentTimeMillis() - t0;
        }
    }

    /**
     * In-process instance-memory probe. Neither /proc/meminfo nor the JMX physical-memory
     * counters can be used to size one instance: both report the sandbox's shared host VM.
     * This reads the sandbox's own accounting instead — the cgroup on Linux, the Job Object
     * on Windows — which is what the platform uses to enforce the per-instance limit.
     */
    static final class InstanceMemProbe {
        String source = "-";        // job / cgroup-v2 / cgroup-v1 / "-"
        String detail = "-";        // API or file the numbers came from
        long limitMB = -1;          // -1 unknown, -2 unlimited
        long startUsedMB = -1;
        long peakUsedMB = -1;
        long lastUsedMB = -1;
        long procPeakWorkingSetMB = -1;   // this JVM's own RSS, Windows only
        long procPeakPrivateMB = -1;      // this JVM's own commit, Windows only
        String procApi = "-";             // export that answered for the two above
        long privStartMB = -1;            // instance private commit, from WEBSITE_COUNTERS_APP
        long privPeakMB = -1;
        long privLastMB = -1;
        String privSource = "-";

        // The counter is recomputed by the platform on every lookup, so it is polled well
        // below the sampler's own cadence rather than on every tick.
        private static final long SANDBOX_INTERVAL_MS = 200L;

        private final CgroupProbe cg = new CgroupProbe();
        private final WinProbe win = new WinProbe();
        final SandboxEnvProbe sandbox = new SandboxEnvProbe();
        private long nextSandboxMs;

        void init() {
            sandbox.init();
            nextSandboxMs = System.currentTimeMillis() + SANDBOX_INTERVAL_MS;
            if (win.onWindows()) {
                win.init();
                if (win.ok) {
                    source = "job";
                    detail = win.detail;
                    limitMB = win.limitMB;
                    startUsedMB = win.jobPeakUsedMB;
                    peakUsedMB = win.jobPeakUsedMB;
                    lastUsedMB = win.jobPeakUsedMB;
                    return;
                }
            }
            cg.init();
            if (cg.usageFile != null) {
                source = "cgroup-" + cg.version;
                detail = cg.limitSource;
                limitMB = cg.limitMB;
                startUsedMB = cg.startUsedMB;
                peakUsedMB = cg.peakUsedMB;
                lastUsedMB = cg.lastUsedMB;
            }
        }

        void sample() {
            long now = System.currentTimeMillis();
            if (now >= nextSandboxMs) {
                nextSandboxMs = now + SANDBOX_INTERVAL_MS;
                sandbox.sample();
            }
            privStartMB = sandbox.startPrivateMB;
            privPeakMB = sandbox.peakPrivateMB;
            privLastMB = sandbox.lastPrivateMB;
            privSource = sandbox.source;

            if (win.onWindows()) {
                win.sample();
                if (win.ok) {
                    // The job counter is a lifetime high-water mark, so it only moves when
                    // this invocation sets a new record for the instance. The working-set
                    // figures below are instantaneous, so their max is per-invocation.
                    peakUsedMB = Math.max(peakUsedMB, win.jobPeakUsedMB);
                    lastUsedMB = win.jobPeakUsedMB;
                    procPeakWorkingSetMB = Math.max(procPeakWorkingSetMB, win.workingSetMB);
                    procPeakPrivateMB = Math.max(procPeakPrivateMB, win.pagefileMB);
                    if (!"-".equals(win.procApi)) procApi = win.procApi;
                    return;
                }
            }
            cg.sample();
            if (cg.usageFile != null) {
                startUsedMB = cg.startUsedMB;
                peakUsedMB = cg.peakUsedMB;
                lastUsedMB = cg.lastUsedMB;
            }
        }
    }

    /**
     * Windows' counterpart to the cgroup. The App Service sandbox confines the worker to a
     * Job Object carrying the per-instance memory cap, and QueryInformationJobObject with a
     * NULL job handle asks about the job the calling process already belongs to.
     */
    static final class WinProbe {
        private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
        // x64 layouts. JOBOBJECT_EXTENDED_LIMIT_INFORMATION: JobMemoryLimit at 120,
        // PeakProcessMemoryUsed at 128, PeakJobMemoryUsed at 136. PROCESS_MEMORY_COUNTERS:
        // WorkingSetSize at 16, PagefileUsage at 56.
        private static final int JOB_INFO_BYTES = 144;
        private static final int PMC_BYTES = 72;

        boolean ok;
        String detail = "-";
        String procApi = "-";    // which export answered for workingSetMB/pagefileMB
        long limitMB = -1;
        long jobPeakUsedMB = -1;
        long workingSetMB = -1;
        long pagefileMB = -1;

        private Api api;
        private boolean loaded;

        static boolean onWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }

        void init() {
            if (!loaded) {
                loaded = true;
                try {
                    api = Api.INSTANCE;
                } catch (Throwable t) {
                    api = null;   // no JNA on the classpath, or not actually Windows
                }
            }
            sample();
            ok = api != null && (jobPeakUsedMB >= 0 || workingSetMB >= 0);
        }

        void sample() {
            if (api == null) return;
            try {
                com.sun.jna.Memory info = new com.sun.jna.Memory(JOB_INFO_BYTES);
                info.clear();
                if (api.QueryInformationJobObject(null, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                        info, JOB_INFO_BYTES, new com.sun.jna.ptr.IntByReference())) {
                    long limit = info.getLong(120);
                    if (limit >= Long.MAX_VALUE / 2) limitMB = -2;          // no cap set
                    else if (limit > 0) limitMB = limit / 1048576;
                    jobPeakUsedMB = Math.max(jobPeakUsedMB, info.getLong(136) / 1048576);
                    detail = "QueryInformationJobObject";
                }

                com.sun.jna.Memory pmc = new com.sun.jna.Memory(PMC_BYTES);
                pmc.clear();
                pmc.setInt(0, PMC_BYTES);
                // (HANDLE)-1 is GetCurrentProcess()'s pseudo-handle.
                boolean got = false;
                try {
                    got = api.K32GetProcessMemoryInfo(com.sun.jna.Pointer.createConstant(-1), pmc, PMC_BYTES);
                    if (got) procApi = "K32GetProcessMemoryInfo";
                } catch (Throwable ignore) { }
                if (!got) {
                    // This sandbox refused the kernel32 export. psapi's older copy of the same
                    // call is a distinct entry point, so it is worth asking separately.
                    try {
                        got = PsApi.INSTANCE.GetProcessMemoryInfo(
                                com.sun.jna.Pointer.createConstant(-1), pmc, PMC_BYTES);
                        if (got) procApi = "psapi!GetProcessMemoryInfo";
                    } catch (Throwable ignore) { }
                }
                if (got) {
                    workingSetMB = pmc.getLong(16) / 1048576;
                    pagefileMB = pmc.getLong(56) / 1048576;
                }
            } catch (Throwable t) {
                // sampling is best-effort; never fail an invocation over telemetry
            }
        }

        interface Api extends com.sun.jna.Library {
            Api INSTANCE = com.sun.jna.Native.load("kernel32", Api.class);

            boolean QueryInformationJobObject(com.sun.jna.Pointer hJob, int infoClass,
                                              com.sun.jna.Pointer lpInfo, int len,
                                              com.sun.jna.ptr.IntByReference retLen);

            boolean K32GetProcessMemoryInfo(com.sun.jna.Pointer hProcess,
                                            com.sun.jna.Pointer counters, int cb);
        }

        interface PsApi extends com.sun.jna.Library {
            PsApi INSTANCE = com.sun.jna.Native.load("psapi", PsApi.class);

            boolean GetProcessMemoryInfo(com.sun.jna.Pointer hProcess,
                                         com.sun.jna.Pointer counters, int cb);
        }
    }

    /**
     * The sandbox blocks PDH outright, so the platform re-exposes a subset of the perf
     * counters as "fake" environment variables: they are absent from an enumeration, only
     * an individual lookup answers, and the value is recomputed on every lookup.
     * azure-functions-host reads its own counters this way through the App Insights SDK.
     * Java's System.getenv cannot see them — it reads a snapshot taken at JVM start — so the
     * name has to go to kernel32 directly.
     *
     * WEBSITE_COUNTERS_APP.privateBytes is the instance's private commit, i.e. this worker
     * plus the Functions host it shares the sandbox with. That is the quantity Azure
     * Consumption bills on. Because the counter is recomputed per lookup rather than being a
     * lifetime high-water mark, its peak across one invocation is attributable to that
     * invocation — unlike the Job Object's PeakJobMemoryUsed.
     */
    static final class SandboxEnvProbe {
        private static final String COUNTERS_APP = "WEBSITE_COUNTERS_APP";
        private static final String MEM_LIMIT = "WEBSITE_MEMORY_LIMIT_MB";
        private static final String BYTES_KEY = "privateBytes";

        String detail = "-";         // API that answered
        long memLimitMB = -1;        // the platform's own declaration of the instance cap
        String source = "-";         // key the numbers came from
        long startPrivateMB = -1;
        long peakPrivateMB = -1;
        long lastPrivateMB = -1;

        private EnvApi api;
        private boolean loaded;

        void init() {
            if (!loaded) {
                loaded = true;
                try { api = EnvApi.INSTANCE; } catch (Throwable t) { api = null; }
            }
            if (api == null) return;
            detail = "kernel32!GetEnvironmentVariableW";
            String lim = read(MEM_LIMIT);
            if (lim != null) {
                try { memLimitMB = Long.parseLong(lim.trim()); } catch (NumberFormatException ignore) { }
            }
            sample();                // seed startPrivateMB at invocation entry
        }

        void sample() {
            if (api == null) return;
            String v = read(COUNTERS_APP);
            if (v == null) return;
            long bytes;
            try {
                bytes = JSON.parseObject(v).getLongValue(BYTES_KEY);
            } catch (Throwable t) {
                return;              // payload shape changed; leave the columns at -1
            }
            if (bytes <= 0) return;
            long mb = bytes / 1048576;
            if (startPrivateMB < 0) startPrivateMB = mb;
            peakPrivateMB = Math.max(peakPrivateMB, mb);
            lastPrivateMB = mb;
            source = COUNTERS_APP + "." + BYTES_KEY;
        }

        private String read(String name) {
            try {
                char[] buf = new char[8192];
                int n = api.GetEnvironmentVariableW(new com.sun.jna.WString(name), buf, buf.length);
                if (n == 0) return null;                 // not set
                if (n >= buf.length) {                   // longer than the probe buffer
                    char[] big = new char[n];
                    n = api.GetEnvironmentVariableW(new com.sun.jna.WString(name), big, big.length);
                    if (n == 0 || n > big.length) return null;
                    return new String(big, 0, n);
                }
                return new String(buf, 0, n);
            } catch (Throwable t) {
                return null;                             // sampling is best-effort
            }
        }

        interface EnvApi extends com.sun.jna.Library {
            EnvApi INSTANCE = com.sun.jna.Native.load("kernel32", EnvApi.class);

            int GetEnvironmentVariableW(com.sun.jna.WString lpName, char[] lpBuffer, int nSize);
        }
    }

    /**
     * Reads the sandbox's own cgroup, the Linux backend of {@link InstanceMemProbe}. azure-functions-host
     * does the same on Linux Consumption (LinuxContainerMetricsPublisher).
     */
    static final class CgroupProbe {
        String version = "-";       // v1 / v2 / "-" when neither is mounted
        String path = "-";          // memory path from /proc/self/cgroup
        String limitSource = "-";   // file the limit was read from
        long limitMB = -1;          // -1 unreadable, -2 unlimited
        long startUsedMB = -1;
        long peakUsedMB = -1;
        long lastUsedMB = -1;

        String usageFile;

        void init() {
            String rel = cgroupRelPath();
            path = rel.isEmpty() ? "/" : rel;
            // v2 unified first, then v1, then each controller's root-level fallback layout.
            String[][] candidates = {
                    {"/sys/fs/cgroup" + rel + "/memory.max",
                     "/sys/fs/cgroup" + rel + "/memory.current", "v2"},
                    {"/sys/fs/cgroup/memory.max",
                     "/sys/fs/cgroup/memory.current", "v2"},
                    {"/sys/fs/cgroup/memory" + rel + "/memory.limit_in_bytes",
                     "/sys/fs/cgroup/memory" + rel + "/memory.usage_in_bytes", "v1"},
                    {"/sys/fs/cgroup/memory/memory.limit_in_bytes",
                     "/sys/fs/cgroup/memory/memory.usage_in_bytes", "v1"},
            };
            for (String[] c : candidates) {
                long used = parseBytes(readText(c[1]));
                if (used < 0) continue;
                version = c[2];
                usageFile = c[1];
                String limit = readText(c[0]);
                if (limit != null) {
                    limitSource = c[0];
                    limitMB = parseLimit(limit);
                }
                long usedMB = used / 1048576;
                startUsedMB = usedMB;
                peakUsedMB = usedMB;
                lastUsedMB = usedMB;
                return;
            }
        }

        void sample() {
            if (usageFile == null) return;
            long used = parseBytes(readText(usageFile));
            if (used < 0) return;
            used /= 1048576;
            if (startUsedMB < 0) startUsedMB = used;
            peakUsedMB = Math.max(peakUsedMB, used);
            lastUsedMB = used;
        }

        /** Memory path from /proc/self/cgroup: "0::/x" (v2) or "N:memory:/x" (v1). */
        private static String cgroupRelPath() {
            String txt = readText("/proc/self/cgroup");
            if (txt == null) return "";
            String v2 = null, v1 = null;
            for (String line : txt.split("\n")) {
                String[] f = line.trim().split(":", 3);
                if (f.length < 3) continue;
                if ("0".equals(f[0]) && f[1].isEmpty()) {
                    if (v2 == null) v2 = f[2];
                } else if (f[1].contains("memory") && v1 == null) {
                    v1 = f[2];
                }
            }
            String p = v2 != null ? v2 : (v1 != null ? v1 : "");
            return p.isEmpty() || "/".equals(p) ? "" : p;
        }

        static String readText(String file) {
            try {
                byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file));
                return new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Throwable t) {
                return null;
            }
        }

        /** Bytes from a cgroup file, or -1 when unreadable/not a number. */
        private static long parseBytes(String s) {
            if (s == null) return -1;
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** Limit in MB, -2 for "max"/v1's unlimited sentinel, -1 when unparseable. */
        private static long parseLimit(String s) {
            String t = s.trim();
            if (t.isEmpty() || "max".equals(t)) return -2;
            long v = parseBytes(t);
            if (v < 0) return -1;
            if (v >= Long.MAX_VALUE / 2) return -2;   // v1 writes 9223372036854775807 for unlimited
            return v / 1048576;
        }
    }

    private byte[] downloadBlobRange(com.azure.storage.blob.BlobContainerClient containerClient,
                                     String blobName, long startPos, int length) {
        com.azure.storage.blob.BlobClient blobClient = containerClient.getBlobClient(blobName);
        com.azure.storage.blob.models.BlobRange range = new com.azure.storage.blob.models.BlobRange(
                startPos, (long) length);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        blobClient.downloadWithResponse(baos, range, null, null, false, null, null);
        return baos.toByteArray();
    }

    static String fib(int n) {
        if (n <= 1) return String.valueOf(n);
        return fibFast(n)[0].toString();
    }
    private static java.math.BigInteger[] fibFast(int n) {
        if (n == 0) return new java.math.BigInteger[]{java.math.BigInteger.ZERO, java.math.BigInteger.ONE};
        java.math.BigInteger[] half = fibFast(n >> 1);
        java.math.BigInteger a = half[0], b = half[1];
        java.math.BigInteger c = a.multiply(b.shiftLeft(1).subtract(a));
        java.math.BigInteger d = a.multiply(a).add(b.multiply(b));
        if ((n & 1) == 0) return new java.math.BigInteger[]{c, d};
        else              return new java.math.BigInteger[]{d, c.add(d)};
    }

    String handleFib(TenRequestClass request, ExecutionContext context) {
        int n = request.fibN > 0 ? request.fibN : 800000;
        boolean iter = "iter".equals(request.fibAlgo);
        long start = System.nanoTime();
        String result = iter ? FibIter.fib(n) : fib(n);
        long elapsed = System.nanoTime() - start;
        ResponseClass resp = new ResponseClass(null);
        resp.download_time = 0L;
        resp.proofTime = elapsed;
        resp.proofData = null;
        resp.initMs = PROBE_INIT_MS;
        String hostInstanceId = System.getenv("WEBSITE_INSTANCE_ID");
        resp.instanceId = hostInstanceId != null ? hostInstanceId : context.getInvocationId();
        context.getLogger().info("fib(" + n + ") algo=" + (iter ? "iter" : "fast") + " time=" + elapsed + " digits=" + result.length());
        return JSON.toJSONString(resp);
    }
}
