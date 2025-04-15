import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.util.EC2MetadataUtils;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AwsUtils {

    private static final int RUNNING_CODE = 16;
    private static String awsImageId;
    private static String awsKeyName;
    private static String awsSecGroup;
    private static String awsInstanceType;

    private static AmazonEC2 ec2;
    private static AmazonCloudWatch cloudWatch;

    private static final String PROPERTIES_PATH = "/home/ec2-user/.aws/webserver.properties";
    private static final String CREDENTIALS_PATH = "/home/ec2-user/.aws/credentials";

    public static Map<String, InstanceInfo> runningInstanceInfos = new ConcurrentHashMap<>();
    public static AtomicInteger liveInstancesCounter = new AtomicInteger(0);

    // Initialize variables
    static {
        AWSCredentials credentials = null;
        try {
            credentials = new ProfileCredentialsProvider(CREDENTIALS_PATH, null).getCredentials();
        } catch (Exception e) {
            System.err.println("Cannot load the credentials from the credential profiles file. " +
                "Please make sure that your credentials file is at the correct " +
                "location (~/.aws/credentials), and is in valid format.");
            e.printStackTrace();
            System.exit(1);
        }
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(PROPERTIES_PATH));
        } catch (IOException e) {
            System.err.println("Cannot load the properties from the file. " +
                "Please make sure that your properties file is at the correct " +
                "location (~/.aws/webserver.properties), and is in valid format.");
            e.printStackTrace();
            System.exit(1);
        }

        String awsServerRegion = properties.getProperty("server-region");
        awsImageId = properties.getProperty("image-id");
        awsKeyName = properties.getProperty("key-name");
        awsSecGroup = properties.getProperty("sec-group");
        awsInstanceType = properties.getProperty("instace-type");

        ec2 = AmazonEC2ClientBuilder.standard().withRegion(awsServerRegion).withCredentials(
            new AWSStaticCredentialsProvider(credentials)).build();
        cloudWatch = AmazonCloudWatchClientBuilder.standard().withRegion(awsServerRegion).withCredentials(
            new AWSStaticCredentialsProvider(credentials)).build();
    }

    private AwsUtils() {
    }

    public static void init() {
        updateRunningInstances();
        liveInstancesCounter.getAndSet(runningInstanceInfos.size());
    }

    private static void updateRunningInstances() {
        Set<Instance> instances = getInstances(RUNNING_CODE);
        for (Instance inst : instances) {
            if (!runningInstanceInfos.containsKey(inst.getInstanceId())) {
                runningInstanceInfos.put(inst.getInstanceId(), new InstanceInfo(inst));
            } else {
                runningInstanceInfos.get(inst.getInstanceId()).setInstance(inst);
            }
        }

        for (Map.Entry<String, InstanceInfo> entry : AwsUtils.runningInstanceInfos.entrySet()) {
            Instance instance = entry.getValue().getInstance();
            if (!instances.contains(instance)) {
                System.out.println(
                    "Instance " + instance.getInstanceId() + " was abruptly stopped. Removing it from the list.");
                runningInstanceInfos.remove(instance.getInstanceId());
                liveInstancesCounter.getAndDecrement();
            }
        }
    }

    private static Set<Instance> getInstances(int code) {
        Set<Instance> instances = new HashSet<>();
        String loadBalancerId = EC2MetadataUtils.getInstanceId();
        DescribeInstancesResult describeInstancesResult = ec2.describeInstances();
        List<Reservation> reservations = describeInstancesResult.getReservations();

        for (Reservation reservation : reservations) {
            List<Instance> foundInstances = reservation.getInstances();
            for (Instance inst : foundInstances) {
                if (!inst.getInstanceId().equals(loadBalancerId) && inst.getState().getCode() == code) {
                    instances.add(inst);
                }
            }
        }
        return instances;
    }

    public static void launchInstance() {
        RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

        runInstancesRequest.withImageId(awsImageId)
            .withInstanceType(awsInstanceType)
            .withMinCount(1)
            .withMaxCount(1)
            .withKeyName(awsKeyName)
            .withSecurityGroups(awsSecGroup)
            .withMonitoring(true);

        ec2.runInstances(runInstancesRequest);
        System.out.println("Launching instance...");

        liveInstancesCounter.getAndIncrement();
    }

    public static void terminateInstance(String instanceId) {
        TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest();
        terminateInstancesRequest.withInstanceIds(instanceId);
        ec2.terminateInstances(terminateInstancesRequest);
        System.out.println("Terminating instance " + instanceId + "...");

        while (getInstanceStatus(instanceId) == RUNNING_CODE) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        runningInstanceInfos.remove(instanceId);
    }

    public static int getInstanceStatus(String instanceId) {
        DescribeInstancesRequest describeInstanceRequest = new DescribeInstancesRequest().withInstanceIds(instanceId);
        DescribeInstancesResult describeInstanceResult = ec2.describeInstances(describeInstanceRequest);

        if (describeInstanceResult.getReservations().isEmpty()) {
            return -1;
        } else {
            InstanceState state = describeInstanceResult.getReservations().get(0).getInstances().get(0).getState();
            return state.getCode();
        }
    }

    public static void updateCpuMetrics() {
        long offsetInMilliseconds = 1000 * 60 * 10;
        Dimension instanceDimension = new Dimension();
        instanceDimension.setName("InstanceId");

        updateRunningInstances();

        for (Map.Entry<String, InstanceInfo> entry : runningInstanceInfos.entrySet()) {
            String name = entry.getValue().getInstance().getInstanceId();
            instanceDimension.setValue(name);

            GetMetricStatisticsRequest request = new GetMetricStatisticsRequest()
                .withStartTime(new Date(new Date().getTime() - offsetInMilliseconds))
                .withNamespace("AWS/EC2")
                .withPeriod(60)
                .withMetricName("CPUUtilization")
                .withStatistics("Average")
                .withDimensions(instanceDimension)
                .withEndTime(new Date());
            GetMetricStatisticsResult getMetricStatisticsResult = cloudWatch.getMetricStatistics(request);
            List<Datapoint> datapoints = getMetricStatisticsResult.getDatapoints();

            Datapoint lastDatapoint = null;
            for (Datapoint dp : datapoints) {
                if (lastDatapoint == null) {
                    lastDatapoint = dp;
                }
                if (lastDatapoint.getTimestamp().before(dp.getTimestamp())) {
                    lastDatapoint = dp;
                }
            }

            double average;
            if (lastDatapoint != null) {
                average = lastDatapoint.getAverage();
                entry.getValue().setFresh(false);
            } else {
                average = 0;
            }

            entry.getValue().setLastCpuMeasured(average);
            System.out.println("CPU Utilization on " + entry.getValue().getInstance().getInstanceId() + ": " + average);
        }
    }

    public static InstanceInfo getLeastUsedValidInstanceInfo() {
        updateRunningInstances();

        InstanceInfo instanceInfo = null;
        double min = Double.MAX_VALUE;

        for (Map.Entry<String, InstanceInfo> entry : runningInstanceInfos.entrySet()) {
            if (entry.getValue().getWork() < min && !entry.getValue().willTerminate()) {
                instanceInfo = entry.getValue();
                min = instanceInfo.getWork();
            }
        }
        return instanceInfo;
    }

    public static void markInstance(String leastUsedInstanceId) {
        runningInstanceInfos.get(leastUsedInstanceId).setTerminate(true);
        liveInstancesCounter.getAndDecrement();
    }
}