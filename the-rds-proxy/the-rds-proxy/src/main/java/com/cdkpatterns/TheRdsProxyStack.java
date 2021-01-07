package com.cdkpatterns;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.core.CfnOutput;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.MySqlInstanceEngineProps;
import software.amazon.awscdk.services.rds.MysqlEngineVersion;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseProxy;
import software.amazon.awscdk.services.rds.DatabaseProxyOptions;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.apigatewayv2.integrations.LambdaProxyIntegration;
import software.amazon.awscdk.services.apigatewayv2.integrations.LambdaProxyIntegrationProps;

public class TheRdsProxyStack extends Stack {
    public TheRdsProxyStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public TheRdsProxyStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);
        
        // RDS needs to be setup in a VPC
        Vpc vpcRDS = Vpc.Builder.create(this, "Vpc")
        		.maxAzs(2)
        		.build();
        
        // We need this security group to add an ingress rule and allow our lambda to query the proxy
        SecurityGroup lambdaSecurityGroup = SecurityGroup.Builder.create(this, "Lambda to RDS Proxy Connection")
        		.vpc(vpcRDS)
        		.build();
        
        // We need this security group to allow our proxy to query our MySQL Instance
        SecurityGroup dbSecurityGroup = SecurityGroup.Builder.create(this, "Proxy to DB Connection")
        		.vpc(vpcRDS)
        		.build();
        
        dbSecurityGroup.addIngressRule(dbSecurityGroup, Port.tcp(3306), "allow db connection");
        dbSecurityGroup.addIngressRule(lambdaSecurityGroup, Port.tcp(3306), "allow lambda connection");
        
        // Creating secrets to RDS
        Secret secretDbCredentials = Secret.Builder.create(this, "DBCredentialsSecret")
        		.secretName(id + "-rds-credentials")
        		.generateSecretString(new SecretStringGenerator.Builder().
        				secretStringTemplate("{\"username\":\"syscdk\"}")
        				.excludePunctuation(true)
        				.includeSpace(false)
        				.generateStringKey("password")
        				.build())
        		.build();
        
        // Adding the secret to SSM
        StringParameter.Builder.create(this, "DBCredentialsArn")
	        .parameterName("rds-credentials-arn")
	        .stringValue(secretDbCredentials.getSecretArn())
	        .build();
        
        // MySQL DB Instance (delete protection turned off because pattern is for learning.)
        // Attention: re-enable delete protection for a real implementation
        DatabaseInstance mysqlInstance = DatabaseInstance.Builder.create(this, "DBInstance")
        		.engine(DatabaseInstanceEngine.mysql(
        				new MySqlInstanceEngineProps.Builder().version(MysqlEngineVersion.VER_5_7_30).build())
        				)
        		.credentials(Credentials.fromSecret(secretDbCredentials))
        		.instanceType(InstanceType.of(InstanceClass.BURSTABLE2, InstanceSize.SMALL))
        		.vpc(vpcRDS)
        		.removalPolicy(RemovalPolicy.DESTROY)
        		.deletionProtection(false)
        		.securityGroups(List.of(dbSecurityGroup))
        		.build();
        
        // Create an RDS proxy
        DatabaseProxy proxy = mysqlInstance.addProxy(id + "-proxy", new DatabaseProxyOptions.Builder()
	        		.secrets(List.of(secretDbCredentials))
	        		.debugLogging(true)
	        		.vpc(vpcRDS)
	        		.securityGroups(List.of(dbSecurityGroup))
	        		.build());
        
        
        // Lambda function 
        Function rdsFunction = Function.Builder.create(this, "rdsProxyHandler")
        		.runtime(Runtime.NODEJS_12_X)
        		.handler("rdsLambda.handler")
        		.vpc(vpcRDS)
        		.code(Code.fromAsset("lambda_fns/rds"))
        		.securityGroups(List.of(
        				lambdaSecurityGroup))
        		.environment(Map.of(
        				"PROXY_ENDPOINT", proxy.getEndpoint(),
        				"RDS_SECRET_NAME", id + "-rds-credentials"))
        		.build();
        		
        secretDbCredentials.grantRead(rdsFunction);
        
        // defines an API Gateway Http API resource backed by our "dynamoLambda" function.
        HttpApi httpApi = HttpApi.Builder.create(this, "Endpoint")
        		.defaultIntegration(new LambdaProxyIntegration(
        				new LambdaProxyIntegrationProps.Builder().handler(rdsFunction).build()))
        		.build();
        
        CfnOutput.Builder.create(this, "HTTP API Url")
        	.value(httpApi.getUrl())
        	.build();
    }
}
