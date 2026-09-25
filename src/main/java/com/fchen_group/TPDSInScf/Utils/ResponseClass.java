package com.fchen_group.TPDSInScf.Utils;


import com.fchen_group.TPDSInScf.Core.ProofData;

public class ResponseClass {

    public Long download_time;
    public Long proofTime;
    public ProofData proofData;
    public String instanceId;

    // Milliseconds from JVM start to the moment this invocation entered the handler,
    // sampled inside the instance so it carries no client or network time. It is the
    // container's age at handler entry, which is the init cost only when the container
    // was built for this call. A platform that builds the new tier's container in the
    // background after a memory switch breaks that: the first call to land on it then
    // reports an age reaching back before the request, so the probe can exceed the
    // call's own Total_Time_ms. Treat it as a validity flag on that row, not as a value.
    public Long initMs;
    public Long allocatedMemoryMB;
    public Long memUsageMB;

    // Peak consumption sampled across the whole invocation. A single post-hoc read
    // misses the peak: at 32 threads the concurrent download phase lasts well under
    // a second, far below Azure Monitor's PT1M resolution.
    public Long totalPhysMB;        // instance RAM total, as seen from inside
    public Long peakPhysUsedMB;     // instance consumption peak = totalPhys - min(free)
    public Long peakHeapUsedMB;
    public Long peakNonHeapUsedMB;
    public Long peakDirectUsedMB;   // Netty/HTTP off-heap buffers
    public Long peakCommittedMB;    // heap + non-heap + direct committed
    public Integer peakThreads;
    public Integer samples;
    public Long sampleWindowMs;

    // The sandbox's own accounting for this instance. totalPhysMB/peakPhysUsedMB above come
    // from the host VM the sandbox shares with other tenants, so they cannot size one
    // instance; these fields read what the platform itself enforces the limit with - the
    // cgroup on Linux, the Job Object on Windows.
    public String memSource;            // job / cgroup-v2 / cgroup-v1 / "-"
    public String memSourceDetail;      // API or file the numbers came from
    public Long limitMB;                // -1 unknown, -2 unlimited
    public Long usedStartMB;            // instance usage when the invocation started
    public Long usedPeakMB;             // instance usage peak over the invocation
    public Long usedLastMB;             // instance usage when the invocation finished
    public Long procPeakWorkingSetMB;   // this JVM's own RSS peak (Windows only)
    public Long procPeakPrivateMB;      // this JVM's own commit peak (Windows only)
    public String procMemApi;           // export that answered for the two above, "-" if none

    // The platform's own memory counter for this instance. PDH is blocked in the sandbox, so
    // the platform re-exposes a subset as "fake" environment variables that answer only an
    // individual lookup and are recomputed per lookup; WEBSITE_COUNTERS_APP.privateBytes is
    // the instance's private commit (worker + the Functions host it shares the sandbox with),
    // which is what Azure Consumption bills on. Read repeatedly over the invocation so the
    // peak is per-invocation, not the instance's lifetime high-water mark.
    public String privSource;       // key the numbers came from, "-" if the counter did not answer
    public Long privStartMB;        // at invocation entry
    public Long privPeakMB;         // peak over the invocation — the mem_usage column
    public Long privLastMB;         // when the invocation finished
    public Long envMemLimitMB;      // WEBSITE_MEMORY_LIMIT_MB, the cap the platform declares



    public ResponseClass(ProofData proofData) {
        this.proofData=proofData;
    }



    public ResponseClass(){};
}
