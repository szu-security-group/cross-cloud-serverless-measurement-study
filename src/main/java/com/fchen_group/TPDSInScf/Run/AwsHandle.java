package com.fchen_group.TPDSInScf.Run;

import com.alibaba.fastjson.JSON;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.fchen_group.TPDSInScf.Core.ChallengeData;
import com.fchen_group.TPDSInScf.Core.IntegrityAuditing;
import com.fchen_group.TPDSInScf.Core.ProofData;
import com.fchen_group.TPDSInScf.Utils.ResponseClass;
import com.fchen_group.TPDSInScf.Utils.TenRequestClass;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AWS Lambda handler — implements RequestStreamHandler for raw I/O control.
 * Runs inside AWS Lambda, downloads from S3, generates proof.
 */
public class AwsHandle implements RequestStreamHandler {

    /**
     * AWS Lambda entry point. Reads JSON request from input stream,
     * writes JSON response to output stream.
     */
    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        // Read input stream
        java.util.Scanner scanner = new java.util.Scanner(input, "UTF-8").useDelimiter("\\A");
        String inputStr = scanner.hasNext() ? scanner.next() : "";
        scanner.close();
        System.out.println("Received event: " + inputStr);

        TenRequestClass request = JSON.parseObject(inputStr, TenRequestClass.class);
        String result = processRequest(request, context);

        output.write(result.getBytes("UTF-8"));
        output.flush();
    }

    private String processRequest(TenRequestClass request, Context context) {
        if ("fib".equals(request.testType)) return handleFib(request);

        ChallengeData challengeData = request.challengeData;
        String bucketName = request.bucketName;
        String regionName = request.regionName;
        int DATA_SHARDS = request.DaTA_SHARDS;
        int PARITY_SHARDS = request.PARITY_SHARDS;
        String secretId = request.secretId;
        String secretKey = request.secretKey;
        int threadNum = request.threadNum;

        // Use AWS S3 SDK for downloads
        AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                .withRegion(regionName)
                .build();

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
                    downloadData[index] = downloadPartFile(s3Client, bucketName,
                            "sourceFile.txt",
                            challengeData.index[index] * DATA_SHARDS,
                            DATA_SHARDS);
                    downloadParity[index] = downloadPartFile(s3Client, bucketName,
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
            System.out.println("download from S3 successfully (multi-thread)");
        } else {
            start_time_download = System.nanoTime();
            for (int i = 0; i < challengeData.index.length; i++) {
                downloadData[i] = downloadPartFile(s3Client, bucketName,
                        "sourceFile.txt",
                        challengeData.index[i] * DATA_SHARDS,
                        DATA_SHARDS);
                downloadParity[i] = downloadPartFile(s3Client, bucketName,
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
        responseClass.instanceId = context.getLogStreamName();

        String output = JSON.toJSONString(responseClass);
        System.out.println("result:" + output);
        return output;
    }

    /**
     * S3 range download — fetches length bytes starting at startPos.
     */
    private byte[] downloadPartFile(AmazonS3 s3Client, String bucketName,
                                    String cloudFileName, long startPos, int length) {
        GetObjectRequest getRequest = new GetObjectRequest(bucketName, cloudFileName);
        getRequest.setRange(startPos, startPos + length - 1);

        S3Object s3Object = s3Client.getObject(getRequest);
        byte[] fileBlock = new byte[length];

        try (InputStream in = s3Object.getObjectContent()) {
            int offset = 0;
            while (offset < length) {
                int n = in.read(fileBlock, offset, length - offset);
                if (n == -1) break;
                offset += n;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return fileBlock;
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
}
