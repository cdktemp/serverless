using Amazon.CDK;
using System;
using System.Collections.Generic;
using System.Linq;

namespace TheRdsProxy
{
    sealed class Program
    {
        public static void Main(string[] args)
        {
            var app = new App();
            new TheRdsProxyStack(app, "TheRdsProxyStack");
            app.Synth();
        }
    }
}
