package com.fchen_group.TPDSInScf.Core;

import com.fchen_group.TPDSInScf.Utils.ReedSolomon.Galois;
import com.fchen_group.TPDSInScf.Utils.ReedSolomon.ReedSolomon;
import com.tencentcloudapi.ocr.v20181119.models.VehicleRegCertInfo;
import com.tencentcloudapi.tsf.v20180326.models.GatewayGroupIds;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

/**
 * This class implements each process of the auditing lifecycle
 */

public class IntegrityAuditing {

    private int DATA_SHARDS; //num of message bytes ,  223 in this protocol
    private int PARITY_SHARDS; //num of ecc parity bytes , 32 in this proto;
    public int SHARD_NUMBER; // the num of blocks 为文件大小（B）/223(B)
    public int my_shard;


    private long fileSize;
    public final int BYTES_IN_INT = 4;
    private long storeSize;
    private String Key; //
    private String sKey;
    private String filePath;

    public byte[][] originalData;//the source data,stored as blocks
    public byte[][] parity; // the final calculated parity
    public int len = 16; //16 means the key has 16 chars, one chars=8bit, the key's security level is satisfied 128 bit

    /**
     * Construction method , used in SCF
     */
    public IntegrityAuditing(int DATA_SHARDS, int PARITY_SHARDS) {
        this.DATA_SHARDS = DATA_SHARDS;
        this.PARITY_SHARDS = PARITY_SHARDS;
    }

    /**
     * Construction method , used in client这里改了
     */
    public IntegrityAuditing(String filePath, int BLOCK_SHARDS, int DATA_SHARDS) throws IOException {

        this.filePath = filePath;
        this.DATA_SHARDS = DATA_SHARDS;
        this.PARITY_SHARDS = BLOCK_SHARDS - DATA_SHARDS;

        //cal SHARD_NUMBER 支持范围达到TB
        File inputFile = new File(filePath);
        this.fileSize = inputFile.length();
        //storeSize =fileSize +4
        this.storeSize = fileSize + BYTES_IN_INT;
        //这个地方就有问题了，SHARD_NUMBER变成了INT类型，但是即便如此也可以支持400多GB的文件。
        this.SHARD_NUMBER = (int) ((storeSize + (long)DATA_SHARDS - 1) / (long)DATA_SHARDS);

        if(this.SHARD_NUMBER<4194304){
            this.my_shard=SHARD_NUMBER;
        }else {
            this.my_shard=4194304;
        }
        this.originalData = new byte[my_shard][DATA_SHARDS];
        // read original data 这个地方就有问题了，他试图将整个文件读入内存中。
//        FileInputStream in = new FileInputStream(inputFile);
//        for (int i = 0; i < SHARD_NUMBER; i++) {
//            in.read(originalData[i]);
//        }
//        in.close();

    }

    /**
     * Used to generate two secret key这里的东西是固定的，与文件大小无关，就是单纯生成一个Key
     */
    public void genKey() {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

        StringBuffer sBuffer1 = new StringBuffer();
        Random random1 = new Random();
        //len 是一个固定值，所以没有问题。
        for (int i = 0; i < len; i++) {
            sBuffer1.append(chars.charAt(random1.nextInt(chars.length())));
        }

        StringBuffer sBuffer2 = new StringBuffer();
        Random random2 = new Random();
        for (int i = 0; i < this.PARITY_SHARDS; i++) {
            sBuffer2.append(chars.charAt(random2.nextInt(chars.length())));
        }
        this.Key = sBuffer1.toString();
        this.sKey = sBuffer2.toString();
    }

    /**
     * Calculate the tags of the source data这里改了
     */
    public long outSource(int number) {

        long start_time_process = System.nanoTime();

        this.parity = new byte[number][];
        //这个地方ReedSolomon 可以看成一个黑盒，将223位的块变为一个32位的标签.这之后parity[i]代表第i个标签。parity[i][j]代表标签的某个部分。有32部分嘛
        ReedSolomon reedSolomon = new ReedSolomon(DATA_SHARDS, PARITY_SHARDS);
        for (int i = 0; i < number; i++) {
            parity[i] = reedSolomon.encodeParity(originalData[i], 0, 1);
        }
        //Multiply a sKey num 这个地方也是一样的，相当于parity[i][j]=parity[i][j]与sKey[j]的域内相乘。
        for (int i = 0; i < parity.length; i++) {
            //这里的sKey是固定好了的
            byte[] sKeyBytes = sKey.getBytes();
            if (sKeyBytes.length != parity[i].length) {
                System.out.println("Error:  sKeyBytes.length != parity.length");
            } else {
                for (int j = 0; j < parity[i].length; j++) {
                    parity[i][j] = Galois.multiply(parity[i][j], sKeyBytes[j]);
                }
            }
        }
        //Add a pseudo random num 然后加上了一个随机值
        for (int i = 0; i < parity.length; i++) {
            byte[] randoms = PseudoRandom.generateRandom(i, this.Key, PARITY_SHARDS);
            for (int j = 0; j < PARITY_SHARDS; j++) {
                parity[i][j] = Galois.add(parity[i][j], randoms[j]);
            }
        }
        long end_time_process = System.nanoTime();
        long timeProcessData = end_time_process - start_time_process;
        System.out.println("Process phase finished");
        return timeProcessData;
    }

    /**
     * Generate the challenge data for auditing
     *这个生成的长度之类的是固定的，与文件大小无关。
     */
    public ChallengeData audit(int challengeLen) {
        byte[] coefficients = new byte[challengeLen];
        int[] index = new int[challengeLen];

        Random random = new Random();
        for (int i = 0; i < challengeLen; i++) {
            index[i] = random.nextInt(SHARD_NUMBER);
        }

        random.nextBytes(coefficients);
        return new ChallengeData(index, coefficients);
    }

    /**
     * Calculate the proofDate after receiving the challenge data and retrieve data from the cloud
     *也无关
     * @param challengeData
     * @param downloadData   the challenged source data
     * @param downloadParity the challenged parity data
     */
    public ProofData prove(ChallengeData challengeData, byte[][] downloadData, byte[][] downloadParity) {
        byte[] dataProof = new byte[DATA_SHARDS];
        byte[] parityProof = new byte[PARITY_SHARDS];

        for (int i = 0; i < challengeData.index.length; i++) {
            byte[] tempData = new byte[DATA_SHARDS];
            byte[] tempParity = new byte[PARITY_SHARDS];
            //int index = challengeData.index[i];

            //cal product of each selected parity block ,data block with coefficients
            for (int j = 0; j < PARITY_SHARDS; j++) {
                tempParity[j] = Galois.multiply(challengeData.coefficients[i], downloadParity[i][j]);
            }
            for (int j = 0; j < DATA_SHARDS; j++) {
                tempData[j] = Galois.multiply(challengeData.coefficients[i], downloadData[i][j]);
            }

            //cal the cumulative sum of calculated block
            for (int j = 0; j < PARITY_SHARDS; j++) {
                parityProof[j] = Galois.add(parityProof[j], tempParity[j]);
            }
            for (int j = 0; j < DATA_SHARDS; j++) {
                dataProof[j] = Galois.add(dataProof[j], tempData[j]);
            }
        }
        ProofData proofData = new ProofData(dataProof, parityProof);
        return proofData;

    }

    /**
     * 也无关
     * To calculate the integrity audit result
     * @param  challengeData
     * @param proofData  proofData return by SCF*/
    public boolean verify(ChallengeData challengeData, ProofData proofData) {
        byte[] verifyParity = new byte[PARITY_SHARDS];
        byte[] reCalParity;

        //First, calculate the sum of the (coefficient * random numbers) in the verification equation
        byte[] sumTemp = new byte[PARITY_SHARDS];
        for (int i = 0; i < challengeData.index.length; i++) {
            byte[] AESRandomByte = PseudoRandom.generateRandom(challengeData.index[i], this.Key, PARITY_SHARDS);
            byte[] temp = new byte[PARITY_SHARDS];
            //cal each (c_j)*(F(i_j))
            for (int j = 0; j < PARITY_SHARDS; j++) {
                temp[j] = Galois.multiply(challengeData.coefficients[i], AESRandomByte[j]);
            }
            //cal sum
            for (int k = 0; k < PARITY_SHARDS; k++) {
                sumTemp[k] = Galois.add(sumTemp[k], temp[k]);
            }
        }
        //continue to cal the Ecc parity from (challengeData , ProofData)
        //firstly,execute: parity - HATags
        for (int i = 0; i < PARITY_SHARDS; i++) {
            verifyParity[i] = Galois.subtract(proofData.parityProof[i], sumTemp[i]);
        }
        //divided by the secret key s
        for (int j = 0; j < PARITY_SHARDS; j++) {
            byte[] sKeyBytes = sKey.getBytes();
            verifyParity[j] = Galois.divide(verifyParity[j], sKeyBytes[j]);
        }
        //using proofData to re cal Ecc parity for verify comparision
        ReedSolomon reedSolomon = new ReedSolomon(DATA_SHARDS, PARITY_SHARDS);
        reCalParity = reedSolomon.encodeParity(proofData.dataProof, 0, 1);

        return compareByteArray(verifyParity, reCalParity);

    }

    /**
     * To calculate tow byte[] array is equal or not according to the content
     * */
    private boolean compareByteArray(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        } else if (a.length != b.length) {
            return false;
        } else {
            if (!Arrays.equals(a, b)) {
                return false;
            }
            return true;
        }
    }
}



