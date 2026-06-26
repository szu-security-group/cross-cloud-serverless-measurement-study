package com.fchen_group.TPDSInScf.Run;

import com.alibaba.fastjson.JSON;
import com.fchen_group.TPDSInScf.Core.ChallengeData;
import com.fchen_group.TPDSInScf.Core.IntegrityAuditing;
import com.fchen_group.TPDSInScf.Core.ProofData;
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

    @FunctionName("Audit")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS)
            HttpRequestMessage<String> request,
            final ExecutionContext context) {

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

        long start_time_proof = System.nanoTime();
        ProofData proofData = integrityAuditing.prove(challengeData, downloadData, downloadParity);
        long end_time_proof = System.nanoTime();

        ResponseClass responseClass = new ResponseClass(proofData);
        responseClass.download_time = end_time_download - start_time_download;
        responseClass.proofTime = end_time_proof - start_time_proof;
        responseClass.instanceId = context.getInvocationId();

        String output = JSON.toJSONString(responseClass);
        context.getLogger().info("result:" + output);
        return output;
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
        long start = System.nanoTime();
        String result = fib(n);
        long elapsed = System.nanoTime() - start;
        ResponseClass resp = new ResponseClass(null);
        resp.download_time = 0L;
        resp.proofTime = elapsed;
        resp.proofData = null;
        resp.instanceId = context.getInvocationId();
        context.getLogger().info("fib(" + n + ") time=" + elapsed + " digits=" + result.length());
        return JSON.toJSONString(resp);
    }
}
