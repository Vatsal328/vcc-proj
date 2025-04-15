import java.util.Map;

public class AutoScaler {

    private static final long SCALE_INTERVAL = 10000;
    private static final double CPU_UTILIZATION_MIN = 50;

    public static final double MAX_WORKLOAD = 500000000;
    private static final double WORKLOAD_UPPER_LIM = MAX_WORKLOAD*0.70;
    private static final double WORKLOAD_LOWER_LIM = MAX_WORKLOAD*0.30;


    public static void execute() throws InterruptedException {
        while (true) {
            Thread.sleep(SCALE_INTERVAL);
            scale();
        }
    }

    private static void scale() {
        System.out.println("--------------------------------------");
        System.out.println("Auto-Scaling...");

        AwsUtils.updateCpuMetrics();
        checkMarkedInstances();

        double averageCpu = 0;
        double averageWork = 0;
        int size = 0;
        boolean freshLaunch = false;
        for (Map.Entry<String, InstanceInfo> entry : AwsUtils.runningInstanceInfos.entrySet()) {
            if (!entry.getValue().willTerminate()) {
                averageCpu += entry.getValue().getLastCpuMeasured();
                averageWork += entry.getValue().getWork();
                System.out.println("Workload on " + entry.getValue().getInstance().getInstanceId() + ": " +
                    entry.getValue().getWork());
                size++;
                if (entry.getValue().isFresh()) {
                    freshLaunch = true;
                }
            }
        }

        if (size > 0) {
            averageCpu /= size;
            averageWork /= size;

            System.out.println("Total average CPU utilization (" + size + " instances): " + averageCpu);
            System.out.println("Total average work (" + size + " instances): " + averageWork);

            if (!freshLaunch) {
                if (averageWork > WORKLOAD_UPPER_LIM) {
                    AwsUtils.launchInstance();

                } else if (averageWork < WORKLOAD_LOWER_LIM && (size > 1 || averageCpu < CPU_UTILIZATION_MIN)) {
                    markLeastUsedInstance();
                }
            }
        }
        System.out.println("--------------------------------------");
    }

    private static void markLeastUsedInstance() {
        String instanceId = AwsUtils.getLeastUsedValidInstanceInfo().getInstance().getInstanceId();
        AwsUtils.markInstance(instanceId);
        System.out.println("Instance " + instanceId + " marked to terminate");
    }

    private static void checkMarkedInstances() {
        for (final Map.Entry<String, InstanceInfo> entry : AwsUtils.runningInstanceInfos.entrySet()) {
            if (entry.getValue().willTerminate() && entry.getValue().getNumCurrentRequests() == 0) {
                new Thread(new Runnable() {
                    public void run() {
                        AwsUtils.terminateInstance(entry.getKey());
                    }
                }).start();
            }
        }
    }

}