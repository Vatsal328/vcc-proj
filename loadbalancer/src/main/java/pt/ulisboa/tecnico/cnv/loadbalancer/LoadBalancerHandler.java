package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class LoadBalancerHandler implements HttpHandler {

    private static final int WS_PORT = 8000;
    private static final long WS_WAIT_TIME = 2000;
    private static final long CONNECTION_CHECK_INTERVAL = 5000;

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {

        final String query = httpExchange.getRequestURI().getQuery();
        System.out.println("Received request: " + query);

        final String[] params = query.split("&");

        double requestWork;

        requestWork = calculateEstimatedWork();

        byte[] response = null;
        InstanceInfo instanceInfo = null;
        String instanceId = null;
        boolean complete = false;

        while (!complete) {
            instanceInfo = reserveLeastUsedInstance(requestWork);
            String instanceIpAddr = instanceInfo.getInstance().getPublicIpAddress();
            instanceId = instanceInfo.getInstance().getInstanceId();

            String serverUrl = "http://" + instanceIpAddr + ":" + WS_PORT + httpExchange.getRequestURI();
            System.out.println("Sending request to " + instanceId);
            try {
                response = tryToGetResponse(serverUrl, instanceInfo, requestWork);
            } catch (IOException e) {
                System.out.println("Connection to " + instanceId + " lost!");
                continue;
            }
            complete = true;
        }

        instanceInfo.decrementNumCurrentRequests();
        instanceInfo.decrementWork(requestWork);
        System.out.println("Received response from " + instanceId);

        httpExchange.sendResponseHeaders(200, 0);
        OutputStream outputStream = httpExchange.getResponseBody();
        outputStream.write(response);
        outputStream.close();
    }

    private byte[] tryToGetResponse(String serverUrl, InstanceInfo instanceInfo, double requestWork)
        throws IOException {
        HttpURLConnection connection = null;
        InputStream is = null;
        while (true) {
            try {
                connection = (HttpURLConnection) new URL(serverUrl).openConnection();
                is = connection.getInputStream();
                return tryToGetResponseBody(instanceInfo, is);

            } catch (IOException e) {
                if (AwsUtils.getInstanceStatus(instanceInfo.getInstance().getInstanceId()) == 16) { //running
                    try {
                        Thread.sleep(WS_WAIT_TIME);
                    } catch (InterruptedException e1) {
                        System.out.println("Thread was interrupted!");
                        instanceInfo.decrementNumCurrentRequests();
                        instanceInfo.decrementWork(requestWork);
                        Thread.currentThread().interrupt();
                    }
                } else { //instance was killed somehow
                    throw e;
                }

            } catch (InterruptedException e) {
                System.out.println("Thread was interrupted!");
                instanceInfo.decrementNumCurrentRequests();
                instanceInfo.decrementWork(requestWork);
                Thread.currentThread().interrupt();

            } finally {
                if (connection != null) {
                    connection.disconnect();
                    connection = null;
                }
                if (is != null) {
                    is.close();
                    is = null;
                }
            }
        }
    }

    private byte[] tryToGetResponseBody(InstanceInfo instanceInfo, InputStream is)
        throws IOException, InterruptedException {
        while (true) {
            if (is.available() != 0) {
                return toByteArray(is);
            } else {
                if (AwsUtils.getInstanceStatus(instanceInfo.getInstance().getInstanceId()) != 16) {
                    throw new IOException();
                } else {
                    Thread.sleep(CONNECTION_CHECK_INTERVAL);
                }
            }
        }
    }

    private byte[] toByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[16384];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        return buffer.toByteArray();
    }

    private double calculateEstimatedWork() {
        double estimatedWork = 200000000; //estimated average
        System.out.println("Estimated work for request: 200000000" + estimatedWork);
        return estimatedWork;
    }

    private InstanceInfo reserveLeastUsedInstance(double requestWork) {
        InstanceInfo instanceInfo = AwsUtils.getLeastUsedValidInstanceInfo();

        if (instanceInfo == null) {
            synchronized (this) {
                if (AwsUtils.liveInstancesCounter.get() == 0) {
                    AwsUtils.launchInstance();
                    waitForInstance(requestWork);
                }
            }
        }

        synchronized (this) {
            InstanceInfo leastUsedValidInstaceInfo = AwsUtils.getLeastUsedValidInstanceInfo();
            if (leastUsedValidInstaceInfo.getWork() + requestWork >= AutoScaler.MAX_WORKLOAD
                && leastUsedValidInstaceInfo.getNumCurrentRequests() > 0) {

                AwsUtils.launchInstance();
                waitForInstance(requestWork);
            }

            instanceInfo = AwsUtils.getLeastUsedValidInstanceInfo();
            instanceInfo.incrementNumCurrentRequests();
            instanceInfo.incrementWork(requestWork);
        }

        return instanceInfo;
    }

    private void waitForInstance(double requestWork) {
        InstanceInfo instanceInfo = null;
        while (instanceInfo == null || (instanceInfo.getWork() + requestWork >= AutoScaler.MAX_WORKLOAD
            && instanceInfo.getNumCurrentRequests() > 0)) {

            try {
                Thread.sleep(WS_WAIT_TIME);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            instanceInfo = AwsUtils.getLeastUsedValidInstanceInfo();
        }
    }

}
