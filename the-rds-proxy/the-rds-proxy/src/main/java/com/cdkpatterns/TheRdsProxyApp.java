package com.cdkpatterns;

import software.amazon.awscdk.core.App;

import java.util.Arrays;

public class TheRdsProxyApp {
    public static void main(final String[] args) {
        App app = new App();

        new TheRdsProxyStack(app, "TheRdsProxyStack");

        app.synth();
    }
}
