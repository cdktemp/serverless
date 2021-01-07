package com.cdkpatterns;

import software.amazon.awscdk.core.App;

import com.cdkpatterns.TheRdsProxyStack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class TheRdsProxyTest {
    private final static ObjectMapper JSON =
        new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true);

    @Test
    public void testStack() throws IOException {
        App app = new App();
        TheRdsProxyStack stack = new TheRdsProxyStack(app, "test");

        // synthesize the stack to a CloudFormation template and compare against
        // a checked-in JSON file.
        JsonNode actual = JSON.valueToTree(app.synth().getStackArtifact(stack.getArtifactId()).getTemplate());

        // After Synth, performs some basic tests

        // AWS::RDS::DBProxy exists test
        assertThat(actual.toString()).contains("AWS::RDS::DBProxy");

        // AWS::Lambda::Function exists test
        assertThat(actual.toString()).contains("AWS::Lambda::Function");
        
        // AWS::ApiGatewayV2::Api exists test
        assertThat(actual.toString()).contains("AWS::ApiGatewayV2::Api");
        
        // AWS::RDS::DBInstance exists test
        assertThat(actual.toString()).contains("AWS::RDS::DBInstance");
    }
}
