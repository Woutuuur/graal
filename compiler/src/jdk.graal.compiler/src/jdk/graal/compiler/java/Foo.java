package jdk.graal.compiler.java;

import java.util.concurrent.atomic.AtomicLong;

public class Foo {

    public static AtomicLong counter = new AtomicLong(0);
    public static AtomicLong counter2 = new AtomicLong(0);

    public static void foo() {
        counter.incrementAndGet();
    }

    public static void bar() {
        counter2.incrementAndGet();
    }

}
