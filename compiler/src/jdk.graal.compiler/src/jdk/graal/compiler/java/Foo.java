package jdk.graal.compiler.java;

public class Foo {

    public static int counter = 0;
    public static int counter2 = 0;

    public static void foo() {
        counter++;
    }

    public static void bar() {
        counter2++;
    }

}
