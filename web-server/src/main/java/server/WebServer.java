package server;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import solver.Solver;
import solver.SolverArgumentParser;
import solver.SolverFactory;

import javax.imageio.ImageIO;

public class WebServer {

	public static void main(final String[] args) throws Exception {
		System.out.println("Starting server...");

		final HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
		System.out.println("Server created on port " + server.getAddress().getPort());

		server.createContext("/solve", new MyHandler());
		System.out.println("Context '/solve' created.");
		server.start();
		System.out.println("Server started and listening on: " + server.getAddress().toString());
	}

	static class MyHandler implements HttpHandler {
		@Override
		public void handle(final HttpExchange t) throws IOException {
			System.out.println("--------------------------------------------------");
			System.out.println("New request received from: " + t.getRemoteAddress().toString());

			final Headers hdrs = t.getResponseHeaders();
			hdrs.add("Content-Type", "image/png");
			hdrs.add("Access-Control-Allow-Origin", "*");
			hdrs.add("Access-Control-Allow-Credentials", "true");
			hdrs.add("Access-Control-Allow-Methods", "POST, GET, HEAD, OPTIONS");
			hdrs.add("Access-Control-Allow-Headers", "Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");

			final String query = t.getRequestURI().getQuery();
			System.out.println("Query received: " + query);

			t.sendResponseHeaders(200, 0);

			final String[] params = query.split("&");
			System.out.println("Parsed query parameters:");
			for (String p : params) {
				System.out.println(" -> " + p);
			}

			final ArrayList<String> newArgs = new ArrayList<>();
			for (final String p : params) {
				final String[] splitParam = p.split("=");
				newArgs.add("-" + splitParam[0]);
				newArgs.add(splitParam[1]);
				System.out.println("Added argument: -" + splitParam[0] + " " + splitParam[1]);
			}
			newArgs.add("-d");
			System.out.println("Added debug flag '-d'.");

			final String[] args = new String[newArgs.size()];
			for (int i = 0; i < newArgs.size(); i++) {
				args[i] = newArgs.get(i);
			}
			System.out.println("Arguments for solver: ");
			for (String arg : args) {
				System.out.println(" -> " + arg);
			}

			SolverArgumentParser ap = null;
			try {
				System.out.println("Parsing solver arguments...");
				ap = new SolverArgumentParser(args);
			} catch (Exception e) {
				System.out.println("Error parsing solver arguments: " + e);
				t.getResponseBody().close();
				return;
			}
			System.out.println("Finished parsing arguments.");

			System.out.println("Creating solver instance...");
			final Solver s = SolverFactory.getInstance().makeSolver(ap);
			System.out.println("Solver instance created: " + s.toString());

			File responseFile = null;
			try {
				System.out.println("Starting image computation...");
				final BufferedImage outputImg = s.solveImage();
				System.out.println("Image computation finished.");

				final String outPath = ap.getOutputDirectory();
				final String imageName = s.toString();
				System.out.println("Output path: " + outPath + ", Image name: " + imageName);

				final Path imagePathPNG = Paths.get(outPath, imageName);
				ImageIO.write(outputImg, "png", imagePathPNG.toFile());
				System.out.println("Image written to disk at: " + imagePathPNG.toString());

				responseFile = imagePathPNG.toFile();

			} catch (FileNotFoundException e) {
				System.out.println("FileNotFoundException during image processing: " + e);
				e.printStackTrace();
			} catch (IOException e) {
				System.out.println("IOException during image processing: " + e);
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				System.out.println("ClassNotFoundException during image processing: " + e);
				e.printStackTrace();
			}

			System.out.println("Sending response file to client...");
			final OutputStream os = t.getResponseBody();
			Files.copy(responseFile.toPath(), os);
			os.close();
			System.out.println("Response sent successfully to: " + t.getRemoteAddress().toString());
			System.out.println("--------------------------------------------------");
		}
	}
}
