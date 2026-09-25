package com.fchen_group.TPDSInScf.Utils;

import java.math.BigInteger;

/**
 * Naive iterative Fibonacci — O(n) big-integer additions, the task the paper describes as
 * "compute the first 800,000 terms". Selected at request time via {@code fibAlgo=iter};
 * the handlers' default fast-doubling path is left untouched so existing FIV.csv stays valid.
 */
public class FibIter {

    private FibIter() {}

    public static String fib(int n) {
        if (n <= 1) return String.valueOf(n);
        BigInteger a = BigInteger.ZERO, b = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            BigInteger c = a.add(b);
            a = b;
            b = c;
        }
        return b.toString();
    }
}
