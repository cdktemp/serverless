using Amazon.CDK;
using Lambda = Amazon.CDK.AWS.Lambda;
using ApiGatewayV2 = Amazon.CDK.AWS.APIGatewayv2;
using ApiGatewayIntegration = Amazon.CDK.AWS.APIGatewayv2.Integrations;
using EC2 = Amazon.CDK.AWS.EC2;
using RDS = Amazon.CDK.AWS.RDS;
using Secrets = Amazon.CDK.AWS.SecretsManager;
using SSM = Amazon.CDK.AWS.SSM;
using System.Collections.Generic;

namespace TheRdsProxy
{
    public class TheRdsProxyStack : Stack
    {

        private readonly EC2.Vpc _vpcRDS;
        private readonly EC2.SecurityGroup _lambdaSecurityGroup;
        private readonly EC2.SecurityGroup _dbSecurityGroup;
        private readonly Secrets.Secret _secretDbCredentials;
        private readonly RDS.DatabaseInstance _mysqlInstance;
        private readonly Lambda.Function _rdsFunction;
        private readonly ApiGatewayV2.HttpApi _httpApi;

        internal TheRdsProxyStack(Construct scope, string id, IStackProps props = null) : base(scope, id, props)
        {

            // RDS needs to be setup in a VPC
            _vpcRDS = new EC2.Vpc(this, "Vpc", new EC2.VpcProps
            {
                MaxAzs = 2
            });

            // We need this security group to add an ingress rule and allow our lambda to query the proxy
            _lambdaSecurityGroup = new EC2.SecurityGroup(this, "Lambda to RDS Proxy Connection", new EC2.SecurityGroupProps
            {
                Vpc = _vpcRDS
            });

            // We need this security group to allow our proxy to query our MySQL Instance
            _dbSecurityGroup = new EC2.SecurityGroup(this, "Proxy to DB Connection", new EC2.SecurityGroupProps
            {
                Vpc = _vpcRDS
            });
            _dbSecurityGroup.AddIngressRule(_dbSecurityGroup, EC2.Port.Tcp(3306), "allow db connection");
            _dbSecurityGroup.AddIngressRule(_lambdaSecurityGroup, EC2.Port.Tcp(3306), "allow lambda connection");

            // Creating secrets to RDS
            _secretDbCredentials = new Secrets.Secret(this, "DBCredentialsSecret", new Secrets.SecretProps
            {
                SecretName = id + "-rds-credentials",
                GenerateSecretString = new Secrets.SecretStringGenerator
                {
                    SecretStringTemplate = "{\"username\":\"syscdk\"}",
                    ExcludePunctuation = true,
                    IncludeSpace = false,
                    GenerateStringKey = "password"
                }
            }) ;

            // Adding the secret to SSM
            new SSM.StringParameter(this, "DBCredentialsArn", new SSM.StringParameterProps
            {
                ParameterName = "rds-credentials-arn",
                StringValue = _secretDbCredentials.SecretArn
            });

            // MySQL DB Instance (delete protection turned off because pattern is for learning.)
            // Attention: re-enable delete protection for a real implementation
            _mysqlInstance = new RDS.DatabaseInstance(this, "DBInstance", new RDS.DatabaseInstanceProps
            {
                Engine = RDS.DatabaseInstanceEngine.Mysql(new RDS.MySqlInstanceEngineProps
                {
                    Version = RDS.MysqlEngineVersion.VER_5_7_30
                }),
                Credentials = RDS.Credentials.FromSecret(_secretDbCredentials),
                InstanceType = EC2.InstanceType.Of(EC2.InstanceClass.BURSTABLE2, EC2.InstanceSize.SMALL),
                Vpc = _vpcRDS,
                RemovalPolicy = RemovalPolicy.DESTROY,
                DeletionProtection = false,
                SecurityGroups = new []
                {
                    _dbSecurityGroup
                }
            });

            // Create an RDS proxy
            var proxy = _mysqlInstance.AddProxy(id = id + "-proxy", new RDS.DatabaseProxyOptions
            {
                Secrets = new[] {_secretDbCredentials},
                DebugLogging = true,
                Vpc = _vpcRDS,
                SecurityGroups = new[] { _dbSecurityGroup }
            });

            // Lambda function 
            _rdsFunction = new Lambda.Function(this, "rdsProxyHandler", new Lambda.FunctionProps
            {
                Runtime = Lambda.Runtime.NODEJS_12_X,
                Code = Lambda.Code.FromAsset("lambda_fns/rds"),
                Handler = "rdsLambda.handler",
                Vpc = _vpcRDS,
                SecurityGroups = new[] { _lambdaSecurityGroup },
                Environment = new Dictionary<string, string>
                {
                    { "PROXY_ENDPOINT", proxy.Endpoint },
                    { "RDS_SECRET_NAME", id + "-rds-credentials" }
                }
            });

            _secretDbCredentials.GrantRead(_rdsFunction);

            // defines an API Gateway Http API resource backed by our "dynamoLambda" function.
            _httpApi = new ApiGatewayV2.HttpApi(this, "Endpoint", new ApiGatewayV2.HttpApiProps
            {
                DefaultIntegration = new ApiGatewayIntegration.LambdaProxyIntegration(
                    new ApiGatewayIntegration.LambdaProxyIntegrationProps {
                        Handler = _rdsFunction
                })
            });

            new CfnOutput(this, "HTTP API Url", new CfnOutputProps
            {
                Value = _httpApi.Url
            });
        }
    }
}
