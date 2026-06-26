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
import com.fchen_group.TPDSInScf.Utils.CloudAPI;
import com.fchen_group.TPDSInScf.Utils.ResponseClass;
import com.fchen_group.TPDSInScf.Utils.TenRequestClass;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TenHandle {
    public String mainHandler(TenRequestClass request) {
        // Fibonacci test mode: pure CPU benchmark, no download
        if ("fib".equals(request.testType)) {
            return handleFib(request);
        }

        ChallengeData challengeData =request.challengeData;
        String bucketName = request.bucketName;
        String regionName = request.regionName;
        int DATA_SHARDS = request.DaTA_SHARDS;
        int PARITY_SHARDS = request.PARITY_SHARDS;
        String secretId = request.secretId;
        String secretKey = request.secretKey;
        int threadNum = request.threadNum;
        String storageType = request.storageType;

        // For s3.csv: all platforms download from unified AWS S3 bucket
        if ("s3".equals(storageType)) {
            return handleAuditS3(request, challengeData, DATA_SHARDS, PARITY_SHARDS, threadNum);
        }

        CloudAPI yunAPI = new CloudAPI(secretId, secretKey, regionName, bucketName);
//get ProofData from cloud by using challengeData from cloud
        IntegrityAuditing integrityAuditing = new IntegrityAuditing(DATA_SHARDS, PARITY_SHARDS);
        byte[][] downloadData = new byte[challengeData.index.length][DATA_SHARDS];
        byte[][] downloadParity = new byte[challengeData.index.length][PARITY_SHARDS];
        // 创建一个固定大小的线程池，线程数与处理器核心数相同
        long start_time_download =0;
        long end_time_download = 0;


        if(threadNum!=1){

            ExecutorService executor = Executors.newFixedThreadPool(threadNum);

            start_time_download = System.nanoTime();
            for (int i = 0; i < challengeData.index.length; i++) {
                final int index = i;
                executor.submit(() -> {
                    downloadData[index] = yunAPI.downloadPartFile("sourceFile.txt", challengeData.index[index] * DATA_SHARDS, DATA_SHARDS);
                    downloadParity[index] = yunAPI.downloadPartFile("parities.txt", challengeData.index[index] * PARITY_SHARDS, PARITY_SHARDS);
                });
            }

            // 关闭线程池并等待所有任务完成
            executor.shutdown();
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            end_time_download = System.nanoTime();

            System.out.println("down load from COS successfully");

        }else {
            start_time_download = System.nanoTime();
            for (int i = 0; i < challengeData.index.length; i++) {
                downloadData[i] = yunAPI.downloadPartFile("sourceFile.txt", challengeData.index[i] * DATA_SHARDS, DATA_SHARDS);
                downloadParity[i] = yunAPI.downloadPartFile("parities.txt", challengeData.index[i] * PARITY_SHARDS, PARITY_SHARDS);
            }
            end_time_download = System.nanoTime();
        }
        long start_time_proof = System.nanoTime();
        ProofData proofData = integrityAuditing.prove(challengeData, downloadData, downloadParity);
        long end_time_proof = System.nanoTime();

        ResponseClass responseClass=new ResponseClass(proofData);
        long download_time=end_time_download-start_time_download;
        long proofTime=end_time_proof-start_time_proof;

        System.out.println(download_time);
        System.out.println(proofTime);
        responseClass.download_time=download_time;
        responseClass.proofTime=proofTime;
        try { responseClass.instanceId = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) {}
        String output= JSON.toJSONString(responseClass);
        System.out.println("最终结果"+output);
        return output;
    }
    public static void main(String[] args) {

    }

    /** Fast doubling — O(log n), computes fib(n) exactly. */
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

    /** Audit handler using AWS S3 for downloads (for s3.csv cross-cloud test). */
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
