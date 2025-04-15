package pt.ulisboa.tecnico.cnv.loadbalancer;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class LoadBalancerApplication {

    public static void main(final String[] args) throws Exception {

        AwsUtils.init();

        final HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/solve", new LoadBalancerHandler());

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println(server.getAddress().toString());

        AutoScaler.execute();
    }

}
