package com.fchen_group.TPDSInScf.Utils;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Alibaba Cloud OSS wrapper — same interface as CloudAPI.java
 * uploadFile / downloadPartFile / multipartUpload
 */
public class AliCloudAPI {

    private OSS ossClient;
    private String bucketName;

    /** Used in Client (local) — reads config from Properties file */
    public AliCloudAPI(String configFilePath) {
        String secretId = null;
        String secretKey = null;
        String endpoint = null;

        try {
            FileInputStream fis = new FileInputStream(configFilePath);
            Properties props = new Properties();
            props.load(fis);
            fis.close();

            secretId = props.getProperty("secretId");
            secretKey = props.getProperty("secretKey");
            String region = props.getProperty("regionName");
            bucketName = props.getProperty("bucketName");
            // OSS endpoint: https://oss-{region}.aliyuncs.com
            endpoint = "https://oss-" + region + ".aliyuncs.com";

        } catch (IOException e) {
            e.printStackTrace();
        }

        ossClient = new OSSClientBuilder().build(endpoint, secretId, secretKey);
    }

    /** Used in SCF handler — receives params directly */
    public AliCloudAPI(String secretId, String secretKey, String regionName, String bucketName) {
        this.bucketName = bucketName;
        String endpoint = "https://oss-" + regionName + ".aliyuncs.com";
        ossClient = new OSSClientBuilder().build(endpoint, secretId, secretKey);
    }

    public void uploadFile(String localFilePath, String cloudFileName) {
        ossClient.putObject(bucketName, cloudFileName, new File(localFilePath));
    }

    public byte[] downloadPartFile(String cloudFileName, long startPos, int length) {
        GetObjectRequest request = new GetObjectRequest(bucketName, cloudFileName);
        request.setRange(startPos, startPos + length - 1);

        OSSObject ossObject = ossClient.getObject(request);
        byte[] fileBlock = new byte[length];

        try (InputStream in = ossObject.getObjectContent()) {
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

    public void multipartUpload(String filePath, int partCount, String cloudFileName) {
        File file = new File(filePath);
        long fileSize = file.length();
        long partSize = fileSize / partCount;

        InitiateMultipartUploadRequest initRequest =
                new InitiateMultipartUploadRequest(bucketName, cloudFileName);
        InitiateMultipartUploadResult initResult =
                ossClient.initiateMultipartUpload(initRequest);
        String uploadId = initResult.getUploadId();

        CountDownLatch latch = new CountDownLatch(partCount);
        List<PartETag> partETags = new ArrayList<>();

        try {
            ExecutorService executor = Executors.newFixedThreadPool(partCount);
            for (int j = 0; j < partCount; j++) {
                final int partIndex = j;
                executor.execute(() -> {
                    long startPos = partIndex * partSize;
                    long curPartSize = (partIndex + 1 == partCount)
                            ? (fileSize - startPos) : partSize;

                    InputStream inputStream = null;
                    try {
                        inputStream = new FileInputStream(file);
                        inputStream.skip(startPos);

                        UploadPartRequest uploadRequest = new UploadPartRequest();
                        uploadRequest.setBucketName(bucketName);
                        uploadRequest.setKey(cloudFileName);
                        uploadRequest.setUploadId(uploadId);
                        uploadRequest.setInputStream(inputStream);
                        uploadRequest.setPartNumber(partIndex + 1);
                        uploadRequest.setPartSize(curPartSize);

                        System.out.println("start" + partIndex);
                        UploadPartResult uploadResult = ossClient.uploadPart(uploadRequest);
                        long time = System.nanoTime();
                        System.out.println("end" + partIndex + "    " + time);
                        partETags.add(uploadResult.getPartETag());
                        System.out.println("finish" + partIndex);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        try { inputStream.close(); } catch (IOException e) { }
                    }
                });
            }
            latch.await();
            executor.shutdown();

            CompleteMultipartUploadRequest completeRequest =
                    new CompleteMultipartUploadRequest(bucketName, cloudFileName, uploadId, partETags);
            ossClient.completeMultipartUpload(completeRequest);
            System.out.println("Multipart upload completed.");
        } catch (Exception e) {
            e.printStackTrace();
            AbortMultipartUploadRequest abortRequest =
                    new AbortMultipartUploadRequest(bucketName, cloudFileName, uploadId);
            ossClient.abortMultipartUpload(abortRequest);
        } finally {
            ossClient.shutdown();
        }
    }

    /** Quick OSS connectivity test */
    public static void main(String[] args) {
        String configPath = System.getProperty("user.dir") + "\\Properties";
        AliCloudAPI api = new AliCloudAPI(configPath);
        System.out.println("OSS client created successfully.");

        // Test upload
        String testFile = System.getProperty("user.dir") + "\\test-upload.txt";
        try {
            // Create a small test file
            java.io.PrintWriter pw = new java.io.PrintWriter(testFile);
            pw.println("Alibaba OSS upload test");
            pw.close();

            api.uploadFile(testFile, "test-upload.txt");
            System.out.println("Upload test-upload.txt OK");

            // Test download
            byte[] content = api.downloadPartFile("test-upload.txt", 0, 26);
            System.out.println("Downloaded: " + new String(content));

            System.out.println("OSS connectivity test PASSED.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
