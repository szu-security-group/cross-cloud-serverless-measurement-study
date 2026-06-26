package com.fchen_group.TPDSInScf.Run;

import com.alibaba.fastjson.JSON;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.fchen_group.TPDSInScf.Core.ChallengeData;
import com.fchen_group.TPDSInScf.Core.IntegrityAuditing;
import com.fchen_group.TPDSInScf.Core.ProofData;
import com.fchen_group.TPDSInScf.Utils.AliCloudAPI;
import com.fchen_group.TPDSInScf.Utils.ResponseClass;
import com.fchen_group.TPDSInScf.Utils.TenRequestClass;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Alibaba Cloud FC handler — mirrors TenHandle.java
 * Runs inside Alibaba Function Compute, downloads from OSS, generates proof.
 *
 * For Alibaba FC custom runtime:
 *   Event comes via stdin → parse → process → write result to stdout.
 */
public class AliHandle {

    public String handleRequest(TenRequestClass request) {
        if ("fib".equals(request.testType)) return handleFib(request);

        ChallengeData challengeData = request.challengeData;
        String bucketName = request.bucketName;
        String regionName = request.regionName;
        int DATA_SHARDS = request.DaTA_SHARDS;
        int PARITY_SHARDS = request.PARITY_SHARDS;
        String secretId = request.secretId;
        String secretKey = request.secretKey;
        int threadNum = request.threadNum;

        // For s3.csv: download from unified S3 bucket instead of native OSS
        if ("s3".equals(request.storageType)) {
            return handleAuditS3(request, challengeData, DATA_SHARDS, PARITY_SHARDS, threadNum);
        }

        AliCloudAPI aliAPI = new AliCloudAPI(secretId, secretKey, regionName, bucketName);

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
                    downloadData[index] = aliAPI.downloadPartFile(
                            "sourceFile.txt",
                            challengeData.index[index] * DATA_SHARDS,
                            DATA_SHARDS);
                    downloadParity[index] = aliAPI.downloadPartFile(
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
            System.out.println("download from OSS successfully (multi-thread)");
        } else {
            start_time_download = System.nanoTime();
            for (int i = 0; i < challengeData.index.length; i++) {
                downloadData[i] = aliAPI.downloadPartFile(
                        "sourceFile.txt",
                        challengeData.index[i] * DATA_SHARDS,
                        DATA_SHARDS);
                downloadParity[i] = aliAPI.downloadPartFile(
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
        try { responseClass.instanceId = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) {}

        String output = JSON.toJSONString(responseClass);
        System.out.println("result:" + output);
        return output;
    }

    /**
     * Entry point for Alibaba FC custom runtime.
     * Uses HTTP server mode: listens on FC_SERVER_PORT (default 9000).
     * Health check: GET / → 200 OK
     * Invocation: POST / → JSON request body → handleRequest → JSON response
     * Also supports Runtime API polling as fallback.
     */
    public static void main(String[] args) {
        // Check if HTTP server mode (port-based)
        int port = 9000;
        String portEnv = System.getenv("FC_SERVER_PORT");
        if (portEnv == null || portEnv.isEmpty()) {
            portEnv = System.getenv("PORT");
        }
        if (portEnv != null && !portEnv.isEmpty()) {
            port = Integer.parseInt(portEnv);
        }

        AliHandle handler = new AliHandle();

        try {
            com.sun.net.httpserver.HttpServer server =
                    com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 0);
            server.createContext("/", exchange -> {
                String method = exchange.getRequestMethod();
                if ("GET".equals(method)) {
                    // Health check
                    String resp = "OK";
                    exchange.sendResponseHeaders(200, resp.length());
                    exchange.getResponseBody().write(resp.getBytes());
                } else if ("POST".equals(method)) {
                    // Invocation: read request body, process, return result
                    java.io.InputStream bodyStream = exchange.getRequestBody();
                    java.util.Scanner scanner = new java.util.Scanner(bodyStream, "UTF-8").useDelimiter("\\A");
                    String input = scanner.hasNext() ? scanner.next() : "";
                    scanner.close();
                    System.out.println("Received event: " + input);

                    TenRequestClass request = JSON.parseObject(input, TenRequestClass.class);
                    String result = handler.handleRequest(request);

                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, result.getBytes("UTF-8").length);
                    exchange.getResponseBody().write(result.getBytes("UTF-8"));
                } else {
                    exchange.sendResponseHeaders(405, 0);
                }
                exchange.close();
            });
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            System.out.println("HTTP server started on port " + port);
        } catch (Exception e) {
            System.out.println("Failed to start HTTP server: " + e.getMessage());
            e.printStackTrace();
        }

        // Also try Runtime API polling as fallback
        String runtimeApi = System.getenv("FC_RUNTIME_API");
        if (runtimeApi != null && !runtimeApi.isEmpty()) {
            System.out.println("FC_RUNTIME_API available, starting polling as fallback");
            pollRuntimeApi(runtimeApi, handler);
        } else {
            // Keep main thread alive
            while (true) {
                try { Thread.sleep(60000); } catch (InterruptedException e) { break; }
            }
        }
    }

    private static void pollRuntimeApi(String runtimeApi, AliHandle handler) {
        String baseUrl = "http://" + runtimeApi + "/2016-08-15/runtime/invocation";
        while (true) {
            try {
                java.net.URL getUrl = new java.net.URL(baseUrl + "/next");
                java.net.HttpURLConnection getConn = (java.net.HttpURLConnection) getUrl.openConnection();
                getConn.setRequestMethod("GET");
                getConn.setConnectTimeout(5000);
                getConn.setReadTimeout(600000);

                int statusCode = getConn.getResponseCode();
                String requestId = getConn.getHeaderField("x-fc-request-id");

                java.io.InputStream eventStream = getConn.getInputStream();
                java.util.Scanner scanner = new java.util.Scanner(eventStream, "UTF-8").useDelimiter("\\A");
                String input = scanner.hasNext() ? scanner.next() : "";
                scanner.close();
                System.out.println("Received event via Runtime API: " + input);

                TenRequestClass request = JSON.parseObject(input, TenRequestClass.class);
                String result = handler.handleRequest(request);

                java.net.URL postUrl = new java.net.URL(baseUrl + "/" + requestId + "/response");
                java.net.HttpURLConnection postConn = (java.net.HttpURLConnection) postUrl.openConnection();
                postConn.setRequestMethod("POST");
                postConn.setDoOutput(true);
                postConn.setRequestProperty("Content-Type", "application/json");
                postConn.getOutputStream().write(result.getBytes("UTF-8"));
                postConn.getOutputStream().close();
                postConn.getResponseCode();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Fallback for local testing: read from stdin, write to stdout */
    private static void stdinMode() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String input = sb.toString();
            System.out.println("Received event: " + input);

            TenRequestClass request = JSON.parseObject(input, TenRequestClass.class);
            AliHandle handler = new AliHandle();
            String result = handler.handleRequest(request);

            System.out.println(result);
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    String handleFib(TenRequestClass request) {
        int n = request.fibN > 0 ? request.fibN : 800000;
        long start = System.nanoTime();
        String result = fib(n);
        long elapsed = System.nanoTime() - start;
        ResponseClass resp = new ResponseClass(null);
        resp.download_time = 0L;
        resp.proofTime = elapsed;
        resp.proofData = null;
        try { resp.instanceId = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) {}
        System.out.println("fib(" + n + ") time=" + elapsed + " digits=" + result.length());
        return JSON.toJSONString(resp);
    }

    String handleAuditS3(TenRequestClass request, ChallengeData challengeData, int DATA_SHARDS, int PARITY_SHARDS, int threadNum) {
        AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
            .withRegion(request.regionName)
            .withCredentials(new AWSStaticCredentialsProvider(
                new BasicAWSCredentials(request.secretId, request.secretKey)))
            .build();
        String bucketName = request.bucketName;

        IntegrityAuditing integrityAuditing = new IntegrityAuditing(DATA_SHARDS, PARITY_SHARDS);
        byte[][] downloadData = new byte[challengeData.index.length][DATA_SHARDS];
        byte[][] downloadParity = new byte[challengeData.index.length][PARITY_SHARDS];
        long startDownload, endDownload;

        if (threadNum != 1) {
            ExecutorService executor = Executors.newFixedThreadPool(threadNum);
            startDownload = System.nanoTime();
            for (int i = 0; i < challengeData.index.length; i++) {
                final int idx = i;
                executor.submit(() -> {
                    downloadData[idx] = s3RangeRead(s3Client, bucketName, "sourceFile.txt", challengeData.index[idx] * DATA_SHARDS, DATA_SHARDS);
                    downloadParity[idx] = s3RangeRead(s3Client, bucketName, "parities.txt", challengeData.index[idx] * PARITY_SHARDS, PARITY_SHARDS);
                });
            }
            executor.shutdown();
            try { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
            catch (InterruptedException e) { e.printStackTrace(); }
            endDownload = System.nanoTime();
        } else {
            startDownload = System.nanoTime();
            for (int i = 0; i < challengeData.index.length; i++) {
                downloadData[i] = s3RangeRead(s3Client, bucketName, "sourceFile.txt", challengeData.index[i] * DATA_SHARDS, DATA_SHARDS);
                downloadParity[i] = s3RangeRead(s3Client, bucketName, "parities.txt", challengeData.index[i] * PARITY_SHARDS, PARITY_SHARDS);
            }
            endDownload = System.nanoTime();
        }

        long startProof = System.nanoTime();
        ProofData proofData = integrityAuditing.prove(challengeData, downloadData, downloadParity);
        long endProof = System.nanoTime();

        ResponseClass resp = new ResponseClass(proofData);
        resp.download_time = endDownload - startDownload;
        resp.proofTime = endProof - startProof;
        try { resp.instanceId = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) {}
        return JSON.toJSONString(resp);
    }

    byte[] s3RangeRead(AmazonS3 s3, String bucket, String key, long start, int len) {
        GetObjectRequest req = new GetObjectRequest(bucket, key);
        req.setRange(start, start + len - 1);
        S3Object obj = s3.getObject(req);
        byte[] buf = new byte[len];
        try (InputStream in = obj.getObjectContent()) {
            int off = 0;
            while (off < len) { int n = in.read(buf, off, len - off); if (n == -1) break; off += n; }
        } catch (IOException e) { e.printStackTrace(); }
        return buf;
    }
}

