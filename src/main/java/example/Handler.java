package example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ec2.waiters.Ec2Waiter;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.*;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Handler implements RequestHandler<String, Integer> {

    @Override
    public Integer handleRequest(String event, Context context){
        LambdaLogger logger = context.getLogger();
        logger.log("----EVENT: " + event+"----");
        String appServerInstanceId = System.getProperty("APP_SERVER_INSTANCE_ID");
        String dbInstanceIdentifier = System.getProperty("DB_INSTANCE_IDENTIFIER");
        DescribeInstancesRequest describeInstancesRequest = DescribeInstancesRequest.builder().instanceIds(appServerInstanceId).build();
        DescribeDbInstancesRequest describeDbInstancesRequest = DescribeDbInstancesRequest.builder().dbInstanceIdentifier(dbInstanceIdentifier).build();

        try(final Ec2Client ec2 = Ec2Client.create();
            final RdsClient rds = RdsClient.create()){

            switch (event){
                case "start":

                    DescribeInstancesResponse describeInstancesResponse = ec2.describeInstances(describeInstancesRequest);
                    DescribeDbInstancesResponse describeDbInstancesResponse = rds.describeDBInstances(describeDbInstancesRequest);
                    Instance ec2Instance = describeInstancesResponse.reservations().get(0).instances().get(0);
                    DBInstance dbInstance = describeDbInstancesResponse.dbInstances().get(0);

                    // Start EC2 instance
                    if(ec2Instance.state().name().equals(InstanceStateName.STOPPED)){
                        ec2.startInstances(StartInstancesRequest.builder()
                                .instanceIds(appServerInstanceId)
                                .build());
                        logger.log("----EC2 Instance started----");
                    } else {
                        throw new RuntimeException("EC2Instance is already running");
                    }

                    // Start RDS instance
                    if(dbInstance.dbInstanceStatus().equalsIgnoreCase("stopped")){
                        rds.startDBInstance(StartDbInstanceRequest.builder()
                                .dbInstanceIdentifier(dbInstanceIdentifier)
                                .build());
                        logger.log("----DB Instance started----");
                    } else {
                        throw new RuntimeException("RDSInstance is already running");
                    }

                    // Wait until EC2 instance is in Running state
                    Ec2Waiter ec2Waiter = ec2.waiter();
                    WaiterResponse<DescribeInstancesResponse> ec2WaiterResponse = ec2Waiter.waitUntilInstanceRunning(e -> e.instanceIds(appServerInstanceId));
                    String publicIpAddress = ec2WaiterResponse.matched().response()
                            .orElseThrow(() -> new RuntimeException("Did not get response after waiting for ec2waiter"))
                            .reservations().get(0)
                            .instances().get(0)
                            .publicIpAddress();

                    logger.log("----Public IP address for ec2Instance: "+publicIpAddress+"----");

                    // send get request to NOIP api

                    updateDNS(publicIpAddress);

                    logger.log("DNS Updated");

                    break;
                case "stop":

                    // Stop EC2 instance
                    ec2.stopInstances(StopInstancesRequest.builder()
                            .instanceIds(appServerInstanceId)
                            .build());

                    logger.log("EC2 Instance stopped");

                    // Stop RDS instance
                    rds.stopDBInstance(StopDbInstanceRequest.builder()
                            .dbInstanceIdentifier(dbInstanceIdentifier)
                            .build());

                    logger.log("DB Instance stopped");

                    break;

                default:
                    throw new RuntimeException("Invalid case");
            }
        }
        return context.getRemainingTimeInMillis();
    }

    public static void updateDNS(String ip){

        String url = System.getProperty("DNS_UPDATE_URL");
        URI uri = URI.create(url + ip);

        HttpClient client = HttpClient.newBuilder()
                .authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(System.getProperty("DNS_SERVICE_USERNAME"), System.getProperty("DNS_SERVICE_PASSWORD").toCharArray());
                    }
                }).build();


        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder()
                    .GET()
                    .uri(uri)
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        if(!response.body().startsWith("good")){
            throw new RuntimeException("Could not update DNS. Got response: " + response.body());
        }
    }
}
